package com.baidukiller.lite.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import com.baidukiller.lite.AutoLearn
import com.baidukiller.lite.BlockStats
import com.baidukiller.lite.DnsLog
import com.baidukiller.lite.MainActivity
import com.baidukiller.lite.Prefs
import com.baidukiller.lite.R
import com.baidukiller.lite.SkipAdAccessibilityService
import com.baidukiller.lite.dns.BlockList
import com.baidukiller.lite.dns.DnsMessage
import com.baidukiller.lite.dns.UpstreamResolver
import com.baidukiller.lite.net.PacketUtil
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramSocket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * ============================================================================
 *  核心：本地 VPN 服务（只接管 DNS，不碰其它流量）
 * ============================================================================
 *
 *  工作方式
 *  --------
 *   1. 建立一个 VPN 隧道，接口地址 10.111.222.1/24；
 *   2. 把「DNS 服务器」设为你选择的上游公共 DNS（默认 223.5.5.5），
 *      并**只为这一个（或两个）地址**添加 /32 路由 → 只有发往它们的包会进隧道；
 *   3. App 的所有其它流量（TCP/HTTPS/视频…）不匹配任何路由，
 *      继续走原来的网络，**完全不经过我们**，因此不可能拖慢或弄坏正常上网；
 *   4. 隧道里读到的每个 UDP:53 报文：
 *        · 域名命中拦截规则 → 直接回 NXDOMAIN（域名不存在）
 *        · 其它域名         → 转发给上游公共 DNS，把应答写回隧道
 *
 *  为什么这样就等于"广告不被创建"
 *  --------
 *   广告 SDK 必须先解析广告域名（mobads.baidu.com 等）才能下载广告配置与素材。
 *   解析被拒 → SDK 拿到"域名不存在" → 没有配置、没有素材 → 开屏广告根本没有
 *   可展示的对象，宿主只能走"无广告"分支直接进入主界面。
 *   整个过程不 hook 任何 App、不需要 root、不使用无障碍服务、不模拟点击。
 *
 *  安全性设计
 *  --------
 *   · 只 route 一个 /32 地址，DNS 之外的报文一律不接管；
 *   · 默认只对百度系 App 生效（addAllowedApplication），其它 App 零影响；
 *   · 上游查询用 protect() 保护，杜绝自环；
 *   · 任何异常都被吞掉并记日志，最坏情况是"拦截失效"，而不是"上不了网"。
 * ============================================================================
 */
class AdBlockVpnService : VpnService() {

    companion object {
        private const val TAG = "disAD"

        const val ACTION_START = "com.baidukiller.lite.action.START"
        const val ACTION_STOP = "com.baidukiller.lite.action.STOP"

        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "bk_lite_vpn"

        /** 隧道内网卡地址（私有网段，几乎不可能与真实网络冲突） */
        private const val TUN_ADDRESS = "10.111.222.1"
        private const val TUN_PREFIX = 24

        @Volatile
        var running: Boolean = false
            private set

        /** 当前服务实例，便于界面直接停止，避免走 Intent */
        @Volatile
        private var instance: AdBlockVpnService? = null

        fun start(ctx: Context) {
            val intent = Intent(ctx, AdBlockVpnService::class.java).setAction(ACTION_START)
            try {
                ctx.startForegroundService(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "启动服务失败：${t.message}")
            }
        }

        fun stop(ctx: Context) {
            instance?.let {
                it.shutdown()
                return
            }
            try {
                ctx.startService(Intent(ctx, AdBlockVpnService::class.java).setAction(ACTION_STOP))
            } catch (t: Throwable) {
                Log.w(TAG, "停止服务失败：${t.message}")
            }
        }

        /** 供界面调用：规则变了，让运行中的服务立刻重新加载 */
        fun reloadRules(ctx: Context) {
            instance?.reloadRules()
        }
    }

    @Volatile
    private var tun: ParcelFileDescriptor? = null

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var executor: ExecutorService? = null

    @Volatile
    private var blockList: BlockList = BlockList()

    @Volatile
    private var resolver: UpstreamResolver? = null

    private val writeLock = Any()

    /** 规则条数，界面显示用 */
    val ruleCount: Int get() = blockList.size

