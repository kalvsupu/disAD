package com.baidukiller.lite

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
import com.baidukiller.lite.vpn.AdBlockVpnService

/**
 * ============================================================================
 *  无障碍辅助（DNS 拦截的兜底）
 * ============================================================================
 *
 *  【定位：辅助，不是主力】
 *  域名拦截负责把广告从源头掐掉；无障碍只负责收尾，所以要求"快、准、少动"。
 *
 *  【识别规则（全部基于节点，绝不猜屏幕坐标）】
 *   ① 文字「跳过 / 关闭广告 / skip」出现在**右上角** → 直接点（任何情况都允许）
 *   ② 控件 id：用 root.packageName 拼 skip / skip_btn / tv_skip / btn_skip
 *   ③ 同样的文字出现在**屏幕顶部 22% 条带内** → 只有在下面任一条件成立时才点：
 *        · 屏幕上出现广告痕迹（广告 / 跳转详情页 / 摇一摇 / 转动手机 / 运营推广）
 *        · 当前窗口看起来像启动页
 *      这样既能覆盖"跳过键在左上角"（微信小程序类），
 *      又不会去点正文、聊天记录里偶然带"跳过"字样的内容。
 *   ④ 确认是广告后，右上角的小「关闭」控件 / 常见 close id
 *   ⑤ 老年人模式：**必须先确认屏幕上有广告痕迹**，才按广告专属词（关闭广告 / 跳过 /
 *      残忍拒绝 / 以后再说 / 不再提示）找关闭键。不含"取消 / 关闭"这类
 *      所有正常对话框都有的通用词，所以不会误关你正在写的笔记。
 *
 *  ⚠ 这里**没有**"点屏幕某个坐标试试"的兜底逻辑。曾经有过，实测代价是
 *    会点中 App 主界面的「+」等功能按钮 —— 猜错的代价远大于漏掉的代价。
 *
 *  【为什么不遍历整棵树】
 *  逐节点取 text / contentDescription / bounds / viewId 每个都是一次跨进程调用，
 *  1500 个节点 ≈ 7000 次 IPC，会把服务主线程和目标 App 一起拖死。
 *  现在用 findAccessibilityNodeInfosByText / ByViewId —— 一次调用让 App 自己去搜，
 *  单次扫描的 IPC 次数从 7000+ 降到 20 以内。
 *
 *  【速度】
 *  · 窗口一出现**立刻**扫一次，不用等定时器；
 *  · 前 3.5 秒每 250ms 扫一次（广告就在这几秒里），之后才指数退避到 1.5 秒省电；
 *  · 点击后静默 1.2 秒、跨窗口冷却 1 秒 —— 足够防连点，又不会让你觉得"它没反应"。
 *
 *  【计数】
 *  同一个窗口只算 1 个"跳过广告数"（内部另计点击次数）。
 * ============================================================================
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
         * 排查用的内部计数：收到过多少个界面事件、真正扫描过多少轮。
         * **只写进 logcat，不在界面上显示** —— 用户没必要知道这些。
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
         * 慢速持续扫描的间隔（定时器兜底）。
         *
         * ★ 为什么不能扫几秒就收工：小程序、网页里的广告经常是**页面加载好几秒之后**
         *   才渲染出来的，而我们原来只在窗口刚出现的几秒里高频扫，之后就停了。
         *   现在改成：窗口属于目标 App 期间一直慢扫，直到切走或锁屏。
         */
        private const val SLOW_INTERVAL_MS = 800L

        /**
         * App 主动发界面变化事件时，两次扫描之间至少间隔这么久。
         *
         * 广告出现的那一刻，App 几乎一定会发事件 —— 事件驱动是**最快**的路径，
         * 所以这里只留一个很短的防抖下限（250ms），而不是等慢扫的 800ms。
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

        // ==================================================================
        //  ★ 隐私红线：界面上的文字绝不外泄
        // ==================================================================
        //  下面两个白名单是硬性防线，不是注释约定：
        //  任何要写进 lastAction / 日志 / 通知栏的字符串，都必须先过 safeTag()，
        //  不在白名单里的内容一律被替换成「规则」。
        //
        //  为什么需要它：无障碍能看到界面上的文字，一旦某个环节手滑把
        //  「被点击控件的文字」传进来，用户的笔记标题、聊天内容就会出现在
        //  状态卡片、通知栏（锁屏也能看到）和系统日志里。
        //  这个白名单保证：**即使以后代码写错，也不可能把用户内容带出去。**
        private val ALLOWED_RULES = setOf(
            "跳过", "跳过(id)", "跳过(顶部)", "跳过(顶部/精确)", "跳过(右下)", "跳过(角落图形)",
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
        lastAction = "服务已连接"
        Log.i(TAG, "无障碍辅助已连接")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        running = false
        // ★ 这里不把 instance 置空：系统偶尔会"解绑又立刻重绑"，
        //   置空会让调试导出误报"无障碍服务未连接"，也会让状态卡片误判。
        //   真正销毁时（onDestroy）才置空。
        lastAction = "服务已断开"
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
        //   （用户只能跑到系统设置里关掉无障碍，体验很差）。
        if (!AdBlockVpnService.running) {
            logGate(event, "拦截没有在运行")
            if (burstPkg.isNotEmpty()) stopBurst()
            return
        }

        val pkg = event.packageName?.toString() ?: return
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
     * 扫描一次。
     * @param force true = 定时器触发（目标 App 在前台期间一直慢扫），
     *              不受"扫描间隔 / 窗口活跃期"限制，但冷却与次数上限仍然生效
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
        if (Prefs.elderMode(this)) ELDER_ACTIVE_WINDOW_MS else ACTIVE_WINDOW_MS

    /** 作用范围与 DNS 拦截保持一致（三个系别 + 老年人模式全局） */
    private fun inScope(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        if (pkg == packageName) return false
        if (SYSTEM_PACKAGES.contains(pkg)) return false
        return Prefs.inScope(this, pkg)
    }

    /**
     * 完全不碰的包。
     *
     * ★ 后半段是"内容 / 编辑类" App：这些界面里的「跳过」「广告」字样
     *   往往是**用户自己的内容**，不是广告按钮。
     *   系统笔记那次误点（自动点开了一篇笔记）就是因为没排除它们。
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
     * 老年人模式才用的"广告专属"关闭词。
     * 注意：**不含"取消"** —— 那是所有正常对话框都有的词。
     * 而且这些词只有在"屏幕上确实有广告痕迹"时才会被使用。
     */
    private val ELDER_DISMISS_TEXTS = listOf(
        "关闭广告", "关闭此广告", "跳过广告", "跳过",
        "残忍拒绝", "以后再说", "不再提示", "不再提醒", "不感兴趣", "我知道了"
    )

    /** 常见跳过按钮的 id 后缀（用 root.packageName 拼全） */
    private val SKIP_ID_SUFFIX = listOf("skip", "skip_btn", "tv_skip", "btn_skip")

    // ==================================================================
    //  扫描
    // ==================================================================
    private fun scan(root: AccessibilityNodeInfo, pkg: String, now: Long) {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val elder = Prefs.elderMode(this)

        // ★ 关键安全阀："事件来自谁"和"活动窗口是谁"必须一致。
        //   否则会出现最荒唐的情况：事件是 A 发来的，我们却在 B 的界面上找按钮 ——
        //   实测就发生过"事件来自负一屏，活动窗口其实是 disAD 自己"，
        //   于是把 disAD 使用说明里的"跳过 / 广告"字样当成了广告痕迹。
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
        // ⑤ 老年人模式：确认是广告后，才按广告专属词找关闭键
        if (elder && adLike) {
            if (tryAct(findAnyDismiss(root), "关弹窗", "关闭")) {
                countIfSplash(); return
            }
        }
        // ⑦ ★ 最后一招：已确认是广告、前面全都没找到 → 点"角落里那块区域中的可点击控件"。
        //    专治"跳过键是纯图形（没有文字、没有 id）"的广告；右上角、右下角都看。
        if (adLike) {
            if (tryAct(findCornerButton(root, screenW, screenH), "跳过(角落图形)", "跳过")) {
                countIfSplash(); return
            }
        }
        // ⑥ 没点掉：把"为什么"说清楚（只记录，**不做任何坐标猜测**）
        //
        //    ★ 这里原来有一段"点屏幕右上角"的坐标兜底，已经删掉。
        //      实测代价：QQ音乐 7 次里有 1 次被它点中了主界面的「+」按钮。
        //      而它想兜住的那些广告（跳过键是纯图片、节点里没有任何文字/id），
        //      本来就无法与正常控件区分 —— 猜错的代价远大于漏掉的代价。
        countIfSplash()
        if (foundButFailed) {
            lastAction = "这个广告没能跳过" + appSuffix(pkg)
            Log.i(TAG, "未跳过（找到控件但点击无效）pkg=$pkg 事件=$eventCount 扫描=$scanCount")
        } else if (adLike || splashLike) {
            lastAction = "发现广告，没能自动跳过" + appSuffix(pkg)
            Log.i(
                TAG,
                "未跳过（没找到跳过键）pkg=$pkg marker=$adMarker splash=$splashLike " +
                    "事件=$eventCount 扫描=$scanCount"
            )
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
     * 文字命中。
     * @param zone ZONE_TOP_RIGHT / ZONE_TOP_BAND / ZONE_BOTTOM_BAND
     * @param exactOnly 文字必须与关键词完全相等
     *
     * ★ 两道安全阀（系统笔记那次误点之后加的）：
     *   1. 文字必须**短**（≤ 8 字）—— 真按钮就写"跳过"，内容正文不可能这么短；
     *   2. 只允许出现在明确的两个条带里（顶部 / 右下角），不是"整屏任意位置"。
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
     * 关闭按钮的 id 命中。
     * 很多广告的 × 是一张没有文字、没有描述的图片，只能靠 id 认出来 ——
     * 这也是"居中弹窗右上角那个 × 点不到"的正解（用节点，不猜坐标）。
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
    // ==================================================================
    /**
     * 节点是否"真的在屏幕上"。
     *
     * ★ 这条检查是必需的安全阀：不可见（vis=0）的节点，平台返回的坐标是**残缺的**
     *   （实测见过 bottom=0、right=-54 这种），拿它去 tapCenter 会点到莫名其妙的位置。
     *   所以：不可见的节点一律不点。
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
     * 是不是"启动页"窗口。
     *
     * ★ 这里踩过一个坑：老写法只要类名含 "launch" 就算启动页，
     *   而微信主界面叫 `com.tencent.mm.ui.LauncherUI` —— 于是微信一打开就被
     *   判定成启动页（当时还有坐标兜底，正好点中微信的「+」号）。
     *   所以现在：**先排除 Launcher / Main 这类主界面，再分弱级**；
     *   而"启动页"现在只用来放宽文字匹配的位置条件，不再用来猜坐标。
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
        //   可能被忽略；更糟的是**会一路往上点到别的处理器**——
        //   QQ音乐那种广告的最外层全屏容器也是 clickable，一路往上就会点到它。
        //   所以文字命中的按钮，一律先按它自己的坐标点一下（那是手指会落的位置）。
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
     * 这个节点的文字**像不像一个"跳过"按钮**。
     *
     * 为什么需要这条检查：只判断"节点里含不含'跳过'"，会把**内容文字**误判成按钮 ——
     * 曾经因此在系统笔记里把一条标题含"跳过"的笔记当成广告按钮点开。
     * 真正的跳过按钮文字一定很短（"跳过" / "跳过 3" / "跳过广告" / "skip"），
     * 所以这里要求：**文字长度 ≤ 8 个字，且包含关键词**。
     */
    private fun looksLikeSkipButtonText(
        node: AccessibilityNodeInfo,
        keyword: String,
        exactOnly: Boolean
    ): Boolean {
        val t = textOf(node).trim()
        if (t.isEmpty() || t.length > 8) return false
        if (exactOnly) return t.equals(keyword, ignoreCase = true)
        return t.contains(keyword, ignoreCase = true)
    }

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
     * 在所有窗口里搜：先搜活动窗口，没有命中再遍历其它窗口。
     *
     * 为什么需要：有些广告是被放在**独立窗口**里的（悬浮窗、独立 Activity 覆盖层），
     * `rootInActiveWindow` 拿不到它，于是"看得见、点不到"。
     *
     * ★★ 2026-10-01 补的兜底（这是"看得见点不到"的真正元凶）：
     *    `findAccessibilityNodeInfosByText` 在部分系统 / 部分节点上**只匹配 text，
     *    不匹配 contentDescription** —— 而自绘广告的「跳过」经常只有描述、没有 text。
     *    实测证据：维护工具走一遍整棵树能看到
     *        ViewGroup | [938,108][1008,177] | click=0 | vis=1 | len=4 | 跳过
     *    但我们用 API 搜就是搜不到，于是状态卡片一直写"没找到跳过键"。
     *    所以：快搜一无所获、且这个窗口确实像广告时，**整棵树走一遍**（限量 200 个节点），
     *    拿 text+描述 合起来匹配。只在需要时走，不会每次都遍历。
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

    /**
     * ★ 最后一招（只在**已经确认是广告**时才用）：找"角落那块固定区域里的可点击小控件"。
     *
     * 为什么需要它：有些广告的「跳过」是**纯图形** —— 节点里既没有文字也没有 id
     * （维护工具抓到的那次是 `ViewGroup click=0 len=4 跳过`，但另一个版本连文字都没有）。
     * 纯靠文字/id 永远找不到，而那块区域里确实存在一个可点击控件。
     *
     * ★ 要同时看**两个角**：跳过键的位置各家用得不一样 ——
     *   · QQ音乐 / 多数 SDK：**右上角**（实测 [938,108][1008,177]）
     *   · B站：**右下角**（截图里「跳过 2」在 x≈87%、y≈91%）
     *   老版本就是因为只放宽了顶部条带，把 B站 整条漏掉过。
     *
     * 为什么这不是"猜屏幕坐标"：
     *   · 必须先确认屏幕上**确实有广告水印**（adLike）；
     *   · 点的是**真实存在的可点击控件**的几何中心，不是硬编码的坐标；
     *   · 区域被收得很紧（见下面四个框），且控件本身不超过 260×260。
     *
     * 为什么以前删掉了坐标兜底、现在又加回来：老写法是"只要窗口像启动页就点右上角"，
     * 于是在 QQ音乐**主界面**上点中了「+」号。现在多了"必须已确认是广告"这道门，
     * 主界面根本进不来。
     */
    private fun findCornerButton(
        root: AccessibilityNodeInfo,
        screenW: Int,
        screenH: Int
    ): AccessibilityNodeInfo? {
        if (!walkAllowed) return null
        // 每个框：中心 x 的下限/上限、中心 y 的下限/上限（屏幕比例）
        val bands = listOf(
            floatArrayOf(0.80f, 0.98f, 0.03f, 0.09f),   // 右上角
            floatArrayOf(0.70f, 0.99f, 0.84f, 0.96f)    // 右下角（B站那种）
        )
        for ((node, _) in ensureWalk(root)) {
            val clickable = try { node.isClickable } catch (_: Throwable) { false }
            if (!clickable) continue
            if (!isUsable(node)) continue
            val r = boundsOf(node) ?: continue
            if (r.width() <= 0 || r.height() <= 0) continue
            if (r.width() > 260 || r.height() > 260) continue
            val cx = r.exactCenterX()
            val cy = r.exactCenterY()
            for (b in bands) {
                if (cx >= screenW * b[0] && cx <= screenW * b[1] &&
                    cy >= screenH * b[2] && cy <= screenH * b[3]
                ) {
                    return node
                }
            }
        }
        return null
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
        Log.i(TAG, "$label（跳过 $skipCount 个 / 点击 $clickCount 次）")
    }

    /**
     * 给"按钮自己或它紧挨着的容器"发 ACTION_CLICK。
     *
     * ★ 只认**包住自己、且没有大得离谱**的容器。
     *   实测教训（QQ音乐那种广告）：
     *     跳过按钮 [938,108][1008,177]（本身不可点击）
     *       └ 父容器 [909,108][1037,177]（可点击，128×69）  ← 这才是该点的
     *          └ …一路往上…
     *             └ 全屏容器 [0,0][1080,2400]（也可点击）    ← 点它等于点了整个页面
     *   不加限制地往上找，就可能点到全屏容器上（那是"点广告本体"的入口）。
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
     * 模拟一次点按。
     *
     * 只在**已经找到一个写着「跳过 / 关闭」的节点**、但它所在的容器不可点击时使用，
     * 点的是那个节点自己的中心 —— 不做任何"屏幕坐标猜测"。
     *
     * 时长 55ms：太短（<30ms）在部分 ROM 上会被识别成异常触摸，表现为按钮"按住不放"；
     * 太长则可能被广告识别成长按。
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
