package com.kalvsupu.disad

import android.content.Context
import android.util.Log

/**
 */
object AutoLearn {

    private const val TAG = "disAD"

    /** 一次自动学习最多新增多少条 */
    private const val MAX_AUTO_ADD = 5

    /** 百度自家域名后缀：出现率低也不该被当成广告 */
    private val BAIDU_OWNED_SUFFIX = listOf(
        "baidu.com", "bdstatic.com", "bdimg.com", "baidubce.com", "baidu-int.com"
    )

    /** 命中这些词就说明"虽然是百度域名，但确实是广告" */
    private val AD_TOKENS = listOf(
        "mobads", "nsclick", "antpcdn", "cpro", "union", "adx", "ads",
        "advert", "s0-ant", "integralwall", "afd"
    )

    // ==================================================================
    //  分析
    /** 手动模式：全部候选（已排除白名单 / 已拦截 / 百度自家非广告域名） */
    fun analyze(ctx: Context, isBlocked: (String) -> Boolean): List<DnsLog.Cand> {
        if (DnsLog.totalSessions < 2) return emptyList()
        val white = Prefs.parseList(Prefs.whitelist(ctx)).map { it.lowercase() }.toSet()
        return DnsLog.candidates(isBlocked).filter { c ->
            if (white.any { w -> c.domain == w || c.domain.endsWith(".$w") }) return@filter false
            if (isProtected(c.domain)) return@filter false
            // ★ 必须自带广告特征词，否则不加。
            //   实测教训：只靠"出现率低"会把它加成一堆基础设施域名 ——
            if (!hasAdSignal(c.domain)) return@filter false
            true
        }
    }

    /**
     */
    private val PROTECTED_SUFFIX = listOf(
        // 百度系（原有）
        "baidu.com", "bdstatic.com", "bdimg.com", "baidubce.com", "baidu-int.com",
        // 腾讯系
        "qq.com", "weixin.qq.com.cn", "wechatpay.cn", "qpic.cn", "gtimg.cn", "gtimg.com",
        "tencent-cloud.net", "tencentmusic.com", "qcloud.com", "myqcloud.com",
        // 字节 / B站 / 阿里 / 网易
        "hdslb.com", "bilibili.com", "bilivideo.com", "bytednsdoc.com",
        "aliyuncs.com", "alicdn.com", "taobao.com", "tmall.com", "alipay.com",
        "163.com", "126.net", "netease.com",
        // 厂商基础设施
        "xiaomi.net", "xiaomi.com", "miui.com", "hicloud.com", "huawei.com",
        "oppo.com", "vivo.com", "sogou.com",
        // 推送 / 统计 SDK（拦了推送就废了）
        "getui.com", "getui.net", "gepush.com", "umeng.com", "umengcloud.com",
        // 明确确认过不能拦的第三方
        "lelaer.com"
    )

    /** 这个域名是不是"某个 App 的基础设施" */
    private fun isProtected(domain: String): Boolean =
        PROTECTED_SUFFIX.any { domain == it || domain.endsWith(".$it") }

    /**
     */
    private fun hasAdSignal(domain: String): Boolean =
        AD_TOKENS.any { domain.contains(it) } ||
            AD_EXTRA_TOKENS.any { domain.contains(it) }

    private val AD_EXTRA_TOKENS = listOf(
        "ad.", ".ad", "ads", "adx", "adnet", "adserv", "adsdk", "ssp.", ".ssp",
        "dsp.", "gdt", "mobads", "pangle", "pangolin", "union", "advert",
        "1rtb", "lrtb", "taqing", "zhangyuyidong", "huolala"
    )

    /** 自动模式：只要高置信度，且限制单次数量 */
    fun autoCandidates(ctx: Context, isBlocked: (String) -> Boolean): List<DnsLog.Cand> =
        analyze(ctx, isBlocked).filter { it.high }.take(MAX_AUTO_ADD)

    /** 是不是"百度自家、且看不出是广告"的域名（保留给手动分析用） */
    private fun isBaiduOwnedAndNotAd(domain: String): Boolean {
        val owned = BAIDU_OWNED_SUFFIX.any { domain == it || domain.endsWith(".$it") }
        if (!owned) return false
        return AD_TOKENS.none { domain.contains(it) }
    }

