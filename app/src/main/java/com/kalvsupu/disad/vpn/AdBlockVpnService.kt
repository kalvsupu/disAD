package com.kalvsupu.disad.vpn

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
import com.kalvsupu.disad.ApiReview
import com.kalvsupu.disad.AutoLearn
import com.kalvsupu.disad.BlockStats
import com.kalvsupu.disad.CrashLog
import com.kalvsupu.disad.DnsLog
import com.kalvsupu.disad.MainActivity
import com.kalvsupu.disad.Prefs
import com.kalvsupu.disad.R
import com.kalvsupu.disad.SkipAdAccessibilityService
import com.kalvsupu.disad.dns.BlockList
import com.kalvsupu.disad.dns.DnsMessage
import com.kalvsupu.disad.dns.UpstreamResolver
import com.kalvsupu.disad.net.PacketUtil
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramSocket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 */
class AdBlockVpnService : VpnService() {

    companion object {
        private const val TAG = "disAD"

        const val ACTION_START = "com.kalvsupu.disad.action.START"
        const val ACTION_STOP = "com.kalvsupu.disad.action.STOP"

        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "bk_lite_vpn"/** 连续读空多少次就判定隧道已死（20ms × 150 ≈ 3 秒） */
        private const val MAX_IDLE_READS = 150

        /** 隧道内网卡地址（私有网段，几乎不可能与真实网络冲突） */
        private const val TUN_ADDRESS = "10.111.222.1"
        private const val TUN_PREFIX = 24

        @Volatile
        private var owner: AdBlockVpnService? = null

        /**
         */
        val running: Boolean
            get() = owner?.isUp == true
        /** 后台隐藏：撤掉常驻通知（服务仍在跑，只是通知栏不再显示） */
        fun hideNotification(ctx: Context) {
            try {
                ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
            } catch (_: Throwable) {
            }
        }

        /** 回到前台：把通知补回来 */
        fun restoreNotification(ctx: Context) {
            val svc = owner ?: return
            if (!svc.isUp) return
            try {
                svc.getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIF_ID, svc.buildNotification())
            } catch (_: Throwable) {
            }
        }

        /** 当前是否有实例持有隧道（内部用，和 running 同源） */
        val ownerInstance: AdBlockVpnService?
            get() = owner

        fun start(ctx: Context) {
            val intent = Intent(ctx, AdBlockVpnService::class.java).setAction(ACTION_START)
            try {
                ctx.startForegroundService(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "启动服务失败：${t.message}")
            }
        }

