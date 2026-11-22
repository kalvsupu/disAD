package com.kalvsupu.disad

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.kalvsupu.disad.dns.BlockList
import com.kalvsupu.disad.vpn.AdBlockVpnService

/**
 */
class MainActivity : Activity() {

    private companion object {
        const val REQ_VPN = 1001
        const val REQ_NOTIF = 1002
        const val REQ_SAVE_LOG = 1003

        /** 自动学习域名导出：至少这么多个才值得发给作者共建规则库 */
        const val EXPORT_MIN = 20

        /** 作者邮箱：接收自动学习域名，用于完善公共规则库 */
        const val EXPORT_MAIL = "3837929531@qq.com"
    }

    // ---------------- 配色 ----------------
    /** 深色模式（onCreate 里按偏好设定；切换时 recreate） */
    private var dark = false

    /** 程序化改 Switch 时抑制回调，避免两个互斥开关互相触发 */
    private var syncingSwitches = false

    private val cPrimary get() = Color.parseColor(if (dark) "#5B9BFF" else "#1E63D8")
    private val cDanger get() = Color.parseColor(if (dark) "#FF6B6B" else "#D9363E")
    private val cOk get() = Color.parseColor(if (dark) "#3DDC84" else "#0F9D58")
    private val cText get() = Color.parseColor(if (dark) "#E6E8EB" else "#1F2329")
    private val cSubText get() = Color.parseColor(if (dark) "#9BA1AC" else "#6B7280")
    private val cCard get() = Color.parseColor(if (dark) "#1E2126" else "#FFFFFF")
    private val cBg get() = Color.parseColor(if (dark) "#101215" else "#F2F4F7")
    private val cLine get() = Color.parseColor(if (dark) "#1FFFFFFF" else "#14000000")
    private val cSoft get() = Color.parseColor(if (dark) "#1B2A44" else "#EDF2FC")

    /**
     */
    private fun adaptColor(hex: String): String {
        if (!dark) return hex
        return when (hex.uppercase()) {
            "#1F2329" -> "#E6E8EB"
            "#6B7280" -> "#9BA1AC"
            "#DC2626" -> "#FF6B6B"
            "#1E63D8" -> "#5B9BFF"
            "#0F9D58" -> "#3DDC84"
            "#F2F4F7" -> "#101215"
            "#B45309" -> "#FFB86B"
            "#8A5A17" -> "#E4C79B"
            "#9AA1AC" -> "#9BA1AC"
            else -> hex
        }
    }

    private lateinit var mainButton: Button
    private lateinit var statusTitleView: TextView
    private lateinit var statBlockedView: TextView
    private lateinit var statSkippedView: TextView
    private lateinit var statusDetailView: TextView
    private lateinit var accHintView: TextView
    private lateinit var crashView: TextView
    private lateinit var a11yStatusView: TextView
    private lateinit var a11yCountView: TextView
    private lateinit var a11yBigButton: Button
    private lateinit var a11yActionView: TextView
    private lateinit var recentView: TextView
    private lateinit var footerView: TextView

    private lateinit var scopeBaiduSwitch: Switch
    private lateinit var scopeOtherSwitch: Switch
    private lateinit var scopePddSwitch: Switch
    private lateinit var skipToastSwitch: Switch
    private lateinit var ocrSwitch: Switch
    private lateinit var bootSwitch: Switch

