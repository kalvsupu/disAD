package com.kalvsupu.disad

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import android.widget.Toast
import com.kalvsupu.disad.vpn.AdBlockVpnService

/**
 */
class SkipAdAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "disAD"

        @Volatile
        var running: Boolean = false
            private set

        /** 跳过的广告数（同一个窗口只计一次） */
        @Volatile
        var skipCount: Int = 0
    // ---------------- OCR 兜底（实验） ----------------
    /** OCR 专用线程池：绝不占用无障碍回调线程（在主线程跑推理 = ANR） */
    private val ocrPool: ExecutorService = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "disad-ocr").also { it.isDaemon = true }
    }
    private val uiHandler = Handler(Looper.getMainLooper())

    /** OCR 兜底：每窗口最多试几次、候选块上限、最小间隔 */
    private val MAX_OCR_SHOTS = 3

    /** OCR 配额窗口：每 8 秒最多 3 张截图（约 100ms/张，功耗可控） */
    private val OCR_QUOTA_WINDOW_MS = 8_000L
    private val MAX_OCR_CANDS = 4
    private val OCR_MIN_GAP_MS = 2_500L
    /** 距上次 OCR 超过这么久就算"新的一轮"，配额重置（小程序启动页会吃掉配额） */
    private val OCR_WATCHDOG_MS = 4_000L

    @Volatile
    private var ocrBusy = false

    /** 每个窗口最多试几次 OCR（第一次没命中，等界面变化再说） */
    private var ocrShotsInWindow = 0
    private var ocrPkg = ""
    private var lastOcrAt = 0L

    /** 本轮 OCR 配额的起点：每 8 秒重置一次截图次数，避免一个 App 只截 2 张就再也不截 */
    private var ocrQuotaStartAt = 0L
    /** 每次 OCR 请求的序号：回来的结果如果比当前旧，直接丢弃（防止状态卡在旧文案） */
    private var ocrSeq = 0
    /** 系统明确拒绝截图能力时记下原因，之后不再重试 */
    @Volatile
    private var ocrUnavailableReason: String? = null

    /** 模型是否正在后台初始化 */
    @Volatile
    private var ocrInitializing = false

    /** 当前挂着的看门狗（完成时取消，避免旧看门狗踩新请求） */
    private var pendingWatchdog: Runnable? = null

    /** 窗口代数：切换窗口/解绑时 +1，过期的 OCR 结果一律丢弃 */
    @Volatile
    private var ocrEpoch = 0
    private var lastToastAt: Long = 0L
            private set

        /** 实际点击次数（排查用） */
        @Volatile
        var clickCount: Int = 0
            private set

        /** 出现过广告的窗口数（用来算"跳过成功率"） */
        @Volatile
        var adSeenCount: Int = 0
            private set

        /**
         */
        @Volatile
        var eventCount: Int = 0
            private set

        @Volatile
        var scanCount: Int = 0
            private set

        @Volatile
        var lastAction: String = "—"
            private set

        /** 服务实例，供调试导出节点结构使用 */
        @Volatile
        private var instance: SkipAdAccessibilityService? = null

        /** 前 FAST_WINDOW_MS 之内用 FAST_INTERVAL_MS 快速扫描；之后转成 SLOW_INTERVAL_MS */
        private const val FAST_WINDOW_MS = 6_000L
        private const val FAST_INTERVAL_MS = 250L

        /**
         */
        private const val SLOW_INTERVAL_MS = 800L

        /**
         */
        private const val EVENT_MIN_INTERVAL_MS = 250L

        /** 允许点击的三个区域 */
        private const val ZONE_TOP_RIGHT = 0     // 右上角（最稳）
        private const val ZONE_TOP_BAND = 1      // 屏幕顶部 22%（微信小程序在左上角）
        private const val ZONE_BOTTOM_BAND = 2   // 屏幕右下角（B站这类在右下角）

        private const val ACTIVE_WINDOW_MS = 15_000L
        private const val ELDER_ACTIVE_WINDOW_MS = 25_000L

        private const val MAX_ACTIONS_PER_WINDOW = 5
        private const val SUPPRESS_AFTER_CLICK_MS = 1200L
        private const val GLOBAL_COOLDOWN_MS = 1000L

        private const val CHANNEL_ALERT = "bk_lite_alert"
        private const val NOTIF_ALERT_ID = 1002
        private const val NOTIF_ONGOING_ID = 1003
        private const val CHANNEL_ONGOING = "disad_a11y_status"

        /** VPN 起来了：撤掉无障碍这条（除非本来就是它自己显示） */
        fun hideOngoingBecauseVpn() {
            instance?.cancelOngoing()
        }

        /** VPN 停了：无障碍如果还在跑，把通知补回来 */
        fun restoreOngoingIfNeeded() {
            instance?.let { svc ->
                if (Prefs.autoSkip(svc)) svc.showOngoingNotification()
            }
        }

        /** 供界面调用：无痕开关切换后立刻刷新常驻通知 */
        fun refreshOngoing() {
            instance?.let { svc ->
                if (Prefs.autoSkip(svc)) svc.showOngoingNotification() else svc.cancelOngoing()
            }
        }

        // ==================================================================
        //  ★ 隐私红线：界面上的文字绝不外泄
        private val ALLOWED_RULES = setOf(
            "跳过", "跳过(id)", "跳过(顶部)", "跳过(顶部/精确)", "跳过(右下)", "跳过(OCR)",
            "关闭(id)", "关闭", "关弹窗", "兜底"
        )
        private val ALLOWED_WORDS = setOf("跳过", "关闭")

        /** 状态卡片里把常见 App 显示成中文名（包名不是用户内容，可以出现） */
        private val FRIENDLY_PACKAGES = mapOf(
            "com.tencent.mm" to "微信",
            "com.tencent.mobileqq" to "QQ",
            "com.tencent.qqmusic" to "QQ音乐",
            "com.tencent.qqlive" to "腾讯视频",
            "tv.danmaku.bili" to "B站",
            "com.baidu.BaiduMap" to "百度地图",
            "com.baidu.netdisk" to "百度网盘",
            "com.baidu.tieba" to "贴吧",
            "com.baidu.searchbox" to "百度",
            "com.xunmeng.pinduoduo" to "拼多多",
            "com.ss.android.ugc.aweme" to "抖音",
            "com.taobao.taobao" to "淘宝",
            "com.eg.android.AlipayGphone" to "支付宝",
            "com.sina.weibo" to "微博",
            "com.zhihu.android" to "知乎",
            "com.netease.cloudmusic" to "网易云音乐",
            "com.qiyi.video" to "爱奇艺",
            "com.youku.phone" to "优酷"
        )

        private fun safeTag(value: String, allowed: Set<String>): String =
            if (allowed.contains(value)) value else "规则"
    }

    // ---------------- 状态 ----------------
    private var activeUntil = 0L
    private var windowStartedAt = 0L
    private var lastScanAt = 0L
    private var actionsThisWindow = 0
    private var countedThisWindow = false
    private var adSeenThisWindow = false
    private var suppressUntil = 0L
    private var globalCooldownUntil = 0L
    private var currentWindowClass = ""
    private var currentPkg = ""

    // 定时扫描（不依赖 App 发事件）
    private val scanHandler = Handler(Looper.getMainLooper())
    private var burstPkg = ""
    private val burstRunner = object : Runnable {
        override fun run() {
            if (burstPkg.isEmpty() || !running) return
            val now = SystemClock.uptimeMillis()
            tryScan(now, force = true)
            // 前 3.5 秒 250ms 一次；之后转慢速，但**不停止** ——
            // 广告经常是页面加载几秒之后才出现的（小程序尤其明显）
            val delay = if (now - windowStartedAt < FAST_WINDOW_MS) {
                FAST_INTERVAL_MS
            } else {
                SLOW_INTERVAL_MS
            }
            if (burstPkg.isNotEmpty()) scanHandler.postDelayed(this, delay)
        }
    }

    private fun startBurst() {
        burstPkg = currentPkg
        scanHandler.removeCallbacks(burstRunner)
        scanHandler.postDelayed(burstRunner, 120L)
    }

    private fun stopBurst() {
        burstPkg = ""
        scanHandler.removeCallbacks(burstRunner)
    }

    // ==================================================================
    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        instance = this
        // 用户亲手开启（或系统重启服务）= 新的一轮，跳过计数从头算；
        // 从后台切回界面不清零（重置点不放 Activity 里）。
        skipCount = 0
        clickCount = 0
        lastAction = "服务已连接"
        Log.i(TAG, "无障碍辅助已连接")
        showOngoingNotification()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        running = false
        // ★ 这里不把 instance 置空：系统偶尔会"解绑又立刻重绑"，
        //   置空会让调试导出误报"无障碍服务未连接"，也会让状态卡片误判。
        lastAction = "服务已断开"
        ocrEpoch++
        pendingWatchdog?.let { uiHandler.removeCallbacks(it) }
        pendingWatchdog = null
        ocrBusy = false
        cancelOngoing()
        Log.i(TAG, "无障碍辅助已断开")
        notifyAccessibilityLost()
        return super.onUnbind(intent)
    }

    /** 系统重新绑定时（解绑又重绑）把状态恢复回来，不用等 onServiceConnected */
    override fun onRebind(intent: Intent?): Unit {
        running = true
        instance = this
        lastAction = "服务已重新连接"
        Log.i(TAG, "无障碍辅助已重新连接")
        super.onRebind(intent)
    }

    override fun onDestroy() {
        running = false
        instance = null
        stopBurst()
        super.onDestroy()
    }

    override fun onInterrupt() {
    }

    // ==================================================================
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // ★ 先计数：这是"系统到底有没有把事件送进来"的唯一直接证据
        eventCount++
        if (!Prefs.autoSkip(this)) {
            logGate(event, "无障碍辅助开关是关的")
            return
        }
        // ★ 无障碍只在"拦截正在运行"时动作。
        //   否则会出现用户已经点了「停止拦截」，无障碍还在点东西的情况
        if (!AdBlockVpnService.running && !Prefs.a11yOnly(this)) {
            logGate(event, "拦截没有在运行")
            if (burstPkg.isNotEmpty()) stopBurst()
            return
        }

        val pkg = event.packageName?.toString() ?: return

        // 换 App 了：OCR 兜底的次数重新计数
        // ★ 换窗口/换 App：让还没回来的 OCR 结果作废（避免点到新界面）
        ocrEpoch++
        if (pkg != ocrPkg) {
            ocrPkg = pkg
            ocrShotsInWindow = 0
            lastOcrAt = 0L
        }
        val now = SystemClock.uptimeMillis()

        if (!inScope(pkg)) {
            logGate(event, "不在作用范围")
            // 切到无关 App：停掉定时扫描，别在别人的界面上乱扫
            if (burstPkg.isNotEmpty()) stopBurst()
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                currentPkg = pkg
                activeUntil = now + windowDuration()
                windowStartedAt = now
                actionsThisWindow = 0
                countedThisWindow = false
                adSeenThisWindow = false
                lastScanAt = 0L
                currentWindowClass = try {
                    event.className?.toString() ?: ""
                } catch (_: Throwable) {
                    ""
                }
                // ★ 关键：自己起定时器扫，不再等 App 发事件
                startBurst()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
            }
            else -> return
        }

        tryScan(now, force = false)
    }

    /** 只是"没轮到我"的情况，写进 logcat 就够了（只在窗口切换时记一条，不刷屏） */
    private fun logGate(event: AccessibilityEvent, why: String) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        Log.i(TAG, "跳过 ${event.packageName}：$why（事件 $eventCount）")
    }

    /**
     */
    private fun tryScan(now: Long, force: Boolean) {
        if (!running) return
        if (!isScreenInteractive()) return
        if (!force && now > activeUntil) return
        if (now < globalCooldownUntil) return
        if (now < suppressUntil) return
        if (actionsThisWindow >= MAX_ACTIONS_PER_WINDOW) return
        if (!force && lastScanAt != 0L && now - lastScanAt < EVENT_MIN_INTERVAL_MS) return

        lastScanAt = now

        try {
            val root = rootInActiveWindow ?: return
            scan(root, currentPkg, now)
        } catch (t: Throwable) {
            Log.w(TAG, "扫描失败：${t.message}")
        }
    }

    /** 锁屏时不做无谓扫描 */
    private fun isScreenInteractive(): Boolean = try {
        val pm = getSystemService(PowerManager::class.java)
        pm == null || pm.isInteractive
    } catch (_: Throwable) {
        true
    }

    private fun pkgOf(node: AccessibilityNodeInfo): String = try {
        node.packageName?.toString() ?: "?"
    } catch (_: Throwable) {
        "?"
    }

    private fun windowDuration(): Long =
        ACTIVE_WINDOW_MS

    /** 作用范围与 DNS 拦截保持一致（三个系别 + 老年人模式全局） */
    private fun inScope(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        if (pkg == packageName) return false
        if (SYSTEM_PACKAGES.contains(pkg)) return false
        return Prefs.inScope(this, pkg)
    }

    /**
     */
    private val SYSTEM_PACKAGES = setOf(
        // 系统界面
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        // 桌面
        "com.miui.home",
        "com.android.launcher",
        "com.android.launcher3",
        "com.bbk.launcher2",
        "com.oppo.launcher",
        "com.huawei.android.launcher",
        // 内容 / 编辑类：里面的文字是用户自己的
        "com.miui.notes",
        "com.miui.gallery",
        "com.android.gallery3d",
        "com.miui.filemanager",
        "com.android.documentsui",
        "com.google.android.documentsui",
        "com.android.fileexplorer",
        "com.android.providers.downloads.ui",
        "com.google.android.apps.docs",
        "com.android.mms",
        "com.miui.smsextra",
        "com.android.contacts",
        "com.android.email",
        "com.android.calendar",
        "com.miui.calculator",
        "com.android.deskclock",
        "com.android.camera",
        "com.miui.camera",
        "com.miui.screenshot"
    )

    /** 跳过按钮的常见文字 */
    private val SKIP_TEXTS = listOf("跳过", "关闭广告", "关闭此广告", "skip")

    /** 广告痕迹：判断"这里确实是广告"的依据 */
    private val AD_MARKERS = listOf(
        "广告", "跳转详情页", "第三方应用", "摇一摇", "转动手机", "运营推广",
        "立即下载", "下载游戏", "点击下载", "点击安装"
    )

    /** 常见「关闭」按钮的 id 后缀（很多广告的 × 没有文字，但有 id） */
    private val CLOSE_ID_SUFFIX = listOf(
        "close", "iv_close", "btn_close", "close_btn", "img_close",
        "ad_close", "dialog_close", "close_ad", "cancel"
    )

    /**
     */
    private val ELDER_DISMISS_TEXTS = listOf(
        "关闭广告", "关闭此广告", "跳过广告", "跳过",
        "残忍拒绝", "以后再说", "不再提示", "不再提醒", "不感兴趣", "我知道了"
    )

    /** 常见跳过按钮的 id 后缀（用 root.packageName 拼全） */
    private val SKIP_ID_SUFFIX = listOf("skip", "skip_btn", "tv_skip", "btn_skip")

    // ==================================================================
    //  扫描
    private fun scan(root: AccessibilityNodeInfo, pkg: String, now: Long) {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        // ★ 关键安全阀："事件来自谁"和"活动窗口是谁"必须一致。
        //   否则会出现最荒唐的情况：事件是 A 发来的，我们却在 B 的界面上找按钮 ——
        val rootPkg = pkgOf(root)
        if (rootPkg == packageName) return
        if (SYSTEM_PACKAGES.contains(rootPkg)) return
        if (!Prefs.inScope(this, rootPkg)) return

        // 真正看到了一个目标 App 的界面
        scanCount++
        val adMarker = findAdMarker(root)
        val level = splashLevel(currentWindowClass)
        val splashLike = level > 0
        val adLike = adMarker != null

        // "整树兜底"只在这个窗口确实像广告时才允许走（避免每次都遍历整棵树）
        walkAllowed = adLike || splashLike
        walkCache = null
        walkCacheRoot = null

        // ★ 找到"像跳过键的控件"却点不动时，要能和"根本没找到"区分开 ——
        //   否则用户/开发者看到的永远是同一句"未找到关闭键"，没法定位。
        var foundButFailed = false

        fun tryAct(node: AccessibilityNodeInfo?, how: String, keyword: String): Boolean {
            if (node == null) return false
            foundButFailed = true
            return act(node, pkg, how, keyword, now)
        }

        // 统计：**只统计"确实识别出广告"的窗口**（有广告水印 / 像启动页 / 找到过疑似跳过键），
        // 信息流里带"广告"字样的卡片不算开屏
        fun countIfSplash() {
            if (adSeenThisWindow) return
            if (!splashLike && !adLike && !foundButFailed) return
            adSeenThisWindow = true
            adSeenCount++
        }

        // ① 右上角文字（任何情况都试）
        if (tryAct(findSkipByText(root, screenW, screenH, ZONE_TOP_RIGHT), "跳过", "跳过")) {
            countIfSplash(); return
        }
        // ② 控件 id
        if (tryAct(findSkipById(root), "跳过", "跳过(id)")) {
            countIfSplash(); return
        }
        // ③ 顶部 22% 条带（微信小程序那种"跳过"在左上角）
        if (adLike || splashLike) {
            if (tryAct(findSkipByText(root, screenW, screenH, ZONE_TOP_BAND), "跳过(顶部)", "跳过")) {
                countIfSplash(); return
            }
        }
        // ③b 顶部条带 + 文字与关键词完全相等（不要求有广告痕迹）
        if (!adLike) {
            if (tryAct(
                    findSkipByText(root, screenW, screenH, ZONE_TOP_BAND, exactOnly = true),
                    "跳过(顶部/精确)", "跳过"
                )
            ) {
                countIfSplash(); return
            }
        }
        // ③c **右下角条带** —— B站这类开屏的「跳过」在屏幕右下角，
        //     之前只放宽到顶部条带，把它们全漏掉了（实测回归）。
        if (adLike || splashLike) {
            if (tryAct(findSkipByText(root, screenW, screenH, ZONE_BOTTOM_BAND), "跳过(右下)", "跳过")) {
                countIfSplash(); return
            }
        }
        // ④ 确认为广告后，找「关闭」控件：先按 id，再按右上角文字
        if (adLike) {
            if (tryAct(findCloseById(root), "关闭(id)", "关闭")) {
                countIfSplash(); return
            }
            if (tryAct(findCloseTopRight(root, screenW, screenH), "关闭", "关闭")) {
                countIfSplash(); return
            }
        }
        // ⑦ ★ 最后一招：OCR 兜底（实验，默认关）。
        //
        val fewNodes = try { root.childCount <= 1 } catch (_: Throwable) { false }
        var ocrTriggered = false
        if (Prefs.ocrFallback(this) && (adLike || splashLike || fewNodes)) {
            ocrTriggered = true
            lastAction = "正在用 OCR 找跳过键" + appSuffix(pkg)
            requestOcrFallback(pkg, now)
        }
        // ⑥ 没点掉：把"为什么"说清楚（只记录，**不做任何坐标猜测**）
        //
        countIfSplash()
        // ★ OCR 已经把"正在用 OCR 找跳过键"写进 lastAction 了，
        //   这里再写一遍会把它覆盖掉 —— 结果状态永远停在"正在用"，
        if (!ocrTriggered) {
            if (foundButFailed) {
                lastAction = "这个广告没能跳过" + appSuffix(pkg)
                Log.i(TAG, "未跳过（找到控件但点击无效）pkg=$pkg 事件=$eventCount 扫描=$scanCount")
            } else if (adLike || splashLike) {
                lastAction = "发现广告，没能自动跳过（OCR 未开启）" + appSuffix(pkg)
                Log.i(
                    TAG,
                    "未跳过（没找到跳过键）pkg=$pkg marker=$adMarker splash=$splashLike " +
                        "事件=$eventCount 扫描=$scanCount"
                )
            }
        }
    }

    /** 一次 IPC：让 App 自己搜包含该关键字的节点 */
    private fun search(root: AccessibilityNodeInfo, keyword: String): List<AccessibilityNodeInfo> =
        try {
            root.findAccessibilityNodeInfosByText(keyword) ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }

    private fun findAdMarker(root: AccessibilityNodeInfo): String? {
        for (k in AD_MARKERS) {
            if (search(root, k).isNotEmpty()) return k
        }
        return null
    }

    /**
     */
    private fun findSkipByText(
        root: AccessibilityNodeInfo,
        screenW: Int,
        screenH: Int,
        zone: Int,
        exactOnly: Boolean = false
    ): AccessibilityNodeInfo? {
        val band = zone != ZONE_TOP_RIGHT
        val maxW = if (band) screenW * 0.55f else screenW * 0.42f
        val maxH = if (band) screenH * 0.14f else screenH * 0.22f
        var fallback: AccessibilityNodeInfo? = null
        for (kw in SKIP_TEXTS) {
            for (node in searchAll(root, kw, SystemClock.uptimeMillis())) {
                if (!looksLikeSkipButtonText(node, kw, exactOnly)) continue
                if (!isUsable(node)) continue                       // ★ 不可见的不点
                val rect = boundsOf(node) ?: continue
                if (rect.width() <= 0 || rect.height() <= 0) continue
                if (rect.width() > maxW || rect.height() > maxH) continue
                when (zone) {
                    ZONE_TOP_RIGHT -> {
                        if (rect.top >= screenH * 0.35f || rect.right <= screenW * 0.55f) continue
                    }
                    ZONE_TOP_BAND -> {
                        if (rect.top >= screenH * 0.22f || rect.bottom >= screenH * 0.35f) continue
                    }
                    else -> {
                        // 右下角：整体位于屏幕下方 1/4、且靠右
                        if (rect.top <= screenH * 0.70f || rect.right <= screenW * 0.55f) continue
                    }
                }
                if (hasClickableAncestor(node)) return node
                if (fallback == null) fallback = node
            }
        }
        return fallback
    }

    /** id 命中（用当前包名拼常见 id） */
    private fun findSkipById(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val pkg = try { root.packageName?.toString() } catch (_: Throwable) { null } ?: return null
        for (suffix in SKIP_ID_SUFFIX) {
            val list = try {
                root.findAccessibilityNodeInfosByViewId("$pkg:id/$suffix")
            } catch (_: Throwable) {
                null
            }
            if (!list.isNullOrEmpty()) {
                val n = list.firstOrNull { isUsable(it) }
                if (n != null) return n
            }
        }
        return null
    }

    /**
     */
    private fun findCloseById(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val pkg = try { root.packageName?.toString() } catch (_: Throwable) { null } ?: return null
        for (suffix in CLOSE_ID_SUFFIX) {
            val list = try {
                root.findAccessibilityNodeInfosByViewId("$pkg:id/$suffix")
            } catch (_: Throwable) {
                null
            }
            for (node in list ?: emptyList()) {
                if (!isUsable(node)) continue
                if (hasClickableAncestor(node)) return node
            }
        }
        return null
    }

    /** 屏幕上有广告痕迹时，找右上角的小"关闭"控件 */
    private fun findCloseTopRight(
        root: AccessibilityNodeInfo,
        screenW: Int,
        screenH: Int
    ): AccessibilityNodeInfo? {
        for (kw in listOf("关闭", "close")) {
            for (node in search(root, kw)) {
                if (!isUsable(node)) continue                       // ★ 不可见的不点
                val rect = boundsOf(node) ?: continue
                if (rect.width() <= 0 || rect.height() <= 0) continue
                if (rect.width() > screenW * 0.42f || rect.height() > screenH * 0.22f) continue
                if (rect.top >= screenH * 0.35f || rect.right <= screenW * 0.55f) continue
                if (textOf(node).length > 6) continue
                if (hasClickableAncestor(node)) return node
            }
        }
        return null
    }

    /** 老年人模式：广告专属关闭词，不限位置 */
    private fun findAnyDismiss(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (kw in ELDER_DISMISS_TEXTS) {
            for (node in search(root, kw)) {
                if (!isUsable(node)) continue
                if (hasClickableAncestor(node)) return node
            }
        }
        return null
    }

    // ==================================================================
    //  判定与动作
    /**
     */
    private fun isUsable(node: AccessibilityNodeInfo): Boolean = try {
        node.isVisibleToUser
    } catch (_: Throwable) {
        false
    }

    private fun boundsOf(node: AccessibilityNodeInfo): Rect? {
        val rect = Rect()
        return try {
            node.getBoundsInScreen(rect)
            rect
        } catch (_: Throwable) {
            null
        }
    }

    private fun textOf(node: AccessibilityNodeInfo): String {
        val t = try { node.text?.toString() ?: "" } catch (_: Throwable) { "" }
        val d = try { node.contentDescription?.toString() ?: "" } catch (_: Throwable) { "" }
        return (t + d).trim()
    }

    private fun hasClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        // 深度放到 10：有些广告 SDK 的可点击容器套得很深，5 层会够不着
        while (cur != null && depth < 10) {
            val clickable = try { cur.isClickable } catch (_: Throwable) { false }
            if (clickable) return true
            cur = try { cur.parent } catch (_: Throwable) { null }
            depth++
        }
        return false
    }

    /**
     */
    private fun looksLikeSplashWindow(cls: String): Boolean = splashLevel(cls) > 0

    /** 2 = 强启动页；1 = 弱（要先确认有广告）；0 = 不是 */
    private fun splashLevel(cls: String): Int {
        if (cls.isEmpty()) return 0
        val c = cls.lowercase()
        // 主界面 / 启动器：右上角通常是功能按钮，绝对不能碰
        if (c.contains("launcher") || c.contains("launchui") ||
            c.contains("mainactivity") || c.contains("mainui") ||
            c.contains("homeactivity") || c.contains("homeui") ||
            c.contains("activity.main")
        ) return 0
        if (c.contains("splash") || c.contains("adsplash") ||
            c.contains("adactivity") || c.contains("advert") ||
            c.contains("startpage")
        ) return 2
        if (c.contains("welcome") || c.contains("guide") || c.contains("logo")) return 1
        return 0
    }

    /** 执行一次点击：**先点节点自己的中心**，再退回给它所在的可点击容器 */
    private fun act(
        node: AccessibilityNodeInfo,
        pkg: String,
        how: String,
        keyword: String,
        now: Long
    ): Boolean {
        // ★ 顺序很重要，实测踩过：
        //   广告的「跳过」经常是自绘/自定义控件，给它所在的容器发 ACTION_CLICK
        val ok = tapCenter(node) || clickNode(node)
        if (!ok) return false
        // 界面上只说人话；命中的具体规则写进 logcat，供排查用
        Log.i(TAG, "已跳过：rule=$how keyword=${safeTag(keyword, ALLOWED_WORDS)} pkg=$pkg")
        onTapped(pkg, now, "已跳过广告" + appSuffix(pkg))
        return true
    }

    /** " · QQ音乐" 这样的后缀（认不出来的 App 就不加，别把包名糊到用户脸上） */
    private fun appSuffix(pkg: String): String {
        val name = FRIENDLY_PACKAGES[pkg] ?: return ""
        return " · $name"
    }

    /**
     */
    private fun looksLikeSkipButtonText(
        node: AccessibilityNodeInfo,
        keyword: String,
        exactOnly: Boolean
    ): Boolean {
        val t = textOf(node).trim()
        if (t.isEmpty() || t.length > 8) return false
        // ★ 播放器里的「跳过片头 / 跳过片尾 / 跳过下一集」是用户自己想点的功能按钮，
        //   不是广告跳过键。点了会把正常播放位置跳掉（B 站暂停页被误判就是这一类）。
        if (PLAYER_SKIP_WORDS.any { t.contains(it) }) return false
        if (exactOnly) return t.equals(keyword, ignoreCase = true)
        return t.contains(keyword, ignoreCase = true)
    }

    /** 这些词出现在「跳过」按钮里说明它是播放器功能，不是广告 */
    private val PLAYER_SKIP_WORDS = listOf("片头", "片尾", "下一集", "下一期", "正片", "预告", "弹幕")

    /** 其它窗口的根节点（带 500ms 缓存，避免每次扫描都去问一遍系统） */
    private var cachedOtherRoots: List<AccessibilityNodeInfo> = emptyList()
    private var cachedOtherRootsAt = 0L

    /** 「整树兜底」用：本轮扫描是否允许走兜底 / 兜底结果缓存 */
    private var walkAllowed = false
    private var walkCache: List<Pair<AccessibilityNodeInfo, String>>? = null
    private var walkCacheRoot: AccessibilityNodeInfo? = null
    private val WALK_LIMIT = 200

    private fun otherRoots(now: Long): List<AccessibilityNodeInfo> {
        if (now - cachedOtherRootsAt < 500L) return cachedOtherRoots
        cachedOtherRootsAt = now
        cachedOtherRoots = try {
            (windows ?: emptyList()).mapNotNull { it?.root }
        } catch (_: Throwable) {
            emptyList()
        }
        return cachedOtherRoots
    }

    /**
     */
    private fun searchAll(
        root: AccessibilityNodeInfo,
        keyword: String,
        now: Long
    ): List<AccessibilityNodeInfo> {
        val primary = search(root, keyword)
        if (primary.isNotEmpty()) return primary
        for (r in otherRoots(now)) {
            if (r == root) continue
            val hit = search(r, keyword)
            if (hit.isNotEmpty()) return hit
        }
        if (!walkAllowed) return emptyList()
        return ensureWalk(root)
            .filter { it.second.isNotEmpty() && it.second.contains(keyword, ignoreCase = true) }
            .map { it.first }
    }

    /** 整树兜底：把活动窗口里的 (节点, 文字+描述) 收集一遍，每轮扫描只做一次 */
    private fun ensureWalk(root: AccessibilityNodeInfo): List<Pair<AccessibilityNodeInfo, String>> {
        val cached = walkCache
        if (cached != null && walkCacheRoot === root) return cached
        val out = ArrayList<Pair<AccessibilityNodeInfo, String>>(64)
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < WALK_LIMIT) {
            val node = queue.pollFirst() ?: break
            visited++
            // ★ 连"没有文字"的节点也留下 —— 有些广告的跳过键是纯图形，
            //   没有文字也没有 id，只能靠"它在右上角那块固定区域 + 可点击"来认。
            out.add(node to textOf(node))
            val cc = try { node.childCount } catch (_: Throwable) { 0 }
            for (i in 0 until cc) {
                try {
                    node.getChild(i)?.let { queue.add(it) }
                } catch (_: Throwable) {
                }
            }
        }
        walkCache = out
        walkCacheRoot = root
        return out
    }

    // ==================================================================
    //  OCR 兜底
    /**
     */
    private fun requestOcrFallback(pkg: String, now: Long) {
        if (Build.VERSION.SDK_INT < 30) return          // takeScreenshot 要 Android 11+
        // 系统明确拒绝能力后不再反复尝试（避免每次扫描都白跑一遍）
        if (ocrUnavailableReason != null) return
        if (ocrBusy) return
        // 配额重置：换 App、或距本轮起点已超过 8 秒。
        // 原来只在"距上次截图超过 3 秒"时才重置，可定时扫描每 600ms 就扫一次，
        // 于是同一 App 只截得 2 张，之后再出现广告也不会再截图 —— 就是"OCR 只在开屏时截图"。
        if (pkg != ocrPkg || now - ocrQuotaStartAt > OCR_QUOTA_WINDOW_MS) {
            ocrPkg = pkg
            ocrQuotaStartAt = now
            ocrShotsInWindow = 0
        }
        if (ocrShotsInWindow >= MAX_OCR_SHOTS) return
        if (now - lastOcrAt < OCR_MIN_GAP_MS) return

        // ★ 模型初始化放到后台线程：10.9MB 模型 + 建 session 放在无障碍回调/主线程会卡顿
        if (!TextRec.ready) {
            if (ocrInitializing) {
                lastAction = "OCR 模型首次加载中…"
                return
            }
            ocrInitializing = true
            lastAction = "OCR 模型首次加载中…"
            ocrPool.execute {
                val ok = TextRec.init(this)
                ocrInitializing = false
                if (!ok) {
                    ocrUnavailableReason = "模型加载失败"
                    lastAction = "OCR 模型加载失败"
                }
                Log.i(TAG, "OCR 模型后台初始化完成：ok=$ok 耗时=${TextRec.loadMs}ms")
            }
            return
        }

        ocrBusy = true
        ocrShotsInWindow++
        lastOcrAt = now
        val seq = ++ocrSeq
        val watchdog = Runnable {
            if (seq == ocrSeq && ocrBusy) {
                ocrBusy = false
                lastAction = "OCR 超时没返回（已放弃）"
                Log.w(TAG, "OCR 兜底：看门狗触发（seq=$seq），强制复位")
            }
        }
        pendingWatchdog = watchdog
        uiHandler.postDelayed(watchdog, OCR_WATCHDOG_MS)
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, ocrPool, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    handleOcrShot(result, pkg, seq)
                }

                override fun onFailure(errorCode: Int) {
                    ocrBusy = false
                    if (errorCode == 3) {
                        // code=3 = 系统限流（两次截图间隔太短）。不算失败，1.2 秒后重试一次。
                        lastAction = "OCR 被系统限流，稍后重试"
                        Log.i(TAG, "OCR 兜底：被限流（code=3），1.2 秒后重试")
                        uiHandler.postDelayed({
                            if (Prefs.ocrFallback(this@SkipAdAccessibilityService)) {
                                lastOcrAt = 0L
                                requestOcrFallback(pkg, SystemClock.uptimeMillis())
                            }
                        }, 1_200L)
                    } else {
                        lastAction = "OCR 截图失败（code=$errorCode）"
                        // 不是限流 = 能力不可用（缺少 canTakeScreenshot / 系统拒绝）
                        ocrUnavailableReason = "系统拒绝截图（code=$errorCode）"
                        lastAction = "OCR 不可用：系统拒绝截图（code=$errorCode）"
                        Log.w(TAG, "OCR 能力不可用，已关闭 OCR 兜底（code=$errorCode）")
                        Log.i(TAG, "OCR 兜底：截图失败 code=$errorCode")
                    }
                }
            })
        } catch (t: Throwable) {
            ocrBusy = false
            Log.w(TAG, "OCR 兜底：调用截图异常 ${t.message}")
        }
    }

    private fun handleOcrShot(result: ScreenshotResult, pkg: String, seq: Int) {
        // 只处理当前这一次请求；窗口代数变了也直接丢弃
        if (seq != ocrSeq) return
        val epoch = ocrEpoch
        val t0 = SystemClock.uptimeMillis()
        var bmp: Bitmap? = null
        var hb: android.hardware.HardwareBuffer? = null
        val crops = ArrayList<Bitmap>()
        try {
            hb = result.hardwareBuffer
            bmp = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                ?.copy(Bitmap.Config.ARGB_8888, false)
            val src = bmp ?: run {
                lastAction = "OCR 截图位图为空"
                return
            }

            val cands = PillFind.find(src)
            if (cands.isEmpty()) {
                lastAction = "OCR 没找到候选块"
                Log.i(TAG, "OCR 兜底：没有候选块")
                return
            }

            val boxes = ArrayList<PillFind.Cand>()
            for (c in cands.take(MAX_OCR_CANDS)) {
                val x0 = c.x0.coerceAtLeast(0)
                val y0 = c.y0.coerceAtLeast(0)
                val x1 = c.x1.coerceAtMost(src.width)
                val y1 = c.y1.coerceAtMost(src.height)
                if (x1 - x0 < 10 || y1 - y0 < 8) continue
                crops.add(Bitmap.createBitmap(src, x0, y0, x1 - x0, y1 - y0))
                boxes.add(c)
            }
            var target: PillFind.Cand? = null
            for (i in crops.indices) {
                val r = TextRec.recognize(crops[i])
                val txt = r?.first ?: continue
                if (r.second < 0.45f) continue
                if (txt.contains("跳过") || txt.contains("关闭广告") || txt.contains("免广告")) {
                    target = boxes[i]
                    break
                }
            }
            val ms = SystemClock.uptimeMillis() - t0
            if (target == null) {
                lastAction = "OCR 看了 ${crops.size} 块，没认出跳过键（${ms}ms）"
                Log.i(TAG, "OCR 兜底：${crops.size} 块都没读到跳过（${ms}ms）")
                return
            }
            val cx = target.cx.toFloat()
            val cy = target.cy.toFloat()
            Log.i(TAG, "OCR 兜底命中：$target → ($cx,$cy) 耗时 ${ms}ms")

            // ★ 点击前再核验一次：请求没过期、窗口没换、开关还开着、服务还在
            uiHandler.post {
                val stillValid = seq == ocrSeq &&
                    epoch == ocrEpoch &&
                    pkg == ocrPkg &&
                    Prefs.ocrFallback(this) &&
                    Prefs.autoSkip(this) &&
                    running
                if (!stillValid) {
                    Log.i(TAG, "OCR 结果已过期（窗口/开关变化），丢弃这次点击")
                    lastAction = "OCR 结果已过期，已放弃"
                    return@post
                }
                val tapped = tapAt(cx, cy)
                if (tapped) {
                    onTapped(pkg, SystemClock.uptimeMillis(), "跳过(OCR)")
                } else {
                    Log.w(TAG, "OCR 兜底：点击注入失败")
                }
            }
        } catch (t: Throwable) {
            lastAction = "OCR 异常：${t.javaClass.simpleName}"
            Log.w(TAG, "OCR 兜底异常：${t.message}")
        } finally {
            // 统一释放：HardwareBuffer、裁剪图、原图、看门狗、忙碌标记
            try {
                hb?.close()
            } catch (_: Throwable) {
            }
            for (b in crops) {
                try {
                    b.recycle()
                } catch (_: Throwable) {
                }
            }
            try {
                bmp?.recycle()
            } catch (_: Throwable) {
            }
            pendingWatchdog?.let { uiHandler.removeCallbacks(it) }
            pendingWatchdog = null
            ocrBusy = false
        }
    }
    private fun tapAt(x: Float, y: Float): Boolean {
        return try {
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 55))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (t: Throwable) {
            Log.w(TAG, "注入点击失败：${t.message}")
            false
        }
    }
    /** 每次真的点掉一个广告，弹一条很短的小提示（可在设置里关掉） */
    private fun showSkipToast(pkg: String) {
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastToastAt < 700L) return      // 连着点多个广告时别刷屏
            lastToastAt = now
            val label = try {
                val pm = packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Throwable) {
                pkg
            }
            Toast.makeText(this, "已跳过 · $label", Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }

    private fun onTapped(pkg: String, now: Long, label: String) {
        clickCount++
        if (!countedThisWindow) {
            countedThisWindow = true
            skipCount++
        }
        actionsThisWindow++
        globalCooldownUntil = now + GLOBAL_COOLDOWN_MS
        suppressUntil = now + SUPPRESS_AFTER_CLICK_MS
        lastAction = label
        if (Prefs.skipToast(this)) showSkipToast(pkg)
        showOngoingNotification()
        Log.i(TAG, "$label（跳过 $skipCount 个 / 点击 $clickCount 次）")
    }

    /**
     */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        val nodeRect = boundsOf(node) ?: return false
        val nodeArea = nodeRect.width().toLong() * nodeRect.height().toLong()
        if (nodeArea <= 0) return false
        val screenArea = resources.displayMetrics.let {
            it.widthPixels.toLong() * it.heightPixels.toLong()
        }

        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            val clickable = try { current.isClickable } catch (_: Throwable) { false }
            if (clickable) {
                val r = boundsOf(current)
                val acceptable = r != null &&
                    r.contains(nodeRect) &&
                    r.width().toLong() * r.height().toLong() <= nodeArea * 6 &&
                    r.width().toLong() * r.height().toLong() <= screenArea / 4
                if (acceptable) {
                    val ok = try {
                        current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } catch (_: Throwable) {
                        false
                    }
                    if (ok) return true
                }
            }
            current = try { current.parent } catch (_: Throwable) { null }
            depth++
        }
        return false
    }

    private fun tapCenter(node: AccessibilityNodeInfo): Boolean {
        val rect = boundsOf(node) ?: return false
        if (rect.width() <= 0 || rect.height() <= 0) return false
        return gestureTap(rect.exactCenterX(), rect.exactCenterY())
    }

    /**
     */
    private fun gestureTap(x: Float, y: Float): Boolean {
        return try {
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 55))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (t: Throwable) {
            Log.w(TAG, "模拟点按失败：${t.message}")
            false
        }
    }

    // ==================================================================
    // ==================================================================
    private fun showOngoingNotification() {
        try {
            if (!Prefs.autoSkip(this) || !running) {
                cancelOngoing()
                return
            }
            // ★ 和 DNS 通知合并：VPN 在跑时，状态统一由 DNS 那条显示（含拦截数/跳过数/无障碍状态）
            if (AdBlockVpnService.running) {
                cancelOngoing()
                return
            }
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ONGOING) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ONGOING,
                        "无障碍跳过状态",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        setShowBadge(false)
                        description = "显示「无障碍跳过」正在运行"
                    }
                )
            }
            val open = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(this, CHANNEL_ONGOING)
                .setContentTitle("无障碍跳过：运行中")
                .setContentText("已跳过 $skipCount 次 · 只关闭广告弹窗，不读界面文字")
                .setSmallIcon(R.drawable.ic_notify)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(open)
                .build()
            nm.notify(NOTIF_ONGOING_ID, n)
        } catch (_: Throwable) {
        }
    }

    private fun cancelOngoing() {
        try {
            getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ONGOING_ID)
        } catch (_: Throwable) {
        }
    }

    private fun notifyAccessibilityLost() {
        if (!Prefs.autoSkip(this)) return
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ALERT) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ALERT,
                    "辅助功能失效提醒",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                ch.description = "无障碍服务被系统关闭时，提醒你重新启用"
                nm.createNotificationChannel(ch)
            }
            val pi = PendingIntent.getActivity(
                this,
                2,
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = Notification.Builder(this, CHANNEL_ALERT)
                .setContentTitle("disAD 的无障碍辅助已被系统关闭")
                .setContentText("点这里重新启用（小米/红米常见，建议同时加入电池优化白名单）")
                .setSmallIcon(R.drawable.ic_notify)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            nm.notify(NOTIF_ALERT_ID, notification)
        } catch (_: Throwable) {
        }
    }
}
