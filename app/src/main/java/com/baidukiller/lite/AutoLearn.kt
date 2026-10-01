package com.baidukiller.lite

import android.content.Context
import android.util.Log

/**
 * ============================================================================
 *  自动学习：把"漏网的广告域名"补进拦截名单
 * ============================================================================
 *  两种模式：
 *
 *   1. 手动确认（默认）—— 点「分析漏网域名」，程序列出一份候选，
 *      带广告特征的默认勾选，你确认后一键加入拦截。
 *   2. 全自动 —— 打开「自动学习」开关后，每次 App 启动之间（会话切换时）
 *      自动把高置信度候选加入拦截，每次最多 5 个，可一键撤销。
 *
 *  【存放位置很重要】
 *    手动确认加入的域名 → 写进用户看得见的「自定义拦截域名」；
 *    自动加入的域名     → 写进独立的「自动加入」列表（只读、可查看、可清空），
 *                        **绝不写进用户的输入框**。
 *    这样用户永远不用在一堆机器加的域名里翻找，也不会误删。
 *
 *  【防误伤】
 *    · 百度自家的长尾域名天然"出现率低"，很容易被误判成广告，
 *      所以自动学习默认**跳过 baidu.com / bdstatic.com / bdimg.com / baidubce.com**，
 *      除非该域名命中广告关键字（mobads、nsclick 之类）；
 *    · 白名单里的域名永不加入；
 *    · 已在规则里的不重复加入；
 *    · 每次自动学习最多加 5 个。
 * ============================================================================
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
    // ==================================================================
    /** 手动模式：全部候选（已排除白名单 / 已拦截 / 百度自家非广告域名） */
    fun analyze(ctx: Context, isBlocked: (String) -> Boolean): List<DnsLog.Cand> {
        if (DnsLog.totalSessions < 2) return emptyList()
        val white = Prefs.parseList(Prefs.whitelist(ctx)).map { it.lowercase() }.toSet()
        return DnsLog.candidates(isBlocked).filter { c ->
            if (white.any { w -> c.domain == w || c.domain.endsWith(".$w") }) return@filter false
            if (isProtected(c.domain)) return@filter false
            // ★ 必须自带广告特征词，否则不加。
            //   实测教训：只靠"出现率低"会把它加成一堆基础设施域名 ——
            //   paydns.wechatpay.cn（微信支付）、s1.hdslb.com（B站图片 CDN）、
            //   dns.weixin.qq.com.cn（微信 HTTPDNS）、sdk.extra.talk.getui.com（推送）
            //   都被误加过。加了这些不是"多拦一点广告"，而是"把人家 App 弄坏"。
            if (!hasAdSignal(c.domain)) return@filter false
            true
        }
    }

    /**
     * 明显是"应用基础设施"的域名后缀，自动学习永不添加。
     * 这些域名的共同点是：**它们是某个 App 正常工作的基础**（通信、支付、图片、
     * 推送、解析、商店），拦了不会"少看一个广告"，只会让那个 App 出问题。
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
     * 域名里有没有广告特征。
     * 宁可漏加，不可错加 —— 自动学习只是"辅助发现"，漏一个域名顶多少拦一条广告，
     * 错加一条却可能让用户的某个 App 直接不能用。
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
    // ==================================================================
    /**
     * 加入拦截名单。
     *
     * @param toAutoList true = 写进"自动加入"独立列表；false = 写进用户自定义列表
     * @return 实际新增的条数
     */
    fun addDomains(ctx: Context, domains: List<String>, toAutoList: Boolean): Int {
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
}