    // ==================================================================
    //  生命周期
    // ==================================================================
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (!running) startTunnel()
        return START_STICKY
    }

    override fun onRevoke() {
        // 用户切换到别的 VPN，或系统收回权限
        Log.i(TAG, "VPN 权限被回收，停止拦截")
        shutdown()
        super.onRevoke()
    }

    override fun onDestroy() {
        instance = null
        shutdown()
        super.onDestroy()
    }

    // ==================================================================
    //  启动隧道
    // ==================================================================
    private fun startTunnel() {
        // 前台服务必须尽快启动，放最前面
        startForegroundSafely()

        blockList = BlockList().also {
            it.load(
                this,
                Prefs.customDomains(this),
                Prefs.whitelist(this),
                Prefs.autoAdded(this),
                Prefs.scopePdd(this)
            )
        }
        // 域名记录常开：它是「查看域名记录」与「自动学习」共同的数据来源，
        // 只存在本机内存 / 私有目录里，不联网、不上传。
        DnsLog.enabled = true

        // ------------------------------------------------------------------
        //  DNS 设计（三层兜底，核心是"宁可拦不住，也不能让它上不了网"）
        //  ------------------------------------------------------------------
        //  1. VPN 的 DNS 只挂我们自己的上游（默认 223.5.5.5 / 119.29.29.29），
        //     并只为它们加 /32 路由 → 报文进隧道，由我们过滤；
        //  2. 手机当前网络的 DNS **只作为我们自己转发时的候选**（走 protect 保护过的
        //     套接字直连出口），**绝不挂到 VPN 的 DNS 列表里**。
        //     ★ 这一点很关键：如果把系统 DNS 也挂上去，系统的解析器有可能把
        //       查询直接发给它 —— 那条路不经过我们的过滤，广告域名就被放行了，
        //       表现就是"某些广告域明明在规则里，却还是被解析出来"。
        //  3. 我们转发失败时**立刻回 SERVFAIL**（而不是静默丢包），
        //     让系统马上失败/切换，而不是干等几秒超时。
        //
        //  这三条一起：最坏情况只是"拦不住广告"，不会让手机上不了网，
        //  也不会出现"绕过过滤"的暗路。
        // ------------------------------------------------------------------
        val upstream1 = Prefs.upstream(this).let { if (isIpv4(it)) it else Prefs.DEFAULT_UPSTREAM }
        val upstream2 = Prefs.upstream2(this).let { if (isIpv4(it)) it else Prefs.DEFAULT_UPSTREAM2 }
        val systemDns = collectSystemDns()

        resolver = UpstreamResolver(upstream1, upstream2, systemDns) { socket: DatagramSocket ->
            protect(socket)
        }

        // 注意：VpnService.Builder 是 VpnService 的「内部类」（AOSP 中声明为非 static），
        // 因此只能在 VpnService 子类内部用 Builder() 直接构造。
        val builder = Builder()
            .setSession("disAD")
            .setMtu(1500)
            .addAddress(TUN_ADDRESS, TUN_PREFIX)
            .setBlocking(true)
            .addDnsServer(upstream1)
            .addRoute(upstream1, 32)

        if (upstream2 != upstream1) {
            builder.addDnsServer(upstream2)
            builder.addRoute(upstream2, 32)
        }

        // 作用范围：三个系别。
        //  - 「其他」开着 → 不加白名单 = 全局（所有 App）
        //  - 否则 → 只把选中的系别包名加进白名单
        val scopePkgs = Prefs.scopePackages(this)
        var allowedCount = 0
        if (scopePkgs.isNotEmpty()) {
            for (pkg in scopePkgs) {
                try {
                    builder.addAllowedApplication(pkg)
                    allowedCount++
                } catch (_: Throwable) {
                    // 该 App 未安装或不可见，跳过即可
                }
            }
        }
        if (allowedCount == 0) {
            Log.i(TAG, "作用范围：全局（所有 App 的 DNS 都过滤）")
        } else {
            Log.i(TAG, "作用范围：$allowedCount 个 App（${Prefs.scopeLabel(this)}）")
        }

        val established: ParcelFileDescriptor? = try {
            builder.establish()
        } catch (t: Throwable) {
            Log.e(TAG, "建立 VPN 隧道失败：${t.message}")
            null
        }

        if (established == null) {
            // 通常是用户没授权（VpnService.prepare 未返回 RESULT_OK）
            Log.w(TAG, "隧道建立失败：未获得 VPN 授权或系统拒绝")
            shutdown()
            return
        }

        val pfd: ParcelFileDescriptor = established
        tun = pfd
        running = true
        executor = Executors.newFixedThreadPool(4)

        worker = Thread({ tunnelLoop(pfd) }, "bk-dns-loop").also {
            it.isDaemon = true
            it.start()
        }
        Log.i(TAG, "拦截已启动：规则 ${blockList.size} 条，作用范围 ${Prefs.scopeLabel(this)}")
    }

    // ==================================================================
    //  隧道读取循环
    // ==================================================================
    private fun tunnelLoop(pfd: ParcelFileDescriptor) {
        val input: FileInputStream
        val output: FileOutputStream
        try {
            input = FileInputStream(pfd.fileDescriptor)
            output = FileOutputStream(pfd.fileDescriptor)
        } catch (t: Throwable) {
            Log.e(TAG, "打开隧道失败：${t.message}")
            shutdown()
            return
        }

        val buffer = ByteArray(32767)
        while (running) {
            val n = try {
                input.read(buffer)
            } catch (t: Throwable) {
                if (running) Log.w(TAG, "读取隧道异常：${t.message}")
                -1
            }
            if (n <= 0) {
                if (!running) break
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                }
                continue
            }
            // 一次 read 可能带回多个 IP 报文，按总长度逐个拆开
            var off = 0
            while (off + 20 <= n) {
                val total = PacketUtil.u16(buffer, off + 2)
                if (total < 20 || off + total > n) break
                try {
                    handleIpPacket(buffer, off, total, output)
                } catch (_: Throwable) {
                    // 单个报文处理失败不影响后续
                }
                off += total
            }
        }

        try {
            input.close()
        } catch (_: Throwable) {
        }
        try {
            output.close()
        } catch (_: Throwable) {
        }
    }

    // ==================================================================
    //  单个 IP 报文处理
    // ==================================================================
    private fun handleIpPacket(p: ByteArray, base: Int, total: Int, out: FileOutputStream) {
        if ((p[base].toInt() and 0xF0) != 0x40) return                 // 只管 IPv4
        val ihl = (p[base].toInt() and 0x0F) * 4
        if (ihl < 20 || base + ihl + 8 > base + total) return
        if ((p[base + 9].toInt() and 0xFF) != 17) return               // 只管 UDP

        val udp = base + ihl
        val dstPort = PacketUtil.u16(p, udp + 2)
        if (dstPort != 53) return                                      // 只管 DNS
        val srcPort = PacketUtil.u16(p, udp)

        val dnsOff = udp + 8
        val available = base + total - dnsOff
        val udpLen = PacketUtil.u16(p, udp + 4)
        val dnsLen = if (udpLen >= 8) minOf(udpLen - 8, available) else available
        if (dnsLen < 12) return

        val question = DnsMessage.parseQuestion(p, dnsOff, dnsLen) ?: return

        // 会话跟踪：两次解析间隔超过 30 秒 = 新的一次 App 启动。
        // 在新会话开始时，对"上一次启动"做一次自动学习。
        var newSession = false
        if (DnsLog.enabled) {
            newSession = DnsLog.beginSessionIfNeeded()
        }
        if (newSession && Prefs.autoLearn(this)) {
            executor?.execute { runAutoLearn() }
        }

        // 排查用：把每个被解析的域名都记下来（开关打开时才记录）
        val hit = blockList.isBlocked(question.name)
        DnsLog.record(question.name, hit)

        // ---------- 命中拦截规则：直接回 NXDOMAIN ----------
        if (hit) {
            val reply = DnsMessage.buildNxDomain(p, dnsOff, question) ?: return
            writeUdpReply(p, base, srcPort, reply, out)
            BlockStats.onBlocked(question.name)
            return
        }

        // ---------- 其它域名：异步转发给上游 DNS ----------
        BlockStats.onAllowed()
        val pool = executor ?: return
        val payload = p.copyOfRange(dnsOff, dnsOff + dnsLen)
        val ipHeader = p.copyOfRange(base, base + 20)
        val port = srcPort
        val questionCopy = question
        pool.execute {
            val upstreamReply = resolver?.query(payload, payload.size)
            if (upstreamReply != null) {
                writeUdpReply(ipHeader, 0, port, upstreamReply, out)
                return@execute
            }
            // ★ 所有上游都失败：回一个 SERVFAIL，让系统立刻切到下一个 DNS
            //   （底层网络的 DNS 也挂在 VPN 上，它会被直连问到）。
            //   这里绝不能"什么都不回" —— 那会让客户端干等超时，表现为"打不开"。
            val servfail = DnsMessage.buildError(payload, 0, questionCopy, DnsMessage.RCODE_SERVFAIL)
            if (servfail != null) {
                writeUdpReply(ipHeader, 0, port, servfail, out)
            }
        }
    }

    /**
     * 读取手机当前网络（Wi-Fi / 移动数据）实际使用的 DNS。
     * 这些地址只挂到 VPN 上、**不加路由** —— 报文直连出口，
     * 作为我们转发失败时的兜底，保证"最坏只是拦不住，不会上不了网"。
     */
    private fun collectSystemDns(): List<String> {
        val out = ArrayList<String>(2)
        try {
            val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return out
            val network = cm.activeNetwork ?: return out
            val lp = cm.getLinkProperties(network) ?: return out
            for (addr in lp.dnsServers) {
                val ip = addr?.hostAddress ?: continue
                if (ip.contains(':')) continue          // 只处理 IPv4
                if (isIpv4(ip) && !out.contains(ip)) out.add(ip)
                if (out.size >= 2) break
            }
        } catch (t: Throwable) {
            Log.w(TAG, "读取系统 DNS 失败：${t.message}")
        }
        return out
    }

    /**
     * 把 DNS 应答封装成 IPv4 + UDP 报文写回隧道。
     *
     * @param orig     原始报文缓冲区
     * @param origOff  原始 IP 首部偏移
     * @param origSrcPort 发起查询的源端口（应答要发回给它）
     * @param payload  DNS 应答内容
     */
    private fun writeUdpReply(
        orig: ByteArray,
        origOff: Int,
        origSrcPort: Int,
        payload: ByteArray,
        out: FileOutputStream
    ) {
        val total = 20 + 8 + payload.size
        val p = ByteArray(total)

        p[0] = 0x45.toByte()                                   // IPv4, 首部 20 字节
        p[1] = 0
        p[2] = ((total ushr 8) and 0xFF).toByte()
        p[3] = (total and 0xFF).toByte()
        p[4] = orig[origOff + 4]                               // 复用原报文的标识字段
        p[5] = orig[origOff + 5]
        p[6] = 0x40.toByte()                                   // Don't Fragment
        p[7] = 0
        p[8] = 64                                              // TTL
        p[9] = 17                                              // UDP
        // p[10] p[11] 留给首部校验和
        System.arraycopy(orig, origOff + 16, p, 12, 4)          // 新源 IP = 原目的 IP（10.111.222.2）
        System.arraycopy(orig, origOff + 12, p, 16, 4)          // 新目的 IP = 原源 IP

        val checksum = PacketUtil.ipv4Checksum(p, 0, 20)
        p[10] = ((checksum ushr 8) and 0xFF).toByte()
        p[11] = (checksum and 0xFF).toByte()

        p[20] = 0                                              // 源端口 53
        p[21] = 53
        p[22] = ((origSrcPort ushr 8) and 0xFF).toByte()
        p[23] = (origSrcPort and 0xFF).toByte()
        val udpLen = 8 + payload.size
        p[24] = ((udpLen ushr 8) and 0xFF).toByte()
        p[25] = (udpLen and 0xFF).toByte()
        p[26] = 0                                              // UDP 校验和 0 = 不校验（IPv4 合法）
        p[27] = 0

        System.arraycopy(payload, 0, p, 28, payload.size)

        synchronized(writeLock) {
            out.write(p)
            out.flush()
        }
    }

    // ==================================================================
    //  停止
    // ==================================================================
    private fun shutdown() {
        running = false
        try {
            notifyHandler.removeCallbacks(notifyTicker)
        } catch (_: Throwable) {
        }
        try {
            executor?.shutdownNow()
        } catch (_: Throwable) {
        }
        executor = null
        try {
            tun?.close()
        } catch (_: Throwable) {
        }
        tun = null
        try {
            stopForeground(true)
        } catch (_: Throwable) {
        }
        try {
            stopSelf()
        } catch (_: Throwable) {
        }
        Log.i(TAG, "拦截已停止")
    }

    // ==================================================================
    //  规则重载 / 自动学习
    // ==================================================================
    /** 用最新的配置重建规则表（切换开关、自动学习加入新域名后调用） */
    fun reloadRules() {
        try {
            blockList = BlockList().also {
                it.load(
                    this,
                    Prefs.customDomains(this),
                    Prefs.whitelist(this),
                    Prefs.autoAdded(this),
                    Prefs.scopePdd(this)
                )
            }
            Log.i(TAG, "规则已重新加载，共 ${blockList.size} 条生效域名")
        } catch (t: Throwable) {
            Log.w(TAG, "重新加载规则失败：${t.message}")
        }
    }

    /** 自动学习：把高置信度的漏网域名加进拦截名单 */
    private fun runAutoLearn() {
        try {
            val current = blockList
            val candidates = AutoLearn.autoCandidates(this) { current.isBlocked(it) }
            if (candidates.isEmpty()) return
            val added = AutoLearn.addDomains(this, candidates.map { it.domain }, toAutoList = true)
            if (added > 0) {
                reloadRules()
                Log.i(
                    TAG,
                    "自动学习：新增 $added 个拦截域名 → " +
                        candidates.joinToString(", ") { it.domain }
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "自动学习失败：${t.message}")
        }
    }

    // ==================================================================
    //  工具
    // ==================================================================
    /** 只接受 IPv4 字面量，避免把非法值塞进 VpnService.Builder 导致建立失败 */
    private fun isIpv4(s: String): Boolean {
        val parts = s.trim().split('.')
        if (parts.size != 4) return false
        for (p in parts) {
            val v = p.toIntOrNull() ?: return false
            if (v < 0 || v > 255) return false
        }
        return true
    }

    // ==================================================================
    //  前台通知：常驻显示"拦截正在运行 + 无障碍是否生效 + 各作用了几次"
    // ==================================================================
    /** 通知刷新用的主线程 Handler（通知内容每 2 秒对一次） */
    private val notifyHandler = Handler(Looper.getMainLooper())
    private var lastNotifySnapshot: String = ""
    private val notifyTicker = object : Runnable {
        override fun run() = refreshNotification()
    }

    private fun startForegroundSafely() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "广告拦截状态",
                    NotificationManager.IMPORTANCE_LOW
                )
                channel.description = "显示 disAD 的运行状态"
                channel.setShowBadge(false)
                nm.createNotificationChannel(channel)
            }
            startForeground(NOTIF_ID, buildNotification())
            startNotifyTicker()
        } catch (t: Throwable) {
            // 某些 ROM 对前台服务有额外限制；失败不影响拦截本身
            Log.w(TAG, "启动前台通知失败（不影响拦截）：${t.message}")
        }
    }

    /** 无障碍当前是否真的在起作用 */
    private fun accessibilityStateText(): String = when {
        !Prefs.autoSkip(this) -> "已关闭（开关）"
        SkipAdAccessibilityService.running -> "已生效"
        else -> "未授权 / 被系统关闭"
    }

    private fun buildNotification(): Notification {
        val blocked = BlockStats.blockedCount()
        val skipped = SkipAdAccessibilityService.skipCount
        val accState = accessibilityStateText()
        val scope = Prefs.scopeLabel(this)

        // 折叠状态：只讲"在不在拦、拦哪些、无障碍行不行"
        val summary = "拦截模式：$scope　·　无障碍：$accState"

        // 展开后才是明细（用户其实不需要天天看数字，但排查时要能看到）
        val detail = buildString {
            append("拦截模式：").append(scope).append('\n')
            append("无障碍辅助：").append(accState).append('\n')
            append("已拦截解析：").append(blocked).append(" 次\n")
            append("已跳过广告：").append(skipped).append(" 次\n")
            append("最近动作：").append(SkipAdAccessibilityService.lastAction)
        }

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAction = PendingIntent.getService(
            this, 1,
            Intent(this, AdBlockVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val accAction = PendingIntent.getActivity(
            this, 3,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("拦截已开启")
            .setContentText(summary)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setSmallIcon(R.drawable.ic_notify)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openApp)
            .addAction(0, "停止", stopAction)
            .addAction(0, "无障碍设置", accAction)
            .build()
    }

    /** 每 2 秒刷新一次通知内容（只在文案变化时真正 notify，避免无谓刷新） */
    private fun startNotifyTicker() {
        notifyHandler.removeCallbacks(notifyTicker)
        notifyHandler.postDelayed(notifyTicker, 2000L)
    }

    private fun refreshNotification() {
        if (!running) return
        try {
            val snapshot = Prefs.scopeLabel(this) + "|" + accessibilityStateText() + "|" +
                BlockStats.blockedCount() + "|" +
                SkipAdAccessibilityService.skipCount + "|" +
                SkipAdAccessibilityService.lastAction
            if (snapshot != lastNotifySnapshot) {
                lastNotifySnapshot = snapshot
                val nm = getSystemService(NotificationManager::class.java)
                nm?.notify(NOTIF_ID, buildNotification())
            }
        } catch (_: Throwable) {
        } finally {
            if (running) notifyHandler.postDelayed(notifyTicker, 2000L)
        }
    }
}