    private lateinit var upstreamEdit: EditText
    private lateinit var upstream2Edit: EditText
    private lateinit var customEdit: EditText
    private lateinit var whiteEdit: EditText
    private lateinit var extraPkgEdit: EditText
    private lateinit var testEdit: EditText
    private lateinit var bgVisibleSwitch: Switch
    private lateinit var bgHideSwitch: Switch
    private lateinit var recentNEdit: EditText
    private lateinit var recentCountView: TextView
    private lateinit var apiBaseEdit: EditText
    private lateinit var apiKeyEdit: EditText
    private lateinit var apiEveryEdit: EditText
    private lateinit var apiThresholdEdit: EditText
    private lateinit var apiStatusView: TextView
    private lateinit var ruleOverviewView: TextView
    private lateinit var autoAddedView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var accessibilityPrompted = false
    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1000)
        }
    }

    // ==================================================================
    //  生命周期
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = resolveDark()
        // 注意：这里**不能**重置面板上的两个大数字。
        // 用户的口径是"只有彻底关掉 App，或停止拦截再重新启动拦截"才归零；
        // 从后台切回来（Activity 重建）不算，重置会让面板显示 0 而通知栏还写着 250。
        // 真正的重置点在 AdBlockVpnService.startTunnelInternal() 与
        // SkipAdAccessibilityService.onServiceConnected() 里。
        // 作用范围的一次性迁移（静默执行，见 Prefs.migrateScopeOnce 的说明）
        Prefs.migrateScopeOnce(this)
        // AI 审查状态的一次性清空（作者要求重新测试）：只执行一次
        runApiStatusResetOnce()
        setContentView(buildUi())
        styleTopBar()
        loadPrefsToUi()
        refreshRuleOverview()
        applyStoreGuard(Prefs.storeGuard(this), silent = true)
        requestNotificationPermissionIfNeeded()
        ensureDisclaimer()
        notifyIfJustUpdated()
        applyRecentsVisibility()
    }

    /**
     */
    private fun notifyIfJustUpdated() {
        val now = try {
            packageManager.getPackageInfo(packageName, 0).versionCode
        } catch (_: Throwable) {
            0
        }
        if (now == 0) return
        val last = Prefs.lastVersion(this)
        Prefs.setLastVersion(this, now)
        if (last == 0 || last == now) return
        if (!Prefs.autoSkip(this)) return
        if (SkipAdAccessibilityService.running) return
        try {
            AlertDialog.Builder(this, dialogTheme())
                .setTitle("无障碍异常怎么办")
                .setMessage("如果开启无障碍异常，彻底关闭软件重开，或重启手机。")
                .setPositiveButton("知道了", null)
                .show()
        } catch (_: Throwable) {
        }
    }

    /**
     */
    private fun ensureDisclaimer() {
        if (Prefs.disclaimerAccepted(this)) return
        val dialog = AlertDialog.Builder(this, dialogTheme())
            .setTitle("使用前请阅读并同意")
            .setMessage(
                "1. 本软件的一切行为都在你的手机本地完成：不联网上传、不采集、不共享任何数据，" +
                    "没有服务器，也没有账号。\n\n" +
                    "2. 拦截、跳过、关闭弹窗等动作，都是你自愿开启的；你可以随时在设置里关闭，" +
                    "或直接卸载。\n\n" +
                    "3. 本软件仅供个人学习与自用，请勿用于商业用途，请遵守你所在地区的法律法规。\n\n" +
                    "4. 是否使用、如何使用由你自行决定，使用产生的一切后果由使用者自行承担。\n\n" +
                    "点击「同意并继续」表示你已阅读并接受以上全部内容；" +
                    "点击「拒绝并退出」将直接关闭本软件。"
            )
            .setCancelable(false)
            .setPositiveButton("同意并继续") { _, _ ->
                Prefs.acceptDisclaimer(this)
                toast("已接受声明，感谢使用")
            }
            .setNegativeButton("拒绝并退出") { _, _ ->
                toast("你已拒绝声明，本软件将退出")
                finishAffinity()
                @Suppress("DEPRECATION")
                android.os.Process.killProcess(android.os.Process.myPid())
            }
            .create()
        try {
            dialog.show()
        } catch (_: Throwable) {
        }
    }

    override fun onResume() {
        applyRecentsVisibility()
        super.onResume()
        handler.post(ticker)
        maybePromptAccessibility()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        // 退出前台时把统计和记录落盘：被杀掉也不会丢（统计用 commit，避免异步写丢）
        try {
            DnsLog.saveToDisk(this, true)
            BlockStats.flush(this, sync = true)
        } catch (_: Throwable) {
        }
    }

    /**
     */
    /**
     */
    /** 0=跟随系统 1=浅色 2=深色 → 实际是否深色 */
    private fun resolveDark(): Boolean = when (Prefs.darkModeState(this)) {
        2 -> true
        1 -> false
        else -> {
            val mode = resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
            mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
    }

    /** 顶部月亮按钮：0→2→1→0 循环（跟随系统 / 深色 / 浅色） */
    private fun cycleDarkMode() {
        val next = when (Prefs.darkModeState(this)) {
            0 -> 2
            2 -> 1
            else -> 0
        }
        Prefs.setDarkModeState(this, next)
        dark = resolveDark()
        toast(
            when (next) {
                2 -> "深色模式（手动）"
                1 -> "浅色模式（手动）"
                else -> "跟随系统"
            }
        )
        recreate()
    }

    private fun darkModeGlyph(): String = when (Prefs.darkModeState(this)) {
        2 -> "🌙"
        1 -> "☀"
        else -> "🌓"
    }

    private fun styleTopBar() {
        try {
            val ab = actionBar ?: return
            ab.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(cBg))
            ab.setDisplayShowTitleEnabled(false)
            ab.setDisplayShowCustomEnabled(true)
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            bar.addView(
                TextView(this).apply {
                    text = "disAD"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(cText)
                    setPadding(dp(16), dp(6), 0, dp(6))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            bar.addView(
                TextView(this).apply {
                    text = darkModeGlyph()
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    setPadding(dp(10), dp(6), dp(16), dp(6))
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    setOnClickListener { cycleDarkMode() }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            ab.setDisplayOptions(
                android.app.ActionBar.DISPLAY_SHOW_CUSTOM
            )
            ab.setCustomView(
                bar,
                android.app.ActionBar.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
              )
        } catch (_: Throwable) {
        }
    }

    private fun applyRecentsVisibility() {
        try {
            val am = getSystemService(android.app.ActivityManager::class.java) ?: return
            val hide = Prefs.bgHide(this)
            val id = taskId
            am.appTasks.firstOrNull { it.taskInfo.id == id }?.setExcludeFromRecents(hide)
        } catch (_: Throwable) {
        }
    }

    /**
     */
    private fun maybePromptAccessibility() {
        if (accessibilityPrompted) return
        if (!Prefs.autoSkip(this)) return
        if (!AdBlockVpnService.running) return
        if (isAccessibilityEnabled()) return
        accessibilityPrompted = true
        toast("无障碍自动跳过已被系统关闭，正在打开设置页…")
        handler.postDelayed({
            try {
                openAccessibilitySettings()
            } catch (_: Throwable) {
            }
        }, 600)
    }

    // ==================================================================
    //  交互
    private fun onMainButtonClick() {
        if (AdBlockVpnService.running) {
            AdBlockVpnService.stop(this)
            toast("已停止拦截")
            handler.postDelayed({ refreshStatus() }, 200)
            return
        }
        savePrefsFromUi(showToast = false)

        val prepareIntent: Intent? = try {
            VpnService.prepare(this)
        } catch (t: Throwable) {
            toast("无法请求 VPN 权限：${t.message}")
            null
        }

        if (prepareIntent != null) {
            try {
                startActivityForResult(prepareIntent, REQ_VPN)
            } catch (t: Throwable) {
                toast("打开 VPN 授权页面失败：${t.message}")
            }
        } else {
            AdBlockVpnService.start(this)
            toast("拦截已启动")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SAVE_LOG) {
            if (resultCode == RESULT_OK) writeLogTo(data?.data)
            return
        }
        if (requestCode != REQ_VPN) return
        if (resultCode == RESULT_OK) {
            AdBlockVpnService.start(this)
            toast("拦截已启动")
        } else {
            toast("未授予 VPN 权限，无法拦截广告")
            showVpnAuthHelp()
        }
    }

    /**
     */
    private fun showVpnAuthHelp() {
        val text = """
            安卓同一时间只允许一个 VPN 生效，而且部分系统会拦掉授权弹窗，
            所以「点启动拦截没反应」基本都是下面几种情况：

            1) 系统里还占着一个 VPN（最常见）
               有的系统在 App 被强杀后会留一个「僵尸 VPN」，开关看着是关的，其实还占着。
               · 先把其它 VPN / 加速器全部停掉；
               · 还不行就【重启一次手机】—— 这一步能解决大多数情况。

            2) 国产系统把授权弹窗拦了（小米 / 红米最常见）
               设置 → 应用设置 → 应用管理 → disAD → 权限管理
               → 把「后台弹出界面」设为允许；顺便允许「自启动」。

            3) 开了「始终开启的 VPN」
               设置 → 连接与共享 → VPN → 右上角齿轮 → 改成「无」。

            4) 还不行
               到 设置 → VPN 里看看有没有残留条目，手动删掉再试。
        """.trimIndent()
        try {
            AlertDialog.Builder(this, dialogTheme())
                .setTitle("VPN 授权没拿到？看这里")
                .setMessage(text)
                .setPositiveButton("知道了", null)
                .setNeutralButton("打开系统 VPN 设置") { _, _ ->
                    try {
                        startActivity(Intent("android.settings.VPN_SETTINGS"))
                    } catch (_: Throwable) {
                        toast("请手动进入 设置 → VPN")
                    }
                }
                .show()
        } catch (_: Throwable) {
        }
    }

    // ==================================================================
    //  AI 审查设置
    private fun saveApiSettings(silent: Boolean = false) {
        try {
            val sp = Prefs.sp(this)
            sp.edit()
                .putString(Prefs.KEY_API_BASE, apiBaseEdit.text.toString().trim().ifEmpty { Prefs.DEFAULT_API_BASE })
                .putString(Prefs.KEY_API_KEY, apiKeyEdit.text.toString().trim())
                .putInt(Prefs.KEY_API_EVERY, apiEveryEdit.text.toString().toIntOrNull()?.coerceIn(1, 999) ?: 5)
                .putInt(Prefs.KEY_API_THRESHOLD, apiThresholdEdit.text.toString().toIntOrNull()?.coerceIn(500, 1000000) ?: 10000)
                .apply()
            if (!silent) {
                toast(if (apiKeyEdit.text.toString().isBlank()) "已保存（没填 Key，此功能不生效）" else "已保存")
            }
            // 第一次填好 Key：引导做一次连通性测试 + 拉模型
            val key = apiKeyEdit.text.toString().trim()
            if (key.isNotEmpty() && Prefs.apiStatus(this).isEmpty()) {
                AlertDialog.Builder(this, dialogTheme())
                    .setTitle("检测到已填写 API")
                    .setMessage("要不要现在拉取你账号下可用的模型，并顺便做一次连通性测试？")
                    .setPositiveButton("开始") { _, _ ->
                        fetchModels()
                    }
                    .setNegativeButton("稍后", null)
                    .show()
            }
            refreshStatus()
        } catch (t: Throwable) {
            toast("保存失败：${t.message}")
        }
    }

    /** 拉取账号下可用的模型 id，弹窗选一个填进去 */
    /** 「开启/停止跳过」大按钮：和 DNS 卡片一样的一个按钮切两个状态 */
    private fun toggleA11y() {
        val on = Prefs.autoSkip(this)
        if (on) {
            Prefs.sp(this).edit().putBoolean(Prefs.KEY_AUTO_SKIP, false).apply()
            SkipAdAccessibilityService.refreshOngoing()   // 立刻撤掉常驻通知
            refreshStatus()
            toast("无障碍跳过已停止（DNS 拦截不受影响）")
        } else {
            Prefs.sp(this).edit().putBoolean(Prefs.KEY_AUTO_SKIP, true).apply()
            if (!isAccessibilityEnabled()) openAccessibilitySettings()
            SkipAdAccessibilityService.refreshOngoing()
            refreshStatus()
            toast("无障碍跳过已开启")
        }
    }

    /**
     */
    private fun configureApiTest() {
        if (apiKeyEdit.text.toString().isBlank()) {
            toast("先填 API Key")
            return
        }
        saveApiSettings()
        ApiReview.listModels(this) { ids, err ->
            if (err != null) {
                toast("连不上：$err")
                return@listModels
            }
            if (ids.isEmpty()) {
                toast("这个地址没返回可用模型")
                return@listModels
            }
            val arr = ids.toTypedArray()
            AlertDialog.Builder(this, dialogTheme())
                .setTitle("选择模型")
                .setItems(arr) { _, which -> pickModelAndTest(arr[which]) }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /** 「修改模型」：只换模型，不做测试也可以 */
    private fun changeModel() {
        if (apiKeyEdit.text.toString().isBlank()) {
            toast("先填 API Key")
            return
        }
        saveApiSettings()
        ApiReview.listModels(this) { ids, err ->
            if (err != null) {
                toast("连不上：$err")
                return@listModels
            }
            val arr = ids.toTypedArray()
            AlertDialog.Builder(this, dialogTheme())
                .setTitle("修改模型")
                .setItems(arr) { _, which -> pickModelAndTest(arr[which]) }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun pickModelAndTest(model: String) {
        Prefs.sp(this).edit().putString(Prefs.KEY_API_MODEL, model).apply()
        refreshStatus()
        Prefs.setApiStatus(this, "正在测试 $model …")
        refreshStatus()
        ApiReview.testConnection(this, model) { ok, msg ->
            Prefs.setApiStatus(this, if (ok) "模型 $model：" + msg else "测试失败：$msg")
            refreshStatus()
            toast(if (ok) "模型 $model 连通正常" else "失败：$msg")
        }
    }

    private fun fetchModels() {
        if (apiKeyEdit.text.toString().isBlank()) {
            toast("先填 API Key")
            return
        }
        saveApiSettings()
        toast("正在向服务商查询模型列表…")
        ApiReview.listModels(this) { ids, err ->
            if (err != null) {
                toast("查询失败：$err")
                return@listModels
            }
            if (ids.isEmpty()) {
                toast("没拿到模型列表")
                return@listModels
            }
            val arr = ids.toTypedArray()
            AlertDialog.Builder(this, dialogTheme())
                .setTitle("可用模型（点一个填入）")
                .setItems(arr) { _, which ->
                    Prefs.sp(this).edit().putString(Prefs.KEY_API_MODEL, arr[which]).apply()
                    saveApiSettings()
                    toast("已选择 ${arr[which]}")
                }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    /** 一次性清空 AI 审查状态（方便重新填 Key 测试） */
    private fun runApiStatusResetOnce() {
        try {
            val sp = Prefs.sp(this)
            if (sp.getBoolean("api_status_reset_v123", false)) return
            sp.edit()
                .putString(Prefs.KEY_API_STATUS, "")
                .putInt(Prefs.KEY_API_LAST_SESSION, -1)
                .putInt(Prefs.KEY_API_LAST_BLOCKED, 0)
                .putLong(Prefs.KEY_API_LAST_AT, 0L)
                .putBoolean("api_status_reset_v123", true)
                .apply()
        } catch (_: Throwable) {
        }
    }

    private fun loadApiSettingsToUi() {
        if (!::apiBaseEdit.isInitialized) return
        apiBaseEdit.setText(Prefs.apiBase(this))
        apiKeyEdit.setText(Prefs.apiKey(this))
        apiEveryEdit.setText(Prefs.apiEvery(this).toString())
        apiThresholdEdit.setText(Prefs.apiThreshold(this).toString())
    }

    // ==================================================================
    //  查看当前规则（只读，不提供导出）
    private fun showCurrentRules() {
        try {
            val bl = BlockList().also {
                it.load(
                    this,
                    Prefs.customDomains(this),
                    Prefs.whitelist(this),
                    Prefs.autoAdded(this),
                    Prefs.scopePdd(this)
                )
            }
            val sb = StringBuilder()
            sb.append("生效域名：").append(bl.size).append(" 条\n")
            sb.append("　· 内置清单：").append(bl.size - bl.countCustom - bl.countAuto).append(" 条\n")
            sb.append("　· 自定义：").append(bl.countCustom).append(" 条\n")
            sb.append("　· 自动学习：").append(bl.countAuto).append(" 条\n")
            sb.append("　· 白名单（永不拦）：").append(bl.countWhite).append(" 条\n\n")
            sb.append("关键字规则：").append(bl.countKeyword).append(" 条\n")
            sb.append("（标签前缀 / 整标签精确 / 连续标签序列）\n\n")
            sb.append("作用范围：").append(Prefs.scopeLabel(this))
            val tv = TextView(this).apply {
                text = sb.toString()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(cText)
                setTextIsSelectable(true)
                setPadding(dp(16), dp(12), dp(16), dp(4))
            }
            AlertDialog.Builder(this, dialogTheme())
                .setTitle("当前规则")
                .setView(ScrollView(this).apply { addView(tv) })
                .setPositiveButton("关闭", null)
                .show()
        } catch (t: Throwable) {
            toast("读取规则失败：${t.message}")
        }
    }

    // ==================================================================
    //  崩溃记录查看
    private fun showCrashLog() {
        val text = CrashLog.read(this) ?: return
        try {
            val tv = TextView(this).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(cText)
                setTextIsSelectable(true)
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            AlertDialog.Builder(this, dialogTheme())
                .setTitle("崩溃报告")
                .setView(ScrollView(this).apply { addView(tv) })
                .setPositiveButton("关闭", null)
                .setNeutralButton("复制") { _, _ ->
                    try {
                        val cm = getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText("disAD 崩溃记录", text))
                        toast("已复制，粘贴发给作者即可")
                    } catch (_: Throwable) {
                    }
                }
                .setNegativeButton("清除记录") { _, _ ->
                    CrashLog.clear(this)
                    refreshStatus()
                    toast("已清除")
                }
                .show()
        } catch (t: Throwable) {
            toast("打开失败：${t.message}")
        }
    }

    // ==================================================================
    //  说明：界面节点结构导出已迁移到独立的「维护工具」App
    private fun onApplyClick() {
        savePrefsFromUi(showToast = true)
        applyStoreGuard(Prefs.storeGuard(this), silent = false)
        refreshRuleOverview()
        if (AdBlockVpnService.running) {
            restartTunnel()
            toast("设置已保存，正在重新加载规则…")
        }
    }

    /**
     * 对话框主题：夜间模式必须换成深色主题。
     * 原来所有 AlertDialog 都用固定 Light 主题，夜间模式下正文是浅色字、底色是白，
     * 于是「域名记录 / 当前规则」这类长文本弹窗完全看不清。
     */
    private fun dialogTheme(): Int =
        if (isDarkNow()) R.style.Theme_DisAD_Dialog_Dark else R.style.Theme_DisAD_Dialog

    /** 当前外观是否深色（弹窗、系统控件都要用它，不能只看启动时的 dark 字段） */
    private fun isDarkNow(): Boolean = resolveDark()

    /** 作用范围（哪些 App 走 VPN）只能在建立隧道时确定，所以要重启隧道才能生效 */
    private fun restartTunnel() {
        AdBlockVpnService.stop(this)
        handler.postDelayed({ AdBlockVpnService.start(this) }, 600)
    }

    // ==================================================================
    //  状态刷新
    private fun refreshStatus() {
        val running = AdBlockVpnService.running

        mainButton.text = if (running) "停止拦截" else "启动拦截"
        mainButton.background = rounded(if (running) cDanger else cPrimary, 12)

        val a11yOnly = Prefs.a11yOnly(this)
        statusTitleView.text = if (running) "● DNS 拦截运行中" else "○ DNS 拦截已停止"
        statusTitleView.setTextColor(if (running) cOk else cSubText)

        statBlockedView.text = BlockStats.sessionBlockedCount().toString()
        statSkippedView.text = SkipAdAccessibilityService.skipCount.toString()

        val accState = when {
            !Prefs.autoSkip(this) -> "已关闭（开关）"
              !AdBlockVpnService.running && !Prefs.a11yOnly(this) -> "已暂停（拦截未运行）"
            SkipAdAccessibilityService.running -> "已生效"
            else -> "未授权 / 被系统关闭"
        }
        if (accState.startsWith("未授权")) {
            accHintView.visibility = View.VISIBLE
        } else {
            accHintView.visibility = View.GONE
        }
        crashView.visibility = if (CrashLog.has(this)) View.VISIBLE else View.GONE
        // 无障碍跳过卡片
        if (::a11yStatusView.isInitialized) {
            val enabled = isAccessibilityEnabled()
            val on = Prefs.autoSkip(this)
            a11yStatusView.text = when {
                !on -> "○ 无障碍辅助：已停止"
                !enabled -> "○ 无障碍辅助：已开启，但系统开关没打开"
                else -> "● 无障碍辅助：已生效（独立于 VPN）"
            }
            a11yStatusView.setTextColor(if (on && enabled) cOk else cSubText)
            a11yBigButton.text = if (on) "停止跳过" else "启动跳过"
            a11yBigButton.background = rounded(if (on) cDanger else cPrimary, 12)
            if (::a11yCountView.isInitialized) {
                a11yCountView.text = SkipAdAccessibilityService.skipCount.toString()
            }
            if (::a11yActionView.isInitialized) {
                a11yActionView.text = "最近动作：" + SkipAdAccessibilityService.lastAction
            }
        }

        // AI 审查状态
        if (::apiStatusView.isInitialized) {
            val st = Prefs.apiStatus(this)
            val base = when {
                Prefs.apiKey(this).isBlank() -> "状态：未配置（此功能不生效）"
                st.isEmpty() -> "状态：就绪"
                else -> "状态：$st"
            }
            // 把「还差多少次」也写出来：阈值是不是真的在走，用户一眼能看见
            apiStatusView.text = if (Prefs.apiKey(this).isBlank()) {
                base
            } else {
                val total = BlockStats.blockedCount()
                val threshold = Prefs.apiThreshold(this).coerceAtLeast(500)
                val since = (total - Prefs.apiLastBlocked(this)).coerceAtLeast(0L)
                val left = (threshold - since).coerceAtLeast(0L)
                "$base\n累计拦截 $total 次 · 距下次自动审查还差 $left 次"
            }
        }
        statusDetailView.text = "作用范围：" + Prefs.scopeLabel(this@MainActivity)
        statusDetailView.setTextColor(cSubText)

        val recent = BlockStats.recentBlocked()
        recentView.text = if (recent.isEmpty()) {
            "最近拦截：—"
        } else {
            "最近拦截：" + recent.distinct().take(3).joinToString("、")
        }

        val autoCount = AutoLearn.autoAddedCount(this)
        // 页脚只留两样：版本号 + 自动学习加入的域名数量
        if (::recentCountView.isInitialized) {
            recentCountView.text = "已缓存 ${DnsLog.recentCount()} 条（最多 5000）"
        }
        footerView.text = "v2.4.0 · 自动学习 + AI 审查：$autoCount 个域名"
    }

    // ==================================================================
    //  设置读写
    private fun loadPrefsToUi() {
        scopeBaiduSwitch.isChecked = Prefs.scopeBaidu(this)
        scopeOtherSwitch.isChecked = Prefs.scopeOther(this)
        scopePddSwitch.isChecked = Prefs.scopePdd(this)
        skipToastSwitch.isChecked = Prefs.skipToast(this)
        ocrSwitch.isChecked = Prefs.ocrFallback(this)
        bootSwitch.isChecked = Prefs.bootAutoStart(this)
        syncingSwitches = true
        bgVisibleSwitch.isChecked = !Prefs.bgHide(this)
        bgHideSwitch.isChecked = Prefs.bgHide(this)
        syncingSwitches = false

        loadApiSettingsToUi()
        upstreamEdit.setText(Prefs.upstream(this))
        upstream2Edit.setText(Prefs.upstream2(this))
        customEdit.setText(Prefs.customDomains(this))
        whiteEdit.setText(Prefs.whitelist(this))
        extraPkgEdit.setText(Prefs.extraPackages(this))
    }

    private fun savePrefsFromUi(showToast: Boolean) {
        val up1raw = upstreamEdit.text.toString().trim()
        val up2raw = upstream2Edit.text.toString().trim()
        val up1 = if (isIpv4(up1raw)) up1raw else Prefs.DEFAULT_UPSTREAM
        val up2 = if (isIpv4(up2raw)) up2raw else Prefs.DEFAULT_UPSTREAM2
        if (up1 != up1raw) upstreamEdit.setText(up1)
        if (up2 != up2raw) upstream2Edit.setText(up2)

        Prefs.sp(this).edit()
            .putBoolean(Prefs.KEY_SCOPE_BAIDU, scopeBaiduSwitch.isChecked)
            .putBoolean(Prefs.KEY_SCOPE_OTHER, scopeOtherSwitch.isChecked)
            .putBoolean(Prefs.KEY_SCOPE_PDD, scopePddSwitch.isChecked)
            .putBoolean(Prefs.KEY_BOOT, bootSwitch.isChecked)
            .putString(Prefs.KEY_UPSTREAM, up1)
            .putString(Prefs.KEY_UPSTREAM2, up2)
            .putString(Prefs.KEY_CUSTOM, customEdit.text.toString())
            .putString(Prefs.KEY_WHITELIST, whiteEdit.text.toString())
            .putString(Prefs.KEY_EXTRA_PKGS, extraPkgEdit.text.toString())
            .apply()

        if (showToast) toast("设置已保存")
    }

    /** 只接受 IPv4 字面量，避免上游地址本身还要走一次 DNS */
    private fun isIpv4(s: String): Boolean {
        val parts = s.split('.')
        if (parts.size != 4) return false
        for (p in parts) {
            val v = p.toIntOrNull() ?: return false
            if (v < 0 || v > 255) return false
        }
        return true
    }

    /** 启用/停用「应用商店跳转拦截」组件 */
    private fun applyStoreGuard(enabled: Boolean, silent: Boolean) {
        val cn = ComponentName(this, MarketInterceptActivity::class.java)
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        try {
            packageManager.setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP)
            if (enabled && !silent) {
                toast("已启用：广告拉起应用商店时若弹出选择框，请选「disAD」并点「始终」")
            }
        } catch (t: Throwable) {
            if (!silent) toast("设置失败：${t.message}")
        }
    }

    /** 无障碍服务是否已在系统设置里启用 */
    private fun isAccessibilityEnabled(): Boolean {
        return try {
            val expected = ComponentName(this, SkipAdAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (enabled.isNullOrEmpty()) {
                false
            } else {
                enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
            }
        } catch (t: Throwable) {
            false
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (t: Throwable) {
            toast("无法打开无障碍设置：${t.message}")
        }
    }

    /** 申请加入电池优化白名单：国产 ROM 上能显著降低后台被杀、无障碍被关的概率 */
    private fun requestIgnoreBatteryOptimization() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (pm != null && pm.isIgnoringBatteryOptimizations(packageName)) {
                toast("已经在电池优化白名单里了")
                return
            }
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
            startActivity(intent)
        } catch (t: Throwable) {
            toast("无法打开：${t.message}（可到 设置→应用→disAD→省电策略 里手动设为「无限制」）")
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        try {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            }
        } catch (_: Throwable) {
        }
    }

    private fun toast(msg: String) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }

    // ==================================================================
    //  弹窗：域名记录
    private fun copyText(label: String, text: String) {
        try {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText(label, text))
            toast("已复制到剪贴板")
        } catch (t: Throwable) {
            toast("复制失败：${t.message}")
        }
    }

    private fun recentN(): Int {
        val v = recentNEdit.text.toString().toIntOrNull() ?: 5
        return v.coerceIn(1, 500)
    }

    /** 近 n 次会话的记录文本：每次会话一段，含起止时间与条数 */
    private fun recentText(n: Int, limitLines: Int): String {
        val groups = DnsLog.recentSessionGroups(n)
        if (groups.isEmpty()) return "（还没有记录）"
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val sb = StringBuilder()
        val total = groups.sumOf { it.second.size }
        sb.append("最近 ").append(groups.size).append(" 次 DNS 会话，共 ")
            .append(total).append(" 条记录\n\n")
        var lines = 0
        for ((idx, recs) in groups) {
            if (recs.isEmpty()) continue
            val first = fmt.format(java.util.Date(recs.last().at))
            val last = fmt.format(java.util.Date(recs.first().at))
            val blockedN = recs.count { it.blocked }
            sb.append("■ 第 ").append(idx).append(" 次　").append(first)
            if (last != first) sb.append(" ~ ").append(last)
            sb.append("　共 ").append(recs.size).append(" 条（拦 ").append(blockedN).append("）\n")
            for (r in recs) {
                if (lines++ >= limitLines) {
                    sb.append("　…（内容过多，导出可看全部）\n")
                    return sb.toString()
                }
                sb.append("　").append(fmt.format(java.util.Date(r.at))).append(" ")
                    .append(if (r.blocked) "拦" else "放").append(" ")
                    .append(r.domain).append('\n')
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun showRecentRecords() {
        val n = recentN()
        val text = recentText(n, 400)
        val tv = TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(cText)
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("最近 $n 次 DNS 会话")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("关闭", null)
            .setNeutralButton("复制") { _, _ ->
                copyText("disAD 域名记录", text)
            }
            .show()
    }

    private fun exportRecentRecords() {
        val n = recentN()
        val text = recentText(n, Int.MAX_VALUE)
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "disAD 近 $n 次域名记录")
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(intent, "导出到"))
        } catch (t: Throwable) {
            toast("导出失败：${t.message}")
        }
    }

    private fun trimRecentRecords() {
        val n = recentN()
        val sessions = DnsLog.recentSessionGroups(0).size
        if (sessions <= n) {
            toast("现在只有 $sessions 次会话，不用清")
            return
        }
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("清除更早的记录")
            .setMessage("将只保留最近 $n 次 DNS 会话，更早的整段丢掉。\n（不影响拦截规则和统计）")
            .setPositiveButton("清除") { _, _ ->
                DnsLog.keepRecentSessions(n)
                refreshStatus()
                toast("已清除，剩 ${DnsLog.recentCount()} 条记录")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDnsLog() {
        val entries = DnsLog.entries()
        val switchState = if (DnsLog.enabled) "已开启" else "未开启"
        val text: String = if (entries.isEmpty()) {
            "记录开关：$switchState\n\n" +
                "还没有记录到任何域名。\n\n" +
                "【正确用法】\n" +
                "1. 确认「记录全部域名」开关是打开的（默认就开，拨动即时生效）；\n" +
                "2. 回到这里点一次「清空」（关键，这样列表才是干净的一次启动）；\n" +
                "3. 确认「拦截运行中」，并且目标 App 在作用范围内；\n" +
                "4. 回桌面冷启动一次百度地图（或贴吧），等广告出现/消失；\n" +
                "5. 再回来点「查看域名记录」。"
        } else {
            val sb = StringBuilder()
            sb.append("记录开关：").append(switchState)
                .append("    共 ").append(entries.size).append(" 个域名\n")
            sb.append("✕ = 已拦截    · = 已放行\n")
            sb.append("★ 最新解析到的在最上面\n\n")
            entries.take(500).forEach { e ->
                sb.append(if (e.blocked) "✕ " else "· ")
                    .append(e.count).append(" 次  ")
                    .append(e.domain).append('\n')
            }
            if (entries.size > 500) {
                sb.append("\n（此处只显示前 500 条，完整内容请点「导出到文件」）")
            }
            sb.toString()
        }

        val tv = TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(cText)
            setTextIsSelectable(true)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val sv = ScrollView(this).apply { addView(tv) }

        try {
            AlertDialog.Builder(this, dialogTheme())
                .setTitle("域名记录")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .setNeutralButton("清空") { _, _ ->
                    DnsLog.clear()
                    toast("已清空记录")
                }
                .setNegativeButton("导出到文件") { _, _ -> exportDnsLog() }
                .show()
        } catch (t: Throwable) {
            toast("打开记录失败：${t.message}")
        }
    }

    private fun exportDnsLog() {
        // 用系统文件选择器（SAF）导出：用户可以自己选位置（一般存到「下载」），
        // 不需要任何存储权限，也避开了 Android 11+ 对 Android/data 的访问限制。
        try {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "disAD-dns-log.txt")
            }
            startActivityForResult(intent, REQ_SAVE_LOG)
        } catch (t: Throwable) {
            toast("导出失败：${t.message}")
        }
    }

    private fun writeLogTo(uri: Uri?) {
        if (uri == null) return
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(DnsLog.toText().toByteArray(Charsets.UTF_8))
                out.flush()
            }
            toast("已导出，可在文件管理器的「下载」里找到")
        } catch (t: Throwable) {
            toast("写入失败：${t.message}")
        }
    }

    // ==================================================================
    //  自动学习：分析漏网域名 / 撤销
    private fun buildBlockList(): BlockList =
        BlockList().also {
            it.load(
                this,
                Prefs.customDomains(this),
                Prefs.whitelist(this),
                Prefs.autoAdded(this),
                Prefs.scopePdd(this)
            )
        }

    private fun showCandidates() {
        if (DnsLog.totalSessions < 2) {
            toast("数据还不够：请先冷启动目标 App 两次以上再分析")
            return
        }
        val candidates = try {
            val bl = buildBlockList()
            AutoLearn.analyze(this) { bl.isBlocked(it) }
        } catch (t: Throwable) {
            toast("分析失败：${t.message}")
            return
        }
        if (candidates.isEmpty()) {
            toast("暂无可疑域名（多冷启动几次目标 App 再来分析）")
            return
        }

        val labels = Array(candidates.size) { i ->
            val c = candidates[i]
            val mark = if (c.high) "★" else "·"
            "$mark ${c.domain}\n      出现 ${c.count} 次 / ${c.sessions} 个启动（共 ${c.totalSessions} 次）\n      依据：${c.reasons}"
        }
        val checked = BooleanArray(candidates.size) { candidates[it].high }

        try {
            AlertDialog.Builder(this, dialogTheme())
                .setTitle("可疑漏网域名（共 ${candidates.size} 个，★已默认勾选）")
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton("加入拦截") { _, _ ->
                    val selected = ArrayList<String>()
                    for (i in candidates.indices) {
                        if (checked[i]) selected.add(candidates[i].domain)
                    }
                    if (selected.isEmpty()) {
                        toast("没有勾选任何域名")
                        return@setPositiveButton
                    }
                    val n = AutoLearn.addDomains(this, selected, toAutoList = false)
                    AdBlockVpnService.reloadRules(this)
                    customEdit.setText(Prefs.customDomains(this))
                    refreshStatus()
                    refreshRuleOverview()
                    toast("已加入拦截 $n 个域名，规则已重新加载")
                }
                .setNegativeButton("取消", null)
                .show()
        } catch (t: Throwable) {
            toast("打开列表失败：${t.message}")
        }
    }

    // ==================================================================
    //  规则总览 / 自动加入列表 / 规则自测
    /** 重新计算"规则总览"（要读 assets，只在需要时调用，不放进每秒刷新） */
    private fun refreshRuleOverview() {
        try {
            val bl = buildBlockList()
            ruleOverviewView.text = "规则总览：内置 ${bl.countDefault} + 常驻严格 ${bl.countStrict} + " +
                "拼多多 ${bl.countPdd} + 自定义 ${bl.countCustom} + 自动 ${bl.countAuto} " +
                "= ${bl.size} 个域名；关键字 ${bl.countKeyword} 条；白名单 ${bl.countWhite} 条"
            autoAddedView.text = "当前自动加入：${bl.countAuto} 个"
        } catch (t: Throwable) {
            ruleOverviewView.text = "规则总览读取失败：${t.message}"
        }
    }

    private fun showAutoAdded() {
        val ai = AutoLearn.aiAdded(this)
        val aiSet = ai.map { it.domain }.toHashSet()
        val learned = AutoLearn.autoAddedList(this).filter { it !in aiSet }

        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        val sb = StringBuilder()

        sb.append("【AI 审查加入】").append(ai.size).append(" 个\n")
        if (ai.isEmpty()) {
            sb.append("（还没有；在「AI 审查」里配置 Key 后会自动/手动跑）\n")
        } else {
            var lastKey = ""
            for (a in ai) {
                val key = fmt.format(java.util.Date(a.at)) + (if (a.manual) " · 手动" else " · 自动")
                if (key != lastKey) {
                    sb.append("— ").append(fmt.format(java.util.Date(a.at)))
                        .append(if (a.manual) "（手动触发）" else "（自动触发）").append('\n')
                    lastKey = key
                }
                sb.append("   ").append(a.domain).append('\n')
            }
        }

        sb.append('\n').append("【自动学习加入】").append(learned.size).append(" 个\n")
        if (learned.isEmpty()) {
            sb.append("（还没有）\n")
        } else {
            learned.forEach { sb.append("   ").append(it).append('\n') }
        }

        val tv = TextView(this).apply {
            this.text = sb.toString()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(cText)
            setTextIsSelectable(true)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }

        // 题库共建：≥20 个才允许导出（太少对公共规则库没有参考价值）
        val total = ai.size + learned.size
        val enableExport = total >= EXPORT_MIN

        try {
            AlertDialog.Builder(this, dialogTheme())
                .setTitle("加入拦截的域名（$total 个）")
                .setView(ScrollView(this).apply { addView(tv) })
                .setPositiveButton("关闭", null)
                .setNeutralButton(if (enableExport) "导出并发送" else "导出（需 ≥$EXPORT_MIN 个）") { _, _ ->
                    if (enableExport) exportAutoLearned(learned, ai) else toast(
                        "现在只有 $total 个，攒到 $EXPORT_MIN 个再导出更有参考价值"
                    )
                }
                .setNegativeButton("全部清空") { _, _ -> clearAutoAdded() }
                .show()
        } catch (t: Throwable) {
            toast("打开失败：${t.message}")
        }
    }

    /** 导出自动学习 + AI 审查加入的域名（带注释头），走系统分享，可直接发到作者邮箱 */
    private fun exportAutoLearned(learned: List<String>, ai: List<AutoLearn.AiAdded>) {
        try {
            val counts = HashMap<String, Long>()
            for (e in DnsLog.entries()) counts[e.domain] = e.count
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            val verName = try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
            } catch (_: Throwable) {
                "?"
            }
            val aiSet = ai.map { it.domain }.toHashSet()

            val sb = StringBuilder()
            sb.append("# disAD 自动学习域名提交\n")
            sb.append("# 版本：").append(verName).append('\n')
            sb.append("# 导出时间：").append(fmt.format(java.util.Date())).append('\n')
            sb.append("# 数量：").append(learned.size + ai.size)
                .append("（自动学习 ").append(learned.size)
                .append(" / AI 审查 ").append(ai.size).append("）\n")
            sb.append("# 说明：这些域名是 disAD 在本机按广告特征自动学习、或经 AI 审查后加入拦截的，\n")
            sb.append("#       用于完善公共规则库。只含域名与出现次数，不含任何账号、内容或设备信息。\n")
            sb.append("# 域名\t来源\t本机出现次数\n")

            for (d in learned) {
                sb.append(d).append('\t').append("自动学习").append('\t')
                    .append(counts[d] ?: 0L).append('\n')
            }
            for (a in ai) {
                if (a.domain in aiSet && learned.contains(a.domain)) continue
                sb.append(a.domain).append('\t')
                    .append(if (a.manual) "AI 审查（手动）" else "AI 审查（自动）").append('\t')
                    .append(counts[a.domain] ?: 0L).append('\n')
            }

            val text = sb.toString()
            val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf(EXPORT_MAIL))
                putExtra(android.content.Intent.EXTRA_SUBJECT, "disAD 自动学习域名提交（${learned.size + ai.size} 个）")
                putExtra(android.content.Intent.EXTRA_TEXT, text)
            }
            startActivity(android.content.Intent.createChooser(share, "发送到"))
        } catch (t: Throwable) {
            toast("导出失败：${t.message}")
        }
    }

    private fun clearAutoAdded() {
        val n = AutoLearn.undoAuto(this)
        if (n == 0) {
            toast("没有自动加入的域名")
            return
        }
        AdBlockVpnService.reloadRules(this)
        refreshStatus()
        refreshRuleOverview()
        toast("已清空 $n 个自动加入的域名")
    }

    /** 规则自测：输入域名，直接告诉你会不会被拦、命中哪条规则 */
    private fun runSelfTest() {
        val q = testEdit.text.toString().trim()
        if (q.isEmpty()) {
            toast("请先输入一个域名，例如 mobads.baidu.com")
            return
        }
        val result = try {
            buildBlockList().explain(q)
        } catch (t: Throwable) {
            "读取规则失败：${t.message}"
        }
        val msg = result ?: "会放行（没有命中任何规则）"
        try {
            AlertDialog.Builder(this, dialogTheme())
                .setTitle("规则自测：$q")
                .setMessage(msg)
                .setPositiveButton("知道了", null)
                .show()
        } catch (t: Throwable) {
            toast(msg)
        }
    }

    // ==================================================================
    //  弹窗：使用说明（内容见 HelpText.kt，这里只剩打开逻辑）
    // ==================================================================
    //  使用说明：每个板块是一个可点的小按钮，点开才看完整说明
    private fun showHelpSection(sec: HelpSection) {
        try {
            AlertDialog.Builder(this, dialogTheme())
                .setTitle(sec.title)
                .setMessage(sec.body)
                .setPositiveButton("知道了", null)
                .setNeutralButton("复制说明") { _, _ ->
                    try {
                        val cm = getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(
                            android.content.ClipData.newPlainText(
                                sec.title,
                                sec.title + "\n\n" + sec.body
                            )
                        )
                        toast("已复制")
                    } catch (_: Throwable) {
                    }
                }
                .show()
        } catch (t: Throwable) {
            toast("打开失败：${t.message}")
        }
    }

    /** 说明板块的一行：标题 + 一句话概括 + 右侧箭头，整行可点 */
    private fun helpRow(title: String, summary: String, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(11), 0, dp(11))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = weightParams(1f)
        }
        col.addView(label(title, 14f, "#1E63D8", false))
        if (summary.isNotEmpty()) {
            col.addView(label(summary, 11f, "#6B7280", false))
        }
        row.addView(col)
        row.addView(label("›", 20f, "#9AA1AC", false))
        return row
    }

    /** 摇一摇：跳到系统的应用管理列表，让用户自己去关那个 App 的「运动与方向」权限 */
    private fun openAppListSettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
        } catch (t: Throwable) {
            toast("打不开：${t.message}")
        }
    }

    // ==================================================================
    //  界面构建
    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(16), dp(14), dp(28))
        }

        // ---------- ① 顶部标题 ----------
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cPrimary, 14)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        header.addView(label("disAD", 22f, "#FFFFFF", true))
        header.addView(
            label("免 Root · DNS 拦截 + 无障碍兜底", 12f, "#C7DBFF", false)
                .apply { setPadding(0, dp(2), 0, 0) }
        )
        root.addView(header, matchWrap())

        // ---------- ② 状态卡片 ----------
        val statusCard = card(root)
        statusTitleView = label("○ 拦截已停止", 17f, "#6B7280", true)
        statusCard.addView(statusTitleView)

        val statRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        statBlockedView = bigNumber("0", cPrimary)
        statSkippedView = bigNumber("0", cOk)
        statRow.addView(statColumn(statBlockedView, "DNS 拦截（次）"), weightParams(1f))
        statRow.addView(statColumn(statSkippedView, "自动跳过（次）"), weightParams(1f))
        statusCard.addView(statRow)

        // ★ 醒目位置的一行红字：App 会把广告素材缓存在本地，
        //   不清掉的话，已经下载好的广告断网也会再显示一次 —— 用户会以为没生效。
        statusCard.addView(
            label(
                "⚠ 使用前请清除本地广告缓存，否则已下载的广告还会再显示一次",
                13f, "#DC2626", true
            ).apply { setPadding(0, dp(12), 0, 0) }
        )

        statusDetailView = label("", 13f, "#6B7280", false)
            .apply { setPadding(0, dp(10), 0, 0) }
        statusCard.addView(statusDetailView)

        recentView = label("最近拦截：—", 12f, "#6B7280", false)
            .apply { setPadding(0, dp(6), 0, 0) }
        statusCard.addView(recentView)

        // 崩溃 / 启动失败时，把堆栈留在本机，这里给出入口。
        crashView = label(
            "⚠ 崩溃报告（请向作者反应）· 点这里查看",
            12f, "#DC2626", true
        ).apply {
            setPadding(0, dp(10), 0, 0)
            visibility = View.GONE
            setOnClickListener { showCrashLog() }
        }
        statusCard.addView(crashView)

        // ---------- 无障碍跳过（独立卡片，和 DNS 拦截各跑各的） ----------
        val a11yCard = card(root, "无障碍跳过")

        a11yBigButton = Button(this).apply {
            text = "启动跳过"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(cPrimary, 12)
            stateListAnimator = null
            setOnClickListener { toggleA11y() }
        }
        a11yCard.addView(a11yBigButton, matchHeight(52, topMargin = 4))

        a11yCountView = label("0", 30f, "#1E63D8", true)
            .apply { setPadding(0, dp(10), 0, 0) }
        a11yCard.addView(a11yCountView)
        a11yCard.addView(label("跳过（次）", 11f, "#6B7280", false))

        a11yStatusView = label("", 12f, "#1F2329", false)
            .apply { setPadding(0, dp(8), 0, 0) }
        a11yCard.addView(a11yStatusView)

        a11yActionView = label("最近动作：—", 12f, "#6B7280", false)
            .apply { setPadding(0, dp(4), 0, 0) }
        a11yCard.addView(a11yActionView)

        a11yCard.addView(
            label(
                "独立于 VPN：只开这张卡片时 VPN 完全不会启动。",
                11f, "#6B7280", false
            ).apply { setPadding(0, dp(8), 0, 0) }
        )
        a11yCard.addView(
            smallButton("无障碍设置") { openAccessibilitySettings() },
            matchWrap(topMargin = 8)
        )

        // 无障碍没生效时，直接甩一条可点的提示 —— 装/更新过 App 之后系统常把它关掉，
        // 用户不知道的话会以为"软件没用"。
        accHintView = label(
            "⚠ 无障碍辅助没生效：点这里去系统设置里重新启用",
            12f, "#DC2626", true
        ).apply {
            setPadding(0, dp(10), 0, 0)
            visibility = View.GONE
            setOnClickListener { openAccessibilitySettings() }
        }
        statusCard.addView(accHintView)

        // ---------- ②b 摇一摇：系统跳转辅助 ----------
        val shakeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(if (dark) 0xFF3A2A14.toInt() else 0xFFFFF4E5.toInt(), 14)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        shakeCard.addView(label("摇一摇广告", 15f, "#B45309", true))
        shakeCard.addView(
            label(
                "关掉APP运动与方向权限即可使摇一摇广告失效",
                12f, "#8A5A17", false
            ).apply { setPadding(0, dp(4), 0, 0) }
        )
        shakeCard.addView(
            smallButton("去应用管理里关掉") { openAppListSettings() },
            matchHeight(42, topMargin = 10)
        )
        root.addView(shakeCard, matchWrap(topMargin = 12))

        // ---------- ③ 主按钮 ----------
        mainButton = Button(this).apply {
            text = "启动拦截"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            background = rounded(cPrimary, 12)
            setOnClickListener { onMainButtonClick() }
        }
        statusCard.addView(mainButton, matchHeight(52, topMargin = 12))

        // ---------- ④ 开关卡片 ----------
        val switchCard = card(root, "作用范围")
        scopeBaiduSwitch = switchRow(
            switchCard, Prefs.KEY_SCOPE_BAIDU,
            "百度系",
            "百度地图 / 网盘 / 贴吧 / 百度 App",
            Prefs.scopeBaidu(this)
        )
        scopeOtherSwitch = switchRow(
            switchCard, Prefs.KEY_SCOPE_OTHER,
            "其他",
            "淘宝、抖音、微博、知乎、系统应用等",
            Prefs.scopeOther(this)
        )
        scopePddSwitch = switchRow(
            switchCard, Prefs.KEY_SCOPE_PDD,
            "拼多多系",
            "拼多多 App 及它的广告素材域名",
            Prefs.scopePdd(this)
        )

        // ---------- 开关归位：DNS 卡片 / 无障碍卡片各管自己的 ----------
        // 「无障碍辅助 / 自动学习 / 拦截应用商店跳转 / 仅无障碍模式」四个开关已删除：
        bootSwitch = switchRow(
            statusCard, Prefs.KEY_BOOT,
            "开机自动启动",
            "开机后自动恢复 DNS 拦截",
            Prefs.bootAutoStart(this)
        )
        skipToastSwitch = switchRow(
            a11yCard, Prefs.KEY_SKIP_TOAST,
            "跳过时弹提示",
            "每点掉一个广告弹一条小提示",
            Prefs.skipToast(this)
        )
        ocrSwitch = switchRow(
            a11yCard, Prefs.KEY_OCR_FALLBACK,
            "OCR 兜底（实验）",
            "无障碍找不到跳过键时，截图 + 本地识别（功耗可忽略）",
            Prefs.ocrFallback(this)
        )
        // 「记录全部域名」已并入常态：域名记录是「查看域名记录」和「自动学习」共同的
        // 数据来源，做成开关只会让用户不小心关掉、然后在别处看到一片空白。

        // ---------- ⑤ 维护工具 ----------
        val toolCard = card(root, "维护工具")
        buttonRow(
            toolCard,
            smallButton("查看域名记录") { showDnsLog() },
        smallButton("查看当前规则") { showCurrentRules() },
        )
        buttonRow(
            toolCard,
            smallButton("清空统计") {
                BlockStats.reset(this)
                refreshStatus()
            },
            smallButton("无障碍设置") { openAccessibilitySettings() }
        )
        buttonRow(
            toolCard,
            smallButton("电池白名单") { requestIgnoreBatteryOptimization() }
        )

        // ---------- ⑤d 外观与通知 ----------
        // 深色模式移到标题栏月亮按钮：跟随系统 / 深色 / 浅色
        val uiCard = card(root, "外观与通知")
        bgVisibleSwitch = switchRow(
            uiCard, "bg_visible",
            "多任务可见",
            "",
            !Prefs.bgHide(this)
        )
        bgHideSwitch = switchRow(
            uiCard, "bg_hide",
            "多任务隐藏",
            "",
            Prefs.bgHide(this)
        )
        // ---------- ⑤c 近 N 次记录（查看 / 导出 / 清除） ----------
        val recentCard = card(root, "近 N 次记录")
        recentCountView = label("", 12f, "#6B7280", false)
        recentCard.addView(recentCountView)
        recentCard.addView(fieldLabel("保留最近几次 DNS 会话（一次 = 从打开到关闭）", topMargin = 10))
        recentNEdit = editRow(recentCard, "默认 5", "5")
        buttonRow(
            recentCard,
            smallButton("查看") { showRecentRecords() },
            smallButton("导出") { exportRecentRecords() }
        )
        recentCard.addView(
            smallButton("清除更早的记录（只留最近 N 次会话）") { trimRecentRecords() },
            matchWrap(topMargin = 8)
        )
        recentCard.addView(
            label(
                "只记域名和是否被拦，不记任何界面内容；全部保存在本机内存，最多 5000 条。",
                11f, "#6B7280", false
            ).apply { setPadding(0, dp(8), 0, 0) }
        )

        // ---------- ⑤b AI 审查（用户自填 API） ----------
        val aiCard = expandable(
            root,
            "AI 审查（实验）",
            "用你自己的大模型 API 判断漏网域名 · 不填 Key 则不生效",
            false
        )
        aiCard.addView(fieldLabel("API 地址（默认 DeepSeek，兼容 OpenAI 格式）"))
        apiBaseEdit = editRow(aiCard, Prefs.DEFAULT_API_BASE, Prefs.DEFAULT_API_BASE)
        aiCard.addView(fieldLabel("API Key（只存在本机，随请求发给你填的服务商）", topMargin = 10))
        apiKeyEdit = editRow(aiCard, "sk-...", "")

        aiCard.addView(fieldLabel("每 N 次会话审查一次", topMargin = 10))
        apiEveryEdit = editRow(aiCard, "默认 5", "5")
        aiCard.addView(fieldLabel("累计拦截次数达到多少时再审一次", topMargin = 10))
        apiThresholdEdit = editRow(aiCard, "默认 10000", "10000")

        apiStatusView = label("", 11f, "#6B7280", false)
            .apply { setPadding(0, dp(8), 0, 0) }
        aiCard.addView(apiStatusView)

        buttonRow(
            aiCard,
            smallButton("配置测试") { configureApiTest() },
            smallButton("修改模型") { changeModel() }
        )
        buttonRow(
            aiCard,
            smallButton("保存") { saveApiSettings() },
            smallButton("立即审查一次") {
                saveApiSettings(silent = true)
                if (ApiReview.busy) {
                    toast("上一次审查还在跑，稍等")
                } else {
                    toast("已开始审查，结果会以通知提醒")
                    ApiReview.runNow(this)
                }
            }
        )
        aiCard.addView(
            label(
                "做法：只把「放行的域名」发给你填的 API（一次最多 400 个），让它判断哪些其实是广告/追踪；" +
                    "结果只进「自动学习」名单，可一键撤销。读超时 120 秒（会重试 1 次）、失败静默处理。",
                11f, "#6B7280", false
            ).apply { setPadding(0, dp(10), 0, 0) }
        )

        // ---------- ⑥ 维护工具：拦截规则（折叠，一般不用改） ----------
        val ruleCard = expandable(
            root,
            "拦截规则",
            "上游 DNS / 自定义域名 / 白名单 / 规则自测 —— 一般不用改",
            false
        )
        ruleCard.addView(fieldLabel("上游 DNS（用于放行正常域名）"))
        upstreamEdit = editRow(ruleCard, "主 DNS", Prefs.DEFAULT_UPSTREAM)
        upstream2Edit = editRow(ruleCard, "备用 DNS", Prefs.DEFAULT_UPSTREAM2)

        ruleCard.addView(fieldLabel("自定义拦截域名（你手动加的，每行一个，自动含子域）", topMargin = 12))
        customEdit = editRow(ruleCard, "例如 ad.example.com", "", minLines = 3)

        ruleOverviewView = label("", 11f, "#6B7280", false)
            .apply { setPadding(0, dp(8), 0, 0) }
        ruleCard.addView(ruleOverviewView)

        // ---------- ⑦ 维护工具：自动学习加入（独立板块） ----------
        val autoCard = expandable(
            root,
            "自动学习 + AI 审查加入的域名",
            "程序自己加的域名独立存放，不会混进上面的输入框",
            false
        )
        autoAddedView = label("自动学习 + AI 审查：0 个", 13f, "#1F2329", false)
            .apply { setPadding(0, dp(4), 0, 0) }
        autoCard.addView(autoAddedView)
        buttonRow(
            autoCard,
            smallButton("查看全部") { showAutoAdded() },
            smallButton("全部清空") { clearAutoAdded() }
        )

        // 规则自测
        ruleCard.addView(fieldLabel("规则自测：输入域名，看会不会被拦", topMargin = 12))
        val testRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        testEdit = EditText(this).apply {
            hint = "例如 mobads.baidu.com"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(cText)
        }
        testRow.addView(testEdit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val testBtn = smallButton("测试") { runSelfTest() }
        testRow.addView(testBtn, LinearLayout.LayoutParams(dp(70), dp(40)))
        ruleCard.addView(testRow, matchWrap(topMargin = 4))

        ruleCard.addView(fieldLabel("白名单（这些域名永不拦截，误伤时可自救）", topMargin = 12))
        whiteEdit = editRow(ruleCard, "例如 pan.baidu.com", "", minLines = 2)

        ruleCard.addView(fieldLabel("额外作用 App 包名（每行一个，可留空）", topMargin = 12))
        extraPkgEdit = editRow(ruleCard, "例如 com.baidu.baidumaps", "", minLines = 2)

        ruleCard.addView(
            primaryButton("保存并重新加载规则") { onApplyClick() },
            matchHeight(46, topMargin = 14)
        )

        // ---------- ⑧ 维护工具：使用说明 + 法律声明（折叠） ----------
        val helpCard = expandable(
            root,
            "使用说明 · 法律声明",
            "每个板块都可以点开看；首次启动会要求确认这份声明，拒绝则自动退出",
            false
        )
        // 不再是一整段长文，而是一排可点的小板块（标题 + 一句话概括）
        for (sec in HelpText.sections) {
            helpCard.addView(helpRow(sec.title, sec.summary) { showHelpSection(sec) })
        }
        buttonRow(
            helpCard,
            smallButton("无障碍设置") { openAccessibilitySettings() },
            smallButton("电池白名单") { requestIgnoreBatteryOptimization() }
        )

        footerView = label("", 11f, "#9AA1AC", false)
            .apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, 0)
            }
        root.addView(footerView)

        return ScrollView(this).apply {
            setBackgroundColor(cBg)
        // 深色模式：状态栏/导航栏跟着变暗，图标转白
        try {
            window.statusBarColor = cBg
            window.navigationBarColor = cBg
            val lp = window.attributes
            if (dark) {
                window.decorView.systemUiVisibility = 0
            } else {
                window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
            window.attributes = lp
        } catch (_: Throwable) {
        }
            addView(root)
        }
    }

    // ---------------- 界面小工具 ----------------
    /**
     */
    private fun expandable(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        initiallyOpen: Boolean = false
    ): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cCard, 14)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        parent.addView(box, matchWrap(topMargin = 12))

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = label(title, 15f, "#1F2329", true)
            .apply { layoutParams = weightParams(1f) }
        val arrow = label(if (initiallyOpen) "▾" else "▸", 14f, "#6B7280", false)
        head.addView(titleView)
        head.addView(arrow)
        box.addView(head)
        box.addView(label(subtitle, 11f, "#6B7280", false).apply { setPadding(0, dp(2), 0, 0) })

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (initiallyOpen) View.VISIBLE else View.GONE
        }
        box.addView(content, matchWrap(topMargin = 8))

        head.setOnClickListener {
            val open = content.visibility == View.VISIBLE
            content.visibility = if (open) View.GONE else View.VISIBLE
            arrow.text = if (open) "▸" else "▾"
        }
        return content
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
        }

    private fun card(parent: LinearLayout, title: String? = null): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cCard, 14)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        parent.addView(box, matchWrap(topMargin = 12))
        if (title != null) {
            box.addView(
                label(title, 13f, "#1E63D8", true)
                    .apply { setPadding(0, 0, 0, dp(6)) }
            )
        }
        return box
    }

    private fun label(text: String, sizeSp: Float, color: String, bold: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(Color.parseColor(adaptColor(color)))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(3).toFloat(), 1f)
        }

    private fun bigNumber(value: String, color: Int): TextView =
        TextView(this).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setTextColor(color)
            setTypeface(typeface, Typeface.BOLD)
        }

    private fun statColumn(number: TextView, caption: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cSoft, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(number)
            addView(
                label(caption, 11f, "#6B7280", false)
                    .apply { setPadding(0, dp(2), 0, 0) }
            )
        }

    private fun fieldLabel(text: String, topMargin: Int = 6): TextView =
        label(text, 12f, "#6B7280", false)
            .apply { setPadding(0, dp(topMargin), 0, dp(4)) }

    private fun switchRow(
        parent: LinearLayout,
        key: String,
        title: String,
        desc: String,
        initial: Boolean
    ): Switch {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = weightParams(1f)
        }
        col.addView(label(title, 15f, "#1F2329", false))
        if (desc.isNotEmpty()) {
            col.addView(label(desc, 11f, "#6B7280", false))
        }
        val sw = Switch(this)
        // 深色模式下主题仍是 Light：开关的轨道/滑块会黑到看不见，这里手动着色
        try {
            val onColor = cPrimary
            val offTrack = if (dark) 0xFF4A5059.toInt() else 0xFFCFD5DD.toInt()
            val thumbColor = if (dark) 0xFFE6E8EB.toInt() else Color.WHITE
            sw.trackTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(onColor, offTrack)
            )
            sw.thumbTintList = android.content.res.ColorStateList.valueOf(thumbColor)
        } catch (_: Throwable) {
        }
        sw.isChecked = initial
        sw.setOnCheckedChangeListener { _, checked ->
                if (syncingSwitches) return@setOnCheckedChangeListener   // 程序化同步，不是用户操作
            Prefs.sp(this).edit().putBoolean(key, checked).apply()
            when (key) {
                Prefs.KEY_STORE_GUARD -> applyStoreGuard(checked, silent = false)
                Prefs.KEY_SCOPE_BAIDU, Prefs.KEY_SCOPE_OTHER, Prefs.KEY_SCOPE_PDD -> {
                    // 作用范围变了必须重建隧道（addAllowedApplication 只在建立时生效）
                    if (AdBlockVpnService.running) restartTunnel()
                    toast("作用范围已改为：${Prefs.scopeLabel(this)}（正在重启拦截以应用）")
                }
                  Prefs.KEY_A11Y_ONLY -> {
                      if (checked) {
                          // 仅无障碍模式：**完全停掉 VPN** —— 名额让出来，也避免被"检测反广告 VPN"的 App 识别
                          if (AdBlockVpnService.running) AdBlockVpnService.stop(this)
                          toast("仅无障碍模式已开启：VPN 已停掉，只靠无障碍跳广告（抗「检测反广告 App」）")
                      } else {
                          toast("仅无障碍模式已关闭")
                      }
                      refreshStatus()
                  }
                  "bg_visible" -> {
                      Prefs.sp(this).edit().putBoolean(Prefs.KEY_BG_HIDE, !checked).apply()
                      syncingSwitches = true
                      bgHideSwitch.isChecked = !checked
                      syncingSwitches = false
                      applyRecentsVisibility()   // ★ 必须在写 pref 之后调用，否则用的还是旧值
                      toast(if (checked) "后台可见：可在多任务里看到 disAD" else "已改为后台隐藏：不可在多任务里看到")
                  }
                  "bg_hide" -> {
                      Prefs.sp(this).edit().putBoolean(Prefs.KEY_BG_HIDE, checked).apply()
                      syncingSwitches = true
                      bgVisibleSwitch.isChecked = !checked
                      syncingSwitches = false
                      applyRecentsVisibility()
                      toast(if (checked) "后台隐藏：不可在多任务里看到 disAD（通知仍常驻）" else "已改为后台可见：可在多任务里看到")
                  }
                  Prefs.KEY_OCR_FALLBACK -> {
                      toast(
                          if (checked) "OCR 兜底已开启：只对「疑似开屏」生效，误点风险低但会变慢"
                          else "OCR 兜底已关闭"
                      )
                  }
                  Prefs.KEY_SKIP_TOAST -> toast(
                      if (checked) "以后每跳过一个广告都会提示" else "已关闭跳过提示"
                  )
                Prefs.KEY_AUTO_SKIP -> {
                    if (checked) {
                        if (!isAccessibilityEnabled()) {
                            toast("请在系统列表里找到「disAD」并打开开关")
                            openAccessibilitySettings()
                        } else {
                            toast("无障碍自动跳过已生效")
                        }
                    }
                }
            }
            refreshStatus()
        }
        row.addView(col)
        row.addView(sw)
        parent.addView(row, matchWrap(topMargin = 10))
        return sw
    }

    private fun smallButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(cPrimary)
            background = rounded(cSoft, 10)
            setOnClickListener { onClick() }
        }

    private fun primaryButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.WHITE)
            background = rounded(cPrimary, 12)
            setOnClickListener { onClick() }
        }

    /** 一行按钮：给两个就并排，只给一个就占满整行 */
    private fun buttonRow(parent: LinearLayout, left: Button, right: Button? = null) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (right == null) {
            row.addView(left, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)
            ))
        } else {
            val lp = LinearLayout.LayoutParams(0, dp(42), 1f)
            lp.rightMargin = dp(4)
            val rp = LinearLayout.LayoutParams(0, dp(42), 1f)
            rp.leftMargin = dp(4)
            row.addView(left, lp)
            row.addView(right, rp)
        }
        parent.addView(row, matchWrap(topMargin = 8))
    }

    private fun editRow(
        parent: LinearLayout,
        hint: String,
        initial: String,
        minLines: Int = 1
    ): EditText {
        val et = EditText(this)
        et.hint = hint
        et.setText(initial)
        et.minLines = minLines
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        et.setTextColor(cText)
        parent.addView(et)
        return et
    }

    private fun matchWrap(topMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { this.topMargin = dp(topMargin) }

    private fun matchHeight(heightDp: Int, topMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(heightDp)
        ).apply { this.topMargin = dp(topMargin) }

    private fun weightParams(weight: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            weight
        )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
