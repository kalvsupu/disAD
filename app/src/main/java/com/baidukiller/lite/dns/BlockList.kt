package com.baidukiller.lite.dns

import android.content.Context
import android.util.Log
import com.baidukiller.lite.Prefs
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * ============================================================================
 *  拦截规则表
 * ============================================================================
 *  规则来源（全部常驻，不再有"严格模式开关"）：
 *    assets/blocklist_default.txt   百度自家广告 + 实测抓到的第三方广告平台
 *    assets/blocklist_strict.txt    更激进的通用广告端点（原"严格模式"，现已常驻）
 *    assets/blocklist_pdd.txt       拼多多系素材域名（由「拼多多系」开关控制）
 *    用户自定义域名（设置里手填）
 *    自动学习加入的域名（独立存放，不写进用户输入框）
 *    关键字规则（全部常驻）
 *
 *  匹配方式：域名本身 或 其任意子域；白名单优先，命中即放行。
 * ============================================================================
 */
class BlockList {

    private companion object {
        const val TAG = "disAD"
    }

    private val domains: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val whiteList: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val customDomains: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val autoDomains: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val pddDomains: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    // ---------------- 统计（界面"规则总览"用） ----------------
    @Volatile var countDefault: Int = 0; private set
    @Volatile var countStrict: Int = 0; private set
    @Volatile var countPdd: Int = 0; private set
    @Volatile var countCustom: Int = 0; private set
    @Volatile var countAuto: Int = 0; private set
    @Volatile var countWhite: Int = 0; private set

    val countKeyword: Int
        get() = KEYWORDS_LABEL.size + KEYWORDS_EXACT.size + KEYWORDS_DOTS.size

    val size: Int get() = domains.size

    // ==================================================================
    //  加载
    // ==================================================================
    fun load(
        ctx: Context,
        customRaw: String,
        whiteRaw: String,
        autoRaw: String,
        includePdd: Boolean
    ) {
        val defaults = HashSet<String>(512)
        readAsset(ctx, "blocklist_default.txt", defaults)

        val strict = HashSet<String>(256)
        readAsset(ctx, "blocklist_strict.txt", strict)

        val pdd = HashSet<String>(64)
        if (includePdd) readAsset(ctx, "blocklist_pdd.txt", pdd)

        val custom = HashSet<String>(64)
        for (raw in Prefs.parseList(customRaw)) normalize(raw)?.let { custom.add(it) }

        val auto = HashSet<String>(256)
        for (raw in Prefs.parseList(autoRaw)) normalize(raw)?.let { auto.add(it) }

        whiteList.clear()
        for (raw in Prefs.parseList(whiteRaw)) normalize(raw)?.let { whiteList.add(it) }

        customDomains.clear()
        customDomains.addAll(custom)
        autoDomains.clear()
        autoDomains.addAll(auto)
        pddDomains.clear()
        pddDomains.addAll(pdd)

        domains.clear()
        domains.addAll(defaults)
        domains.addAll(strict)
        domains.addAll(pdd)
        domains.addAll(custom)
        domains.addAll(auto)

        countDefault = defaults.size
        countStrict = strict.size
        countPdd = pdd.size
        countCustom = custom.size
        countAuto = auto.size
        countWhite = whiteList.size

        Log.i(
            TAG,
            "规则加载：内置 $countDefault + 常驻严格 $countStrict + 拼多多 $countPdd " +
                "+ 自定义 $countCustom + 自动 $countAuto = 生效 ${domains.size} 个域名；" +
                "关键字 $countKeyword 条；白名单 $countWhite 条"
        )
    }

    // ==================================================================
    //  判定
    // ==================================================================
    /** 高频路径：不加锁、不建字符串、只做包含与后缀比较 */
    fun isBlocked(host: String): Boolean {
        if (host.isEmpty()) return false
        if (whiteList.isNotEmpty() && matchParent(whiteList, host) != null) return false
        if (matchesKeyword(host)) return true
        return matchParent(domains, host) != null
    }

    /**
     * 给"规则自测"用：返回命中的规则描述，null 表示会放行。
     * 只在界面上手动触发，不在 DNS 高频路径里调用。
     */
    fun explain(rawHost: String): String? {
        val host = rawHost.trim().lowercase()
        if (host.isEmpty()) return null

        matchParent(whiteList, host)?.let { return "白名单放行（命中「$it」）" }
        matchedKeyword(host)?.let { return "会拦截：广告关键字「$it」" }
        matchParent(domains, host)?.let { hit ->
            return when {
                autoDomains.contains(hit) -> "会拦截：自动学习加入「$hit」"
                customDomains.contains(hit) -> "会拦截：自定义「$hit」"
                pddDomains.contains(hit) -> "会拦截：拼多多系规则「$hit」"
                else -> "会拦截：内置清单「$hit」"
            }
        }
        return null
    }

    // ==================================================================
    //  内部
    // ==================================================================
    private fun matchParent(set: MutableSet<String>, host: String): String? {
        var h = host
        while (true) {
            if (set.contains(h)) return h
            val i = h.indexOf('.')
            if (i < 0 || i == h.length - 1) return null
            h = h.substring(i + 1)
        }
    }

