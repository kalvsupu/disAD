package com.baidukiller.lite

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
import com.baidukiller.lite.dns.BlockList
import com.baidukiller.lite.vpn.AdBlockVpnService

/**
 * ============================================================================
 *  设置界面（全部代码构建，不依赖布局文件与第三方库）
 * ============================================================================
 *  界面分五块，从"看一眼就知道状态"到"偶尔才动一次"依次排列：
 *
 *    ① 顶部标题
 *    ② 状态卡片 —— 运行状态 / 两个大数字（DNS 拦截、无障碍跳过）/ 无障碍是否生效
 *    ③ 启动·停止大按钮（唯一的主操作）
 *    ④ 开关卡片 —— 7 个开关，一行标题 + 一行说明，**拨动即时生效**
 *    ⑤ 维护工具 —— 查看记录 / 分析漏网域名 / 撤销自动加入 / 清空统计 / 无障碍设置 / 电池白名单
 *    ⑥ 拦截规则 —— 上游 DNS、自定义域名、白名单、额外包名，改完点一次保存
 *    ⑦ 使用说明（长文本收进弹窗，不再占满整屏）
 * ============================================================================
 */
class MainActivity : Activity() {

    private companion object {
        const val REQ_VPN = 1001
        const val REQ_NOTIF = 1002
        const val REQ_SAVE_LOG = 1003
    }

    // ---------------- 配色 ----------------
    private val cPrimary = Color.parseColor("#1E63D8")
    private val cDanger = Color.parseColor("#D9363E")
    private val cOk = Color.parseColor("#0F9D58")
    private val cText = Color.parseColor("#1F2329")
    private val cSubText = Color.parseColor("#6B7280")
    private val cCard = Color.WHITE
    private val cBg = Color.parseColor("#F2F4F7")
    private val cLine = Color.parseColor("#14000000")
    private val cSoft = Color.parseColor("#EDF2FC")

    private lateinit var mainButton: Button
    private lateinit var statusTitleView: TextView
    private lateinit var statBlockedView: TextView
    private lateinit var statSkippedView: TextView
    private lateinit var statusDetailView: TextView
    private lateinit var accHintView: TextView
    private lateinit var recentView: TextView
    private lateinit var footerView: TextView

    private lateinit var scopeBaiduSwitch: Switch
    private lateinit var scopeOtherSwitch: Switch
    private lateinit var scopePddSwitch: Switch
    private lateinit var elderSwitch: Switch
    private lateinit var autoSkipSwitch: Switch
    private lateinit var autoLearnSwitch: Switch
    private lateinit var storeSwitch: Switch
    private lateinit var bootSwitch: Switch