    // ==================================================================
    //  写入 / 撤销
    /**
     */
    /**
     */
    fun addDomains(
        ctx: Context,
        domains: List<String>,
        toAutoList: Boolean,
        source: String? = null
    ): Int {
        if (domains.isEmpty()) return 0
        return try {
            val sp = Prefs.sp(ctx)
            val key = if (toAutoList) Prefs.KEY_AUTO_ADDED else Prefs.KEY_CUSTOM

            val existing = Prefs.parseList(Prefs.getString(sp, key))
            val merged = LinkedHashSet<String>()
            existing.forEach { merged.add(it.trim().lowercase()) }

            var added = 0
            for (d in domains) {
                val v = d.trim().lowercase()
                if (v.isEmpty()) continue
                if (merged.add(v)) added++
            }
            if (added == 0) return 0

            sp.edit().putString(key, merged.joinToString("\n")).apply()
              // AI 来源才记元数据（域名|时间戳|manual/auto），用于分开显示
              if (source != null) {
                  val now = System.currentTimeMillis()
                  val meta = LinkedHashSet<String>()
                  Prefs.getString(sp, Prefs.KEY_AI_META).orEmpty()
                      .split('\n').forEach { if (it.isNotBlank()) meta.add(it.trim()) }
                  for (d in domains) {
                      val v = d.trim().lowercase()
                      if (v.isEmpty()) continue
                      meta.add("$v|$now|$source")
                  }
                  sp.edit().putString(Prefs.KEY_AI_META, meta.joinToString("\n")).apply()
              }
            Log.i(TAG, "已加入拦截 $added 个域名（${if (toAutoList) "自动" else "手动"}列表）")
            added
        } catch (t: Throwable) {
            Log.w(TAG, "加入拦截失败：" + t.message)
            0
        }
    }

    /** 撤销所有"自动加入"的域名（只动自动列表，不碰用户手写的） */
    fun undoAuto(ctx: Context): Int {
        return try {
            val sp = Prefs.sp(ctx)
            val auto = Prefs.parseList(Prefs.getString(sp, Prefs.KEY_AUTO_ADDED))
            if (auto.isEmpty()) return 0
            sp.edit().putString(Prefs.KEY_AUTO_ADDED, "").apply()
            Log.i(TAG, "已清空自动加入的 ${auto.size} 个域名")
            auto.size
        } catch (t: Throwable) {
            Log.w(TAG, "撤销失败：" + t.message)
            0
        }
    }

    /** 自动加入的域名列表（已排序，供界面显示） */
    fun autoAddedList(ctx: Context): List<String> =
        Prefs.parseList(Prefs.autoAdded(ctx)).sorted()

    fun autoAddedCount(ctx: Context): Int =
        Prefs.parseList(Prefs.autoAdded(ctx)).size

    // ==================================================================
    //  AI 审查加入的域名（带时间与触发方式）
    class AiAdded(val domain: String, val at: Long, val manual: Boolean)

    /** 新的在前 */
    fun aiAdded(ctx: Context): List<AiAdded> {
        val raw = Prefs.getString(Prefs.sp(ctx), Prefs.KEY_AI_META).orEmpty()
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<AiAdded>()
        for (line in raw.split('\n')) {
            val p = line.trim().split('|')
            if (p.size < 3) continue
            val at = p[1].toLongOrNull() ?: continue
            out.add(AiAdded(p[0], at, p[2] == "manual"))
        }
        return out.sortedByDescending { it.at }
    }

    /** 撤销时同步清掉元数据 */
    fun forgetAiMeta(ctx: Context, domains: Collection<String>) {
        try {
            val sp = Prefs.sp(ctx)
            val drop = domains.map { it.trim().lowercase() }.toHashSet()
            val keep = Prefs.getString(sp, Prefs.KEY_AI_META).orEmpty()
                .split('\n')
                .filter { it.isNotBlank() && it.substringBefore('|') !in drop }
            sp.edit().putString(Prefs.KEY_AI_META, keep.joinToString("\n")).apply()
        } catch (_: Throwable) {
        }
    }
}