package com.baidukiller.lite

import android.content.Context
import android.content.SharedPreferences

/**
 * ============================================================================
 *  设置项（全部保存在本机私有 SharedPreferences，不联网、不上传）
 * ============================================================================
 *  作用范围改成「三个系别」，可以多选：
 *    · 百度系   —— 百度地图 / 网盘 / 贴吧 等（默认开）
 *    · 其他     —— 淘宝、抖音以及其它 App（默认关；开了等于全局）
 *    · 拼多多系 —— 拼多多相关 App 与拼多多的广告素材域名（默认关）
 * ============================================================================
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
     * 拼多多系 App。
     * 注意：拼多多的广告素材大量出现在**别的 App** 里（投放素材 CDN），
     * 所以「拼多多系」开关除了把拼多多 App 纳入作用范围，还会启用拼多多素材域名规则组。
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
     * 「其他」（= 全局）。
     *
     * ★ 这里做了一次升级迁移：**偏好里完全没有"作用范围"记录**，说明是从
     *   还没有三个系别开关的旧版本（v1.10.x 及更早）升上来的 —— 那种情况默认**打开**。
     *   实测教训：从旧版「百度杀手」覆盖升级后，作用范围回落到"只有百度系"，
     *   用户看到的现象就是"QQ 音乐、B站的广告一个都没关掉"，还以为是功能坏了。
     *   用户装去广告软件的预期本来就是"全都能拦"，要缩小范围让他自己去关。
     */
    fun scopeOther(ctx: Context): Boolean {
        val sp = sp(ctx)
        if (!sp.contains(KEY_SCOPE_OTHER) && !sp.contains(KEY_SCOPE_BAIDU)) return true
        return sp.getBoolean(KEY_SCOPE_OTHER, false)
    }

    fun scopePdd(ctx: Context) = sp(ctx).getBoolean(KEY_SCOPE_PDD, false)

    /** 上次运行时的版本号（用来判断"刚更新完"，提示无障碍可能需要重新启用） */
    fun lastVersion(ctx: Context) = sp(ctx).getInt(KEY_LAST_VERSION, 0)

    fun setLastVersion(ctx: Context, code: Int) {
        sp(ctx).edit().putInt(KEY_LAST_VERSION, code).apply()
    }

    /**
     * 作用范围的一次性迁移（★ 必须做，否则用户会以为功能坏了）。
     *
     * 背景：早期版本没有「其他（= 全局）」这个开关，默认只拦百度系。
     * 用户第一次点「启动拦截」时，界面上的开关状态会被整体写回偏好 ——
     * 于是"其他 = 关"被当成**用户的选择**存了下来。
     * 结果就是从旧版升上来的用户，QQ音乐、B站这些根本不在作用范围里，
     * 表现就是"一个广告都跳不过"，而程序认为自己一切正常。
     *
     * 处理：只在**从未迁移过**时，把「其他」打开一次，并如实告诉用户可以改回去。
     * @return true 表示这次确实改动了（调用方据此弹一次说明）
     */
    fun migrateScopeOnce(ctx: Context): Boolean {
        val sp = sp(ctx)
        if (sp.getBoolean(KEY_SCOPE_MIGRATED, false)) return false
        sp.edit().putBoolean(KEY_SCOPE_MIGRATED, true).apply()
        if (sp.getBoolean(KEY_SCOPE_OTHER, false)) return false
        if (sp.getBoolean(KEY_SCOPE_PDD, false)) return false
        sp.edit().putBoolean(KEY_SCOPE_OTHER, true).apply()
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
     * 真正要放进 VPN 作用范围的包名清单。
     * 返回空列表 = 全局（所有 App 都走本模块）。
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
    fun storeGuard(ctx: Context) = sp(ctx).getBoolean(KEY_STORE_GUARD, false)
    fun upstream(ctx: Context) = sp(ctx).getString(KEY_UPSTREAM, DEFAULT_UPSTREAM) ?: DEFAULT_UPSTREAM
    fun upstream2(ctx: Context) = sp(ctx).getString(KEY_UPSTREAM2, DEFAULT_UPSTREAM2) ?: DEFAULT_UPSTREAM2
    fun customDomains(ctx: Context) = sp(ctx).getString(KEY_CUSTOM, "") ?: ""
    fun whitelist(ctx: Context) = sp(ctx).getString(KEY_WHITELIST, "") ?: ""
    fun extraPackages(ctx: Context) = sp(ctx).getString(KEY_EXTRA_PKGS, "") ?: ""
    fun logAll(ctx: Context) = sp(ctx).getBoolean(KEY_LOG_ALL, true)
    fun autoSkip(ctx: Context) = sp(ctx).getBoolean(KEY_AUTO_SKIP, true)
    fun autoLearn(ctx: Context) = sp(ctx).getBoolean(KEY_AUTO_LEARN, false)
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
