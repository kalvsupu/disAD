package com.kalvsupu.disad

import android.content.Context
import android.content.SharedPreferences

/**
 */
object Prefs {

    const val NAME = "bk_lite"

    // ---------------- 作用范围（三个系别） ----------------
    const val KEY_SCOPE_BAIDU = "scope_baidu"
    const val KEY_SCOPE_OTHER = "scope_other"
    const val KEY_SCOPE_PDD = "scope_pdd"

    // ---------------- 其它开关 ----------------
    const val KEY_BOOT = "boot"
    const val KEY_UPSTREAM = "upstream"
    const val KEY_UPSTREAM2 = "upstream2"
    const val KEY_CUSTOM = "custom"
    const val KEY_WHITELIST = "whitelist"
    const val KEY_EXTRA_PKGS = "extra_pkgs"
    const val KEY_STORE_GUARD = "store_guard"
    const val KEY_LOG_ALL = "log_all"
    const val KEY_AUTO_SKIP = "auto_skip"
    const val KEY_AUTO_LEARN = "auto_learn"
    const val KEY_AUTO_ADDED = "auto_added"

    /** AI 审查加入的域名元数据：每行 `域名|时间戳|manual 或 auto` */
    const val KEY_AI_META = "ai_added_meta"

    /** 仅无障碍模式：不开 VPN，只靠无障碍点跳过（可与梯子/其它 VPN 共存） */
    const val KEY_A11Y_ONLY = "a11y_only"

    /** 每次跳过成功弹一个小提示 */
    const val KEY_SKIP_TOAST = "skip_toast"

    /** 深色模式：0=跟随系统，1=浅色，2=深色 */
    const val KEY_DARK_STATE = "dark_mode_state"

    /** 旧版布尔键（仅用于迁移） */
    private const val KEY_DARK_OLD = "dark_mode"
    /** 前台可见 / 后台隐藏：切到后台时把常驻通知撤掉 */
    const val KEY_BG_HIDE = "bg_hide_notify"

    /** OCR 兜底（实验）：无障碍规则全落空时，截图 + 本地识别找「跳过」 */
    const val KEY_OCR_FALLBACK = "ocr_fallback"

    // ---------------- AI 审查（用户自填 API） ----------------
    const val KEY_API_BASE = "api_base"
    const val KEY_API_KEY = "api_key"
    const val KEY_API_MODEL = "api_model"
    /** 每 N 次会话审查一次（默认 5） */
    const val KEY_API_EVERY = "api_every"
    /** 累计拦截域名达到这个数时审查一次（默认 10000） */
    const val KEY_API_THRESHOLD = "api_threshold"
    const val KEY_API_STATUS = "api_status"
    const val KEY_API_LAST_AT = "api_last_at"
    const val KEY_API_LAST_SESSION = "api_last_session"
    const val KEY_API_LAST_BLOCKED = "api_last_blocked"

    /** 累计拦截 / 放行次数（跨重启保留，AI 审查阈值和主界面统计都用它） */
    const val KEY_TOTAL_BLOCKED = "total_blocked"
    const val KEY_TOTAL_ALLOWED = "total_allowed"

    const val DEFAULT_API_BASE = "https://api.deepseek.com"
    const val DEFAULT_API_MODEL = "deepseek-chat"
    const val KEY_ELDER_MODE = "elder_mode"
    const val KEY_DISCLAIMER = "disclaimer_ok"
    const val KEY_LAST_VERSION = "last_version_code"
    const val KEY_SCOPE_MIGRATED = "scope_migrated_v2"

    const val DEFAULT_UPSTREAM = "223.5.5.5"
    const val DEFAULT_UPSTREAM2 = "119.29.29.29"

    /** 百度系 App */
    val BAIDU_PACKAGES: List<String> = listOf(
        "com.baidu.BaiduMap",
        "com.baidu.BaiduMap.pad",
        "com.baidu.netdisk",
        "com.baidu.tieba",
        "com.baidu.searchbox",
        "com.baidu.searchbox.lite",
        "com.baidu.haokan",
        "com.baidu.homework",
        "com.baidu.yuedu",
        "com.baidu.vvideo"
    )

    /**
     */
    val PDD_PACKAGES: List<String> = listOf(
        "com.xunmeng.pinduoduo",
        "com.xunmeng.pinduoduo.lite",
        "com.duoduo.child.story"
    )

    fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // ---------------- 系别 ----------------
    fun scopeBaidu(ctx: Context) = sp(ctx).getBoolean(KEY_SCOPE_BAIDU, true)

    /**
     */
    fun scopeOther(ctx: Context): Boolean {
        val sp = sp(ctx)
        if (!sp.contains(KEY_SCOPE_OTHER) && !sp.contains(KEY_SCOPE_BAIDU)) return true
        return sp.getBoolean(KEY_SCOPE_OTHER, false)
    }