        fun stop(ctx: Context) {
            // 只有"当前所有者"才走直接收尾；否则退回 Intent，避免误伤别人
            val o = owner
            if (o != null) {
                o.requestShutdown("界面停止")
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
            owner?.reloadRules()
        }

        /** 清理线程：做 join / close 这种可能阻塞的收尾，绝不占用主线程 */
        private val reaper: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "bk-reaper").also { it.isDaemon = true }
        }
    }

    // ==================================================================
    //  实例状态
    /**
     */
    private enum class Phase { IDLE, STARTING, UP, STOPPING }

    @Volatile
    private var phase: Phase = Phase.IDLE

    /** 本实例是否真的建好了隧道 */
    private val isUp: Boolean
        get() = phase == Phase.UP

    /**
     */
    @Volatile
    private var loopStop = false

    @Volatile
    private var tun: ParcelFileDescriptor? = null

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var executor: ExecutorService? = null

    /** 被丢弃的 TCP/53 包（目前不代理 TCP DNS，仅计数用于诊断） */
    @Volatile
    private var droppedTcpDns: Long = 0

    /** 拦截计数用的滴答器：每 200 次看一眼 AI 审查阈值 + 顺手落盘 */
    private var reviewTick: Long = 0

    /** 上一次 startTunnel 是否失败过 —— 失败就不再 STICKY，避免"崩→重启→崩"死循环 */
    @Volatile
    private var startupFailed = false

    /** 本实例是否已经贴过前台通知（启动失败时要能撤掉） */
    private var foregroundShown = false

    @Volatile
    private var blockList: BlockList = BlockList()

    @Volatile
    private var resolver: UpstreamResolver? = null

    private val writeLock = Any()

    /** 规则条数，界面显示用 */
    val ruleCount: Int get() = blockList.size

    // ==================================================================
    //  生命周期
    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestShutdown("收到停止指令")
            return START_NOT_STICKY
        }
        // START_STICKY 被系统拉起时 intent 可能为 null：按"重新启动"处理
        if (!isUp && phase != Phase.STARTING) {
            startTunnel()
        }
        // ★ 只有"隧道真的建起来"才允许系统粘回来。
        //   跑都没跑起来还 STICKY，等于让系统在崩溃边缘疯狂拉活 —— 越拉越崩。
        return if (isUp && !startupFailed) START_STICKY else START_NOT_STICKY
    }

    override fun onRevoke() {
        // 用户切换到别的 VPN，或系统收回权限。
        // 只做"本实例"的收尾：owner 不是自己时，绝不碰全局状态（防自杀）。
        Log.i(TAG, "VPN 权限被回收，停止拦截")
        requestShutdown("VPN 权限被回收")
        super.onRevoke()
    }

    override fun onDestroy() {
        // onDestroy 可能在任何时刻到来（包括新实例已经起来之后），
        // 所以这里同样只能收自己的摊子。
        requestShutdown("服务销毁", fromDestroy = true)
        super.onDestroy()
    }

    // ==================================================================
    //  启动隧道
    /**
     * 启动阶段失败：给出原因、撤掉前台通知、结束服务。
     * 与"正常运行中被停止"分开，避免启动失败时留下一条僵尸通知。
     */
    private fun failStartup(reason: String) {
        startupFailed = true
        phase = Phase.IDLE
        Log.w(TAG, "启动失败（$reason）")
        CrashLog.record(this, "启动拦截失败：$reason", IllegalStateException(reason))
        try {
            android.widget.Toast.makeText(this, "启动失败：$reason", android.widget.Toast.LENGTH_LONG).show()
        } catch (_: Throwable) {
        }
        requestShutdown("启动失败：$reason")
    }

    private fun startTunnel() {
        // ★ 整个启动过程必须兜住。
        //   Service 回调里抛异常 = 进程直接崩溃；而这个服务返回 START_STICKY，
        phase = Phase.STARTING
        try {
            startTunnelInternal()
        } catch (t: Throwable) {
            startupFailed = true
            phase = Phase.IDLE
            CrashLog.record(this, "启动拦截失败", t)
            Log.e(TAG, "启动拦截失败：${t.message}", t)
            requestShutdown("启动失败")
        }
    }

    private fun startTunnelInternal() {
        // ------------------------------------------------------------------
        //  清创：建新隧道之前，把上一轮的一切先弄死。
        forceCleanup("启动前清创")
        phase = Phase.STARTING

        // 每次真正启动隧道（含"停止拦截"后重新开启）都把本次会话计数归零。
        // 这是用户明确要的口径：只有重新启动拦截才清零，切回界面不清零。
        BlockStats.resetSession()

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
        // ★ 作用范围必须明确区分三种情况：
        //   ① 打开「其他」= 全局（有意为之）
        //   ② 选了具体 App     = 白名单
        //   ③ 一个 App 都没有/全加失败 = **不能启动**，否则 Android 会按"没加白名单 = 全部 App"处理，
        //      悄悄从"限定范围"变成"全局"，这正是审计指出的问题。
        val globalScope = Prefs.scopeOther(this)
        val scopePkgs = Prefs.scopePackages(this)
        var allowedCount = 0
        if (!globalScope) {
            if (scopePkgs.isEmpty()) {
                failStartup("作用范围为空：请至少选中一个 App，或打开「其他」")
                return
            }
            for (pkg in scopePkgs) {
                try {
                    builder.addAllowedApplication(pkg)
                    allowedCount++
                } catch (_: Throwable) {
                    // 该 App 未安装或不可见
                }
            }
            if (allowedCount == 0) {
                failStartup("作用范围内的 App 都无法加入（未安装/不可见）：已取消启动，避免变成全局拦截")
                return
            }
            Log.i(TAG, "作用范围：$allowedCount 个 App（${Prefs.scopeLabel(this)}）")
        } else {
            Log.i(TAG, "作用范围：全局（「其他」已打开）")
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
            requestShutdown("隧道建立失败")
            return
        }

        val pfd: ParcelFileDescriptor = established
        tun = pfd
        loopStop = false
        executor = java.util.concurrent.ThreadPoolExecutor(
            4, 4, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            java.util.concurrent.ArrayBlockingQueue(256),
            { r -> Thread(r, "bk-dns-fwd").also { it.isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.DiscardPolicy()   // 队列满就丢，绝不堆内存
        )

        // ★ 先把 phase 置 UP、再登记 owner：这样界面读到的 running 一定是"隧道已就绪"
        phase = Phase.UP
        startupFailed = false
        owner = this

        worker = Thread({ tunnelLoop(pfd) }, "bk-dns-loop").also {
            it.isDaemon = true
            it.start()
        }
        Log.i(TAG, "拦截已启动：规则 ${blockList.size} 条，作用范围 ${Prefs.scopeLabel(this)}")
    }

    // ==================================================================
    //  隧道读取循环
    private fun tunnelLoop(pfd: ParcelFileDescriptor) {
        val input: FileInputStream
        val output: FileOutputStream
        try {
            input = FileInputStream(pfd.fileDescriptor)
            output = FileOutputStream(pfd.fileDescriptor)
        } catch (t: Throwable) {
            Log.e(TAG, "打开隧道失败：${t.message}")
            requestShutdown("打开隧道失败")
            return
        }

        val buffer = ByteArray(32767)
        var idle = 0          // 连续"读不到东西"的次数
        while (!loopStop) {
            val n = try {
                input.read(buffer)
            } catch (t: Throwable) {
                // fd 被关掉时这里会抛异常 —— 属正常退出路径，不算故障
                if (!loopStop) {
                    Log.w(TAG, "读取隧道异常，收尾：${t.message}")
                    requestShutdown("读取隧道异常")
                }
                break
            }
            if (n <= 0) {
                // 读不到数据：可能是正常的静默期，也可能是隧道已经死了。
                // 静默期不该让线程空转，死了也不该无限重试。
                if (loopStop) break
                if (++idle > MAX_IDLE_READS) {
                    Log.w(TAG, "隧道连续 $idle 次读空，判定已失效，主动收尾")
                    requestShutdown("隧道读空")
                    break
                }
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }
            idle = 0
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
        Log.i(TAG, "隧道读取线程已退出")
    }

    // ==================================================================
    //  单个 IP 报文处理
    private fun handleIpPacket(p: ByteArray, base: Int, total: Int, out: FileOutputStream) {
        if ((p[base].toInt() and 0xF0) != 0x40) return                 // 只管 IPv4
        val ihl = (p[base].toInt() and 0x0F) * 4
        if (ihl < 20 || base + ihl + 8 > base + total) return
        val proto = p[base + 9].toInt() and 0xFF
        if (proto == 6) {
            // TCP：目前不代理（只处理 UDP/53）。发往被接管的 DNS 地址的 TCP 会被丢弃，
            // 这里计数并在日志里提示一次，避免"悄悄失败"。
            val udpOff = base + ihl
            if (udpOff + 4 <= p.size && PacketUtil.u16(p, udpOff + 2) == 53) {
                droppedTcpDns++
                if (droppedTcpDns % 64L == 1L) {
                    Log.w(TAG, "收到 TCP/53 查询（暂不支持，已丢弃）：累计 $droppedTcpDns 个")
                }
            }
            return
        }
        if (proto != 17) return                               // 只管 UDP

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
        // AI 审查：会话切换时判断要不要跑（没配 Key 时它自己会立刻返回）
        if (newSession) {
            try {
                DnsLog.saveToDisk(this)
                ApiReview.maybeReviewAsync(this)
            } catch (_: Throwable) {
            }
        }

        // 排查用：把每个被解析的域名都记下来（开关打开时才记录）
        val hit = blockList.isBlocked(question.name)
        DnsLog.record(question.name, hit)

        // ---------- 命中拦截规则：直接回 NXDOMAIN ----------
        if (hit) {
            val reply = DnsMessage.buildNxDomain(p, dnsOff, question) ?: return
            writeUdpReply(p, base, srcPort, reply, out)
            BlockStats.onBlocked(this, question.name)

            // 每 200 次拦截检查一次 AI 审查阈值。
            // 原来只在"新会话"（DNS 静默 30 秒后）检查一次，一条隧道连续用几天
            // 也跨不过 10000 次，用户那边就出现"拦了 17000 次却从没审查过"。
            if (reviewTick++ % 200L == 0L) {
                try {
                    DnsLog.saveToDisk(this)
                    ApiReview.maybeReviewAsync(this)
                } catch (_: Throwable) {
                }
            }
            return
        }

        // ---------- 其它域名：异步转发给上游 DNS ----------
        BlockStats.onAllowed(this)
        val pool = executor ?: return
        val payload = p.copyOfRange(dnsOff, dnsOff + dnsLen)
        val ipHeader = p.copyOfRange(base, base + 20)
        val port = srcPort
        val questionCopy = question
        pool.execute {
            // ★ 这个 lambda 跑在线程池里，抛出去就是**进程级崩溃**（没有任何地方接住）。
            //   实测就是这么闪退的：SERVFAIL 分支下标越界 → 直接闪退。
            try {
                val upstreamReply = resolver?.query(payload, payload.size)
                if (upstreamReply != null) {
                    writeUdpReply(ipHeader, 0, port, upstreamReply, out)
                    return@execute
                }
                // ★ 所有上游都失败：回一个 SERVFAIL，让系统立刻切到下一个 DNS
                //   （底层网络的 DNS 也挂在 VPN 上，它会被直连问到）。
                val qLen = (questionCopy.end - dnsOff).coerceIn(12, payload.size)
                val servfail = DnsMessage.buildError(payload, 0, qLen, DnsMessage.RCODE_SERVFAIL)
                if (servfail != null) {
                    writeUdpReply(ipHeader, 0, port, servfail, out)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "转发线程异常（已忽略）：${t.message}")
            }
        }
    }

    /**
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
    //  停止 / 清创
    /**
     */
    private fun requestShutdown(reason: String, fromDestroy: Boolean = false) {
        if (phase == Phase.STOPPING) return
        val wasOwner = owner === this
        phase = Phase.STOPPING

        // 停服前把统计和近 N 次记录落盘：下次打开还能看到（统计用 commit 立即写）
        try {
            DnsLog.saveToDisk(this, true)
            BlockStats.flush(this, sync = true)
        } catch (_: Throwable) {
        }

        teardownResourcesOnly()

        // ① 本实例自己的收尾：只要贴过前台通知就撤，不管是不是 owner。
        //    ★ 启动失败时还没登记 owner，这里如果只认 owner 就会留下一条僵尸通知。
        if (foregroundShown) {
            foregroundShown = false
            try {
                notifyHandler.removeCallbacks(notifyTicker)
            } catch (_: Throwable) {
            }
            try {
                stopForeground(true)
                // VPN 停了：无障碍如果还在跑，把它的通知补回来
                com.kalvsupu.disad.SkipAdAccessibilityService.restoreOngoingIfNeeded()
            } catch (_: Throwable) {
            }
        }

        // ② 全局状态只有 owner 才能动，避免旧实例踩掉新实例
        if (wasOwner && owner === this) {
            owner = null
            // onDestroy 里再 stopSelf 没意义，还可能引发一次多余 stop
            if (!fromDestroy) {
                try {
                    stopSelf()
                } catch (_: Throwable) {
                }
            }
            Log.i(TAG, "拦截已停止（$reason）")
        } else {
            Log.i(TAG, "本实例已收尾，未触碰当前隧道（$reason）")
        }
        phase = Phase.IDLE
    }

    /**
     */
    private fun teardownResourcesOnly() {
        loopStop = true

        val p = tun
        tun = null
        if (p != null) {
            try {
                p.close()
            } catch (_: Throwable) {
            }
        }

        val w = worker
        worker = null
        if (w != null && w !== Thread.currentThread()) {
            try {
                w.interrupt()
            } catch (_: Throwable) {
            }
            try {
                reaper.execute {
                    try {
                        w.join(700)
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }

        val ex = executor
        executor = null
        try {
            ex?.shutdownNow()
        } catch (_: Throwable) {
        }
    }

    /**
     */
    private fun forceCleanup(reason: String) {
        teardownResourcesOnly()

        // 别的实例还占着 owner：说明它是被顶掉后没走干净的，安静地替它收资源
        val other = owner
        if (other != null && other !== this) {
            Log.w(TAG, "发现残留的旧隧道实例，先清掉它的资源（$reason）")
            try {
                other.teardownResourcesOnly()
            } catch (_: Throwable) {
            }
        }

        if (owner === this || (other != null && !other.isUp)) {
            owner = null
        }
        phase = Phase.IDLE
        loopStop = false        // 清干净了，准备给新隧道用
    }

    // ==================================================================
    //  规则重载 / 自动学习
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
    /** 通知刷新用的主线程 Handler（通知内容每 2 秒对一次） */
    private val notifyHandler = Handler(Looper.getMainLooper())
    private var lastNotifySnapshot: String = ""
    private val notifyTicker = object : Runnable {
        override fun run() = refreshNotification()
    }

    private fun startForegroundSafely() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm != null) {
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        "广告拦截状态",
                        NotificationManager.IMPORTANCE_LOW
                    )
                    channel.description = "显示 disAD 的运行状态"
                    channel.setShowBadge(false)
                    nm.createNotificationChannel(channel)
                }            }
            startForeground(NOTIF_ID, buildNotification())
            foregroundShown = true
            startNotifyTicker()
            // ★ 通知合并：VPN 这条已经在显示状态，撤掉无障碍那条，避免两条并存
            try {
                com.kalvsupu.disad.SkipAdAccessibilityService.hideOngoingBecauseVpn()
            } catch (_: Throwable) {
            }
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
        // 必须与主界面上的大数字同源，否则会出现"通知栏写着 250、切回界面却是 0"。
        // sessionBlockedCount 只在重新启动拦截 / 进程重启时归零，正好对上用户的口径。
        val blocked = BlockStats.sessionBlockedCount()
        val skipped = SkipAdAccessibilityService.skipCount
        val accState = accessibilityStateText()
        val scope = Prefs.scopeLabel(this)

        // 折叠状态：只讲"在不在拦、拦哪些、无障碍行不行"
        // ★ 只保留一条通知时必须"一眼看到全部信息"：把数字放进折叠行
        val summary = "已拦 $blocked 次　·　已跳过 $skipped 次　·　无障碍：$accState"

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

        val ch = CHANNEL_ID
        return Notification.Builder(this, ch)
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
        // ★ 用 isUp / STARTING 判断，而不是 running：
        //   establish() 有可能超过 2 秒，那时 running 还是 false，
        val active = isUp || phase == Phase.STARTING
        if (!active) return
        try {
            val snapshot = Prefs.scopeLabel(this) + "|" + accessibilityStateText() + "|" +
                BlockStats.sessionBlockedCount() + "|" +
                SkipAdAccessibilityService.skipCount + "|" +
                SkipAdAccessibilityService.lastAction
            if (snapshot != lastNotifySnapshot) {
                lastNotifySnapshot = snapshot
                val nm = getSystemService(NotificationManager::class.java)
                nm?.notify(NOTIF_ID, buildNotification())
            }
        } catch (_: Throwable) {
        } finally {
            if (isUp || phase == Phase.STARTING) notifyHandler.postDelayed(notifyTicker, 2000L)
        }
    }
}