    /**
     * 关键字匹配（v1.12.3 重写）。
     *
     * 旧实现是 host.contains(关键字)，会误伤真实域名：
     *   · "ads."   → 命中 downloads.qq.com、uploads.xxx.com
     *   · "e.qq.com" → 命中 be.qq.com
     *   · "adx"    → 命中 downloadx.com
     * 这些都是"别的 App 网络异常"的典型来源。
     *
     * 现在按 DNS 的结构来匹配：
     *   ① 带点的关键字（gdt.qq.com / v.gdt / ad.xiaomi）→ **连续标签精确匹配**，
     *      所以 v.gdt.qq.com 命中，be.qq.com 不再命中；
     *   ② 短通用词（ads / adx）→ **整个标签完全相等**，
     *      所以 x.ads.foo.com 命中，downloads.qq.com 不再命中；
     *   ③ 其余品牌词（mobads / gdtimg / adsmind …）→ **标签前缀匹配**，
     *      所以 mobads.baidu.com、qzs.gdtimg.com、pglstatp-toutiao.com 照常命中。
     */
    private fun matchesKeyword(host: String): Boolean {
        val labels = host.split('.')
        for (lab in labels) {
            for (k in KEYWORDS_LABEL) {
                if (lab.length >= k.length &&
                    lab.regionMatches(0, k, 0, k.length, ignoreCase = true)
                ) return true
            }
            for (k in KEYWORDS_EXACT) {
                if (lab.equals(k, ignoreCase = true)) return true
            }
        }
        for (parts in KEYWORDS_DOTS) {
            var i = 0
            while (i + parts.size <= labels.size) {
                var all = true
                for (j in parts.indices) {
                    if (!labels[i + j].equals(parts[j], ignoreCase = true)) {
                        all = false
                        break
                    }
                }
                if (all) return true
                i++
            }
        }
        return false
    }

    /** 规则自测用：返回命中的关键字，没命中返回 null */
    private fun matchedKeyword(host: String): String? {
        val labels = host.split('.')
        for (lab in labels) {
            for (k in KEYWORDS_LABEL) {
                if (lab.length >= k.length &&
                    lab.regionMatches(0, k, 0, k.length, ignoreCase = true)
                ) return k
            }
            for (k in KEYWORDS_EXACT) {
                if (lab.equals(k, ignoreCase = true)) return k
            }
        }
        for (parts in KEYWORDS_DOTS) {
            var i = 0
            while (i + parts.size <= labels.size) {
                var all = true
                for (j in parts.indices) {
                    if (!labels[i + j].equals(parts[j], ignoreCase = true)) {
                        all = false
                        break
                    }
                }
                if (all) return parts.joinToString(".")
                i++
            }
        }
        return null
    }

    private fun readAsset(ctx: Context, name: String, into: MutableSet<String>) {
        try {
            ctx.assets.open(name).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val text = line.substringBefore('#').trim()
                    if (text.isNotEmpty()) normalize(text)?.let { into.add(it) }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "读取规则文件失败：$name → ${t.message}")
        }
    }

    private fun normalize(raw: String): String? {
        var d = raw.trim().lowercase()
        if (d.isEmpty()) return null
        if (d.startsWith("*.")) d = d.substring(2)
        if (d.startsWith(".")) d = d.substring(1)
        if (d.endsWith(".")) d = d.dropLast(1)
        if (!d.contains('.')) return null
        if (d.contains('/') || d.contains(' ')) return null
        return d
    }

    // ==================================================================
    //  关键字表
    // ==================================================================
    /** ① 品牌词：按「标签前缀」匹配（mobads / gdtimg / adsmind …） */
    private val KEYWORDS_LABEL: List<String> = listOf(
        "mobads", "antpcdn", "nsclick", "integralwall",
        "advlion", "vlion", "mentamob", "ubixioe", "taqing",
        "adsmind", "lrtb", "beizi", "miaozhen", "gdtimg", "ugdtimg",
        "ctobsnssdk", "adintl", "igrowth", "yunxish", "66mobi",
        "advangmai", "qchannel", "ad-api",
        "pangolin", "pangle", "pglstatp",
        "kwaipros", "sigmob", "mintegral", "mbridge", "tradplus",
        "doubleclick", "googlesyndication",
        "adservice", "adsystem", "admaster", "adnxs", "criteo",
        "taboola", "outbrain", "smartadserver", "adkernel",
        "advert", "toutiao", "kwai", "bytescm"
    )

    /**
     * ② 短通用词：按「整个标签完全相等」匹配。
     *    这类词如果按包含匹配会大量误伤（downloads、adsl、adx 出现在别的词里），
     *    所以要求它就是独立的一段域名标签。
     */
    private val KEYWORDS_EXACT: List<String> = listOf("ads", "adx")

    /**
     * ③ 带点的词：按「连续标签精确匹配」。
     *    v.gdt.qq.com 命中 v.gdt；be.qq.com 不再命中 e.qq.com。
     */
    private val KEYWORDS_DOTS: List<List<String>> = listOf(
        "gdt.qq.com".split('.'),
        "v.gdt".split('.'),
        "e.qq.com".split('.'),
        "ad.qq.com".split('.'),
        "ad.xiaomi".split('.')
    )
}