    fun scopePdd(ctx: Context) = sp(ctx).getBoolean(KEY_SCOPE_PDD, false)

    /** 仅无障碍模式（默认关） */
    fun a11yOnly(ctx: Context) = sp(ctx).getBoolean(KEY_A11Y_ONLY, false)

    /** 跳过成功提示（默认开：用户点掉广告后有个"确实干活了"的反馈） */
    fun skipToast(ctx: Context) = sp(ctx).getBoolean(KEY_SKIP_TOAST, true)

    /** 0=跟随系统，1=浅色，2=深色 */
    fun darkModeState(ctx: Context): Int {
        val sp = sp(ctx)
        if (!sp.contains(KEY_DARK_STATE)) {
            // 迁移：旧版只有一个布尔开关（开=深色，关=以前一直浅色 → 现在交给系统）
            return if (sp.getBoolean(KEY_DARK_OLD, false)) 2 else 0
        }
        return sp.getInt(KEY_DARK_STATE, 0)
    }

    fun setDarkModeState(ctx: Context, v: Int) =
        sp(ctx).edit().putInt(KEY_DARK_STATE, v).apply()

    /** 后台隐藏（默认 true = 多任务里看不到） */
    fun bgHide(ctx: Context) = sp(ctx).getBoolean(KEY_BG_HIDE, true)

    /** OCR 兜底开关（默认关：实验功能，开了会多耗电/内存） */
    fun ocrFallback(ctx: Context) = sp(ctx).getBoolean(KEY_OCR_FALLBACK, true)

    // ---------------- AI 审查 ----------------
    fun apiBase(ctx: Context) = sp(ctx).getString(KEY_API_BASE, DEFAULT_API_BASE) ?: DEFAULT_API_BASE
    fun apiKey(ctx: Context) = sp(ctx).getString(KEY_API_KEY, "") ?: ""
    fun apiModel(ctx: Context) = sp(ctx).getString(KEY_API_MODEL, DEFAULT_API_MODEL) ?: DEFAULT_API_MODEL
    fun apiEvery(ctx: Context) = sp(ctx).getInt(KEY_API_EVERY, 5)
    fun apiThreshold(ctx: Context) = sp(ctx).getInt(KEY_API_THRESHOLD, 10000)
    fun apiStatus(ctx: Context) = sp(ctx).getString(KEY_API_STATUS, "") ?: ""
    fun setApiStatus(ctx: Context, v: String) =
        sp(ctx).edit().putString(KEY_API_STATUS, v).apply()

    fun apiLastSession(ctx: Context) = sp(ctx).getInt(KEY_API_LAST_SESSION, -1)
    fun setApiLastSession(ctx: Context, v: Int) =
        sp(ctx).edit().putInt(KEY_API_LAST_SESSION, v).apply()

    fun apiLastBlocked(ctx: Context) = sp(ctx).getInt(KEY_API_LAST_BLOCKED, 0)
    fun setApiLastBlocked(ctx: Context, v: Int) =
        sp(ctx).edit().putInt(KEY_API_LAST_BLOCKED, v).apply()

    fun apiLastReviewAt(ctx: Context) = sp(ctx).getLong(KEY_API_LAST_AT, 0L)

    fun setApiLastReviewAt(ctx: Context, v: Long) =
        sp(ctx).edit().putLong(KEY_API_LAST_AT, v).apply()

    fun totalBlocked(ctx: Context) = sp(ctx).getLong(KEY_TOTAL_BLOCKED, 0L)

    /** sync=true 用 commit：进程可能马上被杀，apply 的异步写入会丢 */
    fun setTotalBlocked(ctx: Context, v: Long, sync: Boolean = false) {
        val e = sp(ctx).edit().putLong(KEY_TOTAL_BLOCKED, v)
        if (sync) e.commit() else e.apply()
    }

    fun totalAllowed(ctx: Context) = sp(ctx).getLong(KEY_TOTAL_ALLOWED, 0L)

    fun setTotalAllowed(ctx: Context, v: Long, sync: Boolean = false) {
        val e = sp(ctx).edit().putLong(KEY_TOTAL_ALLOWED, v)
        if (sync) e.commit() else e.apply()
    }

    /** 上次运行时的版本号（用来判断"刚更新完"，提示无障碍可能需要重新启用） */
    fun lastVersion(ctx: Context) = sp(ctx).getInt(KEY_LAST_VERSION, 0)

    fun setLastVersion(ctx: Context, code: Int) {
        sp(ctx).edit().putInt(KEY_LAST_VERSION, code).apply()
    }