    private lateinit var upstreamEdit: EditText
    private lateinit var upstream2Edit: EditText
    private lateinit var customEdit: EditText
    private lateinit var whiteEdit: EditText
    private lateinit var extraPkgEdit: EditText
    private lateinit var testEdit: EditText
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
    // ==================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 作用范围的一次性迁移（静默执行，见 Prefs.migrateScopeOnce 的说明）
        Prefs.migrateScopeOnce(this)
        setContentView(buildUi())
        loadPrefsToUi()
        refreshRuleOverview()
        applyStoreGuard(Prefs.storeGuard(this), silent = true)
        requestNotificationPermissionIfNeeded()
        ensureDisclaimer()
        notifyIfJustUpdated()
    }

    /**
     * 刚更新完的一次性提示。
     *
     * 实测：覆盖安装之后，系统偶尔会把无障碍服务的绑定弄成半死状态
     * （第一次启用报错、要重启手机才恢复）。这不是本 App 能修的，
     * 但至少要让用户知道"该做什么"，而不是以为功能坏了。
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
            AlertDialog.Builder(this)
                .setTitle("无障碍异常怎么办")
                .setMessage("如果开启无障碍异常，彻底关闭软件重开，或重启手机。")
                .setPositiveButton("知道了", null)
                .show()
        } catch (_: Throwable) {
        }
    }

    /**
     * 首次使用声明。
     * 只有「同意并继续」和「拒绝并退出」两个选项，没有"稍后再说"——
     * 拒绝就等于不使用本软件，直接退出。
     */
    private fun ensureDisclaimer() {
        if (Prefs.disclaimerAccepted(this)) return
        val dialog = AlertDialog.Builder(this)
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
        super.onResume()
        handler.post(ticker)
        maybePromptAccessibility()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    /**
     * 无障碍服务被系统关掉时，回到 App 就提示一次并直接跳到系统设置。
     * 只在「拦截已经在运行」时才提示，避免新用户第一次打开就被拉去设置页。
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
    // ==================================================================
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
     * VPN 授权没拿到时的排查指引。
     *
     * 背景：部分国产系统（小米/红米最典型）会拦掉 VPN 授权弹窗，
     * 用户看到的现象是"点了启动拦截什么也没发生"，而且没有任何报错。
     * 这里把可自助排查的几种情况一次性列清楚。
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
            AlertDialog.Builder(this)
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
    //  说明：界面节点结构导出已迁移到独立的「维护工具」App
    //  （那边有同样的两步式采集/导出，且只在手动开启时工作；
    //    面向用户的 disAD 不再包含任何调试入口）
    // ==================================================================

    private fun onApplyClick() {
        savePrefsFromUi(showToast = true)
        applyStoreGuard(Prefs.storeGuard(this), silent = false)
        refreshRuleOverview()
        if (AdBlockVpnService.running) {
            restartTunnel()
            toast("设置已保存，正在重新加载规则…")
        }
    }

    /** 作用范围（哪些 App 走 VPN）只能在建立隧道时确定，所以要重启隧道才能生效 */
    private fun restartTunnel() {
        AdBlockVpnService.stop(this)
        handler.postDelayed({ AdBlockVpnService.start(this) }, 600)
    }

    // ==================================================================
    //  状态刷新
    // ==================================================================
    private fun refreshStatus() {
        val running = AdBlockVpnService.running

        mainButton.text = if (running) "停止拦截" else "启动拦截"
        mainButton.background = rounded(if (running) cDanger else cPrimary, 12)

        statusTitleView.text = if (running) "● 拦截运行中" else "○ 拦截已停止"
        statusTitleView.setTextColor(if (running) cOk else cSubText)

        statBlockedView.text = BlockStats.blockedCount().toString()
        statSkippedView.text = SkipAdAccessibilityService.skipCount.toString()

        val accState = when {
            !Prefs.autoSkip(this) -> "已关闭（开关）"
            !AdBlockVpnService.running -> "已暂停（拦截未运行）"
            SkipAdAccessibilityService.running -> "已生效"
            else -> "未授权 / 被系统关闭"
        }
        if (accState.startsWith("未授权")) {
            accHintView.visibility = View.VISIBLE
        } else {
            accHintView.visibility = View.GONE
        }
        statusDetailView.text = buildString {
            append("拦截模式：").append(Prefs.scopeLabel(this@MainActivity)).append('\n')
            append("无障碍辅助：").append(accState).append('\n')
            append("最近动作：").append(SkipAdAccessibilityService.lastAction)
        }
        statusDetailView.setTextColor(if (accState == "已生效") cOk else cSubText)

        val recent = BlockStats.recentBlocked()
        recentView.text = if (recent.isEmpty()) {
            "最近拦截：—"
        } else {
            "最近拦截：" + recent.distinct().take(3).joinToString("、")
        }

        val autoCount = AutoLearn.autoAddedCount(this)
        footerView.text = buildString {
            append("v1.18.1 · 已记录 ").append(DnsLog.totalSessions).append(" 次启动")
            if (autoCount > 0) append(" · 自动学习加入 ").append(autoCount).append(" 个域名")
        }
    }

    // ==================================================================
    //  设置读写
    // ==================================================================
    private fun loadPrefsToUi() {
        scopeBaiduSwitch.isChecked = Prefs.scopeBaidu(this)
        scopeOtherSwitch.isChecked = Prefs.scopeOther(this)
        scopePddSwitch.isChecked = Prefs.scopePdd(this)
        autoSkipSwitch.isChecked = Prefs.autoSkip(this)
        autoLearnSwitch.isChecked = Prefs.autoLearn(this)
        storeSwitch.isChecked = Prefs.storeGuard(this)
        bootSwitch.isChecked = Prefs.bootAutoStart(this)
        elderSwitch.isChecked = Prefs.elderMode(this)

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
            .putBoolean(Prefs.KEY_AUTO_SKIP, autoSkipSwitch.isChecked)
            .putBoolean(Prefs.KEY_AUTO_LEARN, autoLearnSwitch.isChecked)
            .putBoolean(Prefs.KEY_BOOT, bootSwitch.isChecked)
            .putBoolean(Prefs.KEY_STORE_GUARD, storeSwitch.isChecked)
            .putBoolean(Prefs.KEY_ELDER_MODE, elderSwitch.isChecked)
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
    // ==================================================================
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
            AlertDialog.Builder(this)
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
    // ==================================================================
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
            AlertDialog.Builder(this)
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
    // ==================================================================
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
        val list = AutoLearn.autoAddedList(this)
        val text = if (list.isEmpty()) {
            "还没有自动学习加入的域名。\n\n" +
                "打开「自动学习漏网域名」后，程序会在每次 App 启动之间，" +
                "把「极少出现 + 带广告特征」的域名加到这里。" +
                "百度自家的域名（baidu.com / bdstatic.com / bdimg.com）已被排除，不会误加。"
        } else {
            "共 ${list.size} 个（按字母排序）\n\n" + list.joinToString("\n")
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
            AlertDialog.Builder(this)
                .setTitle("自动学习加入的域名")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .setNeutralButton("全部清空") { _, _ -> clearAutoAdded() }
                .show()
        } catch (t: Throwable) {
            toast("打开失败：${t.message}")
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
            AlertDialog.Builder(this)
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

    // ==================================================================
    //  使用说明：每个板块是一个可点的小按钮，点开才看完整说明
    // ==================================================================
    private fun showHelpSection(sec: HelpSection) {
        try {
            AlertDialog.Builder(this)
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
    // ==================================================================
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
            background = rounded(0xFFFFF4E5.toInt(), 14)
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
        root.addView(mainButton, matchHeight(52, topMargin = 12))

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

        val switchCard2 = card(root, "其它开关")
        autoSkipSwitch = switchRow(
            switchCard2, Prefs.KEY_AUTO_SKIP,
            "无障碍辅助",
            "",
            Prefs.autoSkip(this)
        )
        elderSwitch = switchRow(
            switchCard2, Prefs.KEY_ELDER_MODE,
            "老年人模式",
            "",
            Prefs.elderMode(this)
        )
        autoLearnSwitch = switchRow(
            switchCard2, Prefs.KEY_AUTO_LEARN,
            "自动学习漏网域名",
            "",
            Prefs.autoLearn(this)
        )
        storeSwitch = switchRow(
            switchCard2, Prefs.KEY_STORE_GUARD,
            "拦截应用商店跳转",
            "",
            Prefs.storeGuard(this)
        )
        bootSwitch = switchRow(
            switchCard2, Prefs.KEY_BOOT,
            "开机自动启动",
            "",
            Prefs.bootAutoStart(this)
        )
        // 「记录全部域名」已并入常态：域名记录是「查看域名记录」和「自动学习」共同的
        // 数据来源，做成开关只会让用户不小心关掉、然后在别处看到一片空白。

        // ---------- ⑤ 维护工具 ----------
        val toolCard = card(root, "维护工具")
        buttonRow(
            toolCard,
            smallButton("查看域名记录") { showDnsLog() },
            smallButton("分析漏网域名") { showCandidates() }
        )
        buttonRow(
            toolCard,
            smallButton("清空统计") {
                BlockStats.reset()
                refreshStatus()
            },
            smallButton("无障碍设置") { openAccessibilitySettings() }
        )
        buttonRow(
            toolCard,
            smallButton("电池白名单") { requestIgnoreBatteryOptimization() }
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
            "自动学习加入的域名",
            "程序自己加的域名独立存放，不会混进上面的输入框",
            false
        )
        autoAddedView = label("自动学习加入：0 个", 13f, "#1F2329", false)
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
            addView(root)
        }
    }

    // ---------------- 界面小工具 ----------------
    /**
     * 可折叠板块：点标题展开 / 收起，返回内容容器。
     * 维护工具里的「拦截规则」「自动学习加入」「使用说明」都用它。
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
            setTextColor(Color.parseColor(color))
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
        sw.isChecked = initial
        sw.setOnCheckedChangeListener { _, checked ->
            Prefs.sp(this).edit().putBoolean(key, checked).apply()
            when (key) {
                Prefs.KEY_STORE_GUARD -> applyStoreGuard(checked, silent = false)
                Prefs.KEY_SCOPE_BAIDU, Prefs.KEY_SCOPE_OTHER, Prefs.KEY_SCOPE_PDD -> {
                    // 作用范围变了必须重建隧道（addAllowedApplication 只在建立时生效）
                    if (AdBlockVpnService.running) restartTunnel()
                    toast("作用范围已改为：${Prefs.scopeLabel(this)}（正在重启拦截以应用）")
                }
                Prefs.KEY_ELDER_MODE -> {
                    if (AdBlockVpnService.running) restartTunnel()
                    toast(
                        if (checked) "老年人模式已开启：所有 App 的广告与弹窗都会被自动关闭"
                        else "老年人模式已关闭"
                    )
                }
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