    /**
     */
    /**
     * 作用范围的一次性迁移。
     *
     * ★ 只在"三个范围键完全不存在"（真正的新装/极老版本）时才写默认值；
     *   用户明确关掉过的状态必须原样保留，否则会把"限定范围"悄悄改成"全局"。
     */
    fun migrateScopeOnce(ctx: Context): Boolean {
        val sp = sp(ctx)
        if (sp.getBoolean(KEY_SCOPE_MIGRATED, false)) return false
        sp.edit().putBoolean(KEY_SCOPE_MIGRATED, true).apply()

        val hasBaidu = sp.contains(KEY_SCOPE_BAIDU)
        val hasOther = sp.contains(KEY_SCOPE_OTHER)
        val hasPdd = sp.contains(KEY_SCOPE_PDD)
        if (hasBaidu || hasOther || hasPdd) return false     // 用户已有明确设置 → 不动
        sp.edit()
            .putBoolean(KEY_SCOPE_BAIDU, true)
            .putBoolean(KEY_SCOPE_OTHER, false)
            .putBoolean(KEY_SCOPE_PDD, false)
            .apply()
        return true
    }

    /** 当前作用范围的文字描述（通知栏 / 状态卡片用） */
    fun scopeLabel(ctx: Context): String {
        if (scopeOther(ctx)) {
            return if (scopePdd(ctx)) "全部 App + 拼多多强化" else "全部 App"
        }
        val parts = ArrayList<String>(2)
        if (scopeBaidu(ctx)) parts.add("百度系")
        if (scopePdd(ctx)) parts.add("拼多多强化")
        return if (parts.isEmpty()) "未选择任何范围" else parts.joinToString(" + ")
    }

    /**
     */
    fun scopePackages(ctx: Context): List<String> {
        if (scopeOther(ctx)) return emptyList()          // 全局
        val out = LinkedHashSet<String>()
        if (scopeBaidu(ctx)) out.addAll(BAIDU_PACKAGES)
        if (scopePdd(ctx)) out.addAll(PDD_PACKAGES)
        parseList(extraPackages(ctx)).forEach { if (it.contains('.')) out.add(it) }
        return out.toList()
    }

    /** 无障碍服务是否应该在这个 App 上工作（与 DNS 范围保持一致） */
    fun inScope(ctx: Context, pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        if (pkg == ctx.packageName) return false
        if (elderMode(ctx)) return true                 // 老年人模式：全部 App
        if (scopeOther(ctx)) return true
        if (scopeBaidu(ctx) && pkg.startsWith("com.baidu.")) return true
        if (scopePdd(ctx) && PDD_PACKAGES.contains(pkg)) return true
        return parseList(extraPackages(ctx)).contains(pkg)
    }

    // ---------------- 其它 ----------------
    fun bootAutoStart(ctx: Context) = sp(ctx).getBoolean(KEY_BOOT, false)
    fun storeGuard(ctx: Context) = sp(ctx).getBoolean(KEY_STORE_GUARD, true)
    fun upstream(ctx: Context) = sp(ctx).getString(KEY_UPSTREAM, DEFAULT_UPSTREAM) ?: DEFAULT_UPSTREAM
    fun upstream2(ctx: Context) = sp(ctx).getString(KEY_UPSTREAM2, DEFAULT_UPSTREAM2) ?: DEFAULT_UPSTREAM2
    fun customDomains(ctx: Context) = sp(ctx).getString(KEY_CUSTOM, "") ?: ""
    fun whitelist(ctx: Context) = sp(ctx).getString(KEY_WHITELIST, "") ?: ""
    fun extraPackages(ctx: Context) = sp(ctx).getString(KEY_EXTRA_PKGS, "") ?: ""
    fun logAll(ctx: Context) = sp(ctx).getBoolean(KEY_LOG_ALL, true)
    fun autoSkip(ctx: Context) = sp(ctx).getBoolean(KEY_AUTO_SKIP, true)
    fun autoLearn(ctx: Context) = sp(ctx).getBoolean(KEY_AUTO_LEARN, true)
    fun elderMode(ctx: Context) = sp(ctx).getBoolean(KEY_ELDER_MODE, false)
    fun autoAdded(ctx: Context) = sp(ctx).getString(KEY_AUTO_ADDED, "") ?: ""
    fun disclaimerAccepted(ctx: Context) = sp(ctx).getBoolean(KEY_DISCLAIMER, false)

    fun acceptDisclaimer(ctx: Context) {
        sp(ctx).edit().putBoolean(KEY_DISCLAIMER, true).apply()
    }

    /** 读取字符串型配置（供 AutoLearn 使用） */
    fun getString(sp: SharedPreferences, key: String, def: String = ""): String =
        sp.getString(key, def) ?: def

    /** 把用户输入的逗号/换行/分号分隔内容拆成列表 */
    fun parseList(raw: String): List<String> =
        raw.split(',', '\n', '\r', ';', ' ', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
