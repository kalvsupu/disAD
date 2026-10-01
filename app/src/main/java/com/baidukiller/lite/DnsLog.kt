package com.baidukiller.lite

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

/**
 * ============================================================================
 *  域名记录 + 会话统计（自动学习的数据来源）
 * ============================================================================
 *  记录目标 App 解析过的**每一个**域名（含放行的），并按「会话」统计：
 *
 *    · 会话 = 一段连续的解析活动；两次解析间隔超过 30 秒，就认为开始了新会话
 *      （对应"冷启动一次 App"）
 *    · 每个域名记录：总次数、出现在多少个会话里、首次出现距会话开始多久
 *
 *  为什么这么统计：
 *    合法域名（maps.baidu.com、pan.baidu.com…）几乎**每次启动**都会出现；
 *    广告域名只**偶尔**冒头（广告平台轮换、素材缓存命中与否）。
 *    所以「出现率低 + 带广告特征」的域名，就是漏网的广告源候选。
 *
 *  数据全部只在内存里，不落盘、不上传。可一键导出成文本。
 * ============================================================================
 */
object DnsLog {

    /** 两次解析间隔超过这个值，视为新会话（= 新的一次 App 启动） */
    const val SESSION_GAP_MS = 30_000L

    /** 最多记录多少个不同域名 */
    private const val MAX_DOMAINS = 4000

    class Stat {
        val count = LongAdder()

        @Volatile var sessions: Int = 0
        @Volatile var lastSession: Int = -1
        @Volatile var firstOffsetMs: Long = Long.MAX_VALUE
        @Volatile var everBlocked: Boolean = false
        @Volatile var everAllowed: Boolean = false
        @Volatile var order: Long = 0L
    }

    private val stats = ConcurrentHashMap<String, Stat>()
    private val sequence = AtomicLong(0)

    @Volatile
    var enabled: Boolean = false

    @Volatile
    private var sessionIndex: Int = 0

    @Volatile
    private var lastQueryAt: Long = 0L

    @Volatile
    private var sessionStartAt: Long = 0L

    /** 到目前为止共经历了多少次会话（= 多少次启动） */
    @Volatile
    var totalSessions: Int = 0
        private set

    // ==================================================================
    //  记录
    // ==================================================================
    /**
     * 判断是否进入新会话。
     * @return true 表示"上一次会话刚刚结束" —— 此时适合触发一次自动学习。
     */
    fun beginSessionIfNeeded(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (lastQueryAt != 0L && now - lastQueryAt <= SESSION_GAP_MS) {
            lastQueryAt = now
            return false
        }
        lastQueryAt = now
        sessionStartAt = now
        sessionIndex++
        totalSessions = sessionIndex
        return sessionIndex > 1
    }

    fun record(domain: String, blocked: Boolean) {
        if (!enabled || domain.isEmpty()) return
        try {
            var st = stats[domain]
            if (st == null) {
                if (stats.size >= MAX_DOMAINS) return
                stats.putIfAbsent(domain, Stat())
                st = stats[domain]
            }
            st ?: return

            st.count.increment()
            if (st.lastSession != sessionIndex) {
                st.lastSession = sessionIndex
                st.sessions++
            }
            if (st.order == 0L) st.order = sequence.incrementAndGet()
            if (blocked) st.everBlocked = true else st.everAllowed = true

            val offset = SystemClock.uptimeMillis() - sessionStartAt
            if (offset in 0 until st.firstOffsetMs) st.firstOffsetMs = offset
        } catch (_: Throwable) {
        }
    }

    // ==================================================================
    //  查询
    // ==================================================================
    /** 一条记录：域名 / 是否被拦过 / 次数 / 首次出现序号 / 会话数 */
    data class Entry(
        val domain: String,
        val blocked: Boolean,
        val count: Long,
        val order: Long,
        val sessions: Int
    )

    /** 按首次出现顺序**倒序**返回：最新解析到的域名排在最前面 */
    fun entries(): List<Entry> {
        val out = ArrayList<Entry>(stats.size)
        try {
            stats.forEach { (k, v) ->
                out.add(
                    Entry(
                        domain = k,
                        blocked = v.everBlocked && !v.everAllowed,
                        count = v.count.sum(),
                        order = v.order,
                        sessions = v.sessions
                    )
                )
            }
        } catch (_: Throwable) {
        }
        out.sortByDescending { it.order }
        return out
    }

    /**
     * 漏网广告域名候选。
     *
     * @param isBlocked 判断某域名当前是否**已经**在拦截规则里（已拦的不再重复推荐）
     */
    data class Cand(
        val domain: String,
        val count: Long,
        val sessions: Int,
        val totalSessions: Int,
        val score: Int,
        val high: Boolean,
        val reasons: String
    )

    fun candidates(isBlocked: (String) -> Boolean): List<Cand> {
        val total = totalSessions
        if (total < 2) return emptyList()

        val out = ArrayList<Cand>()
        try {
            stats.forEach { (domain, st) ->
                val allowed = st.everAllowed
                if (!allowed) return@forEach          // 本来就被拦住了，不需要推荐
                if (isBlocked(domain)) return@forEach

                val rate = st.sessions.toFloat() / total.toFloat()
                if (rate > 0.6f) return@forEach       // 大多数启动都出现 → 大概率是正常功能

                val (score, reasons) = score(domain, st, total, rate)
                if (score <= 0) return@forEach

                out.add(
                    Cand(
                        domain = domain,
                        count = st.count.sum(),
                        sessions = st.sessions,
                        totalSessions = total,
                        score = score,
                        high = score >= 4,
                        reasons = reasons
                    )
                )
            }
        } catch (_: Throwable) {
        }
        out.sortWith(compareByDescending<Cand> { it.score }.thenBy { it.domain })
        return out
    }

    // ==================================================================
    //  可疑度打分
    // ==================================================================
    private fun score(domain: String, st: Stat, total: Int, rate: Float): Pair<Int, String> {
        var s = 0
        val reasons = StringBuilder()

        if (rate <= 0.5f) {
            s += 2
            reasons.append("出现率低 ")
        }
        if (rate <= 0.3f) {
            s += 1
            reasons.append("极少出现 ")
        }
        if (hasAdToken(domain)) {
            s += 2
            reasons.append("含广告特征词 ")
        }
        if (hasRandomLabel(domain)) {
            s += 2
            reasons.append("随机子域(素材CDN) ")
        }
        if (st.firstOffsetMs in 0..8000) {
            s += 1
            reasons.append("启动前8秒出现 ")
        }
        return Pair(s, reasons.toString().trim())
    }

    /** 域名标签里是否出现广告业务词（按标签精确匹配，避免 "download" 里含 "ad" 这类误判） */
    private fun hasAdToken(domain: String): Boolean {
        val labels = domain.split('.')
        for (lb in labels) {
            val l = lb.lowercase()
            if (l in AD_TOKENS) return true
            for (t in AD_PREFIX_TOKENS) {
                if (l.startsWith(t)) return true
            }
            for (t in AD_CONTAIN_TOKENS) {
                if (l.contains(t)) return true
            }
        }
        return false
    }

    private val AD_TOKENS = setOf(
        "ad", "ads", "adv", "adx", "adm", "adserv", "adserver", "adservice",
        "sdk", "track", "tracker", "click", "clk", "report", "union", "ssp",
        "dsp", "cpc", "cpm", "bid", "imp", "promo", "monitor", "stat", "stats",
        "log", "logs", "collect", "analytics", "mkt", "gdt", "adn", "adc"
    )

    private val AD_PREFIX_TOKENS = listOf("ad-", "ad_", "ads-", "ads_", "admob")

    private val AD_CONTAIN_TOKENS = listOf(
        "mobads", "advert", "advlion", "mentamob", "antpcdn", "adsmind",
        "pglstatp", "pangolin", "ctobsnssdk", "tradplus", "mintegral",
        "sigmob", "beizi", "miaozhen", "ugdtimg", "gdtimg", "66mobi"
    )

    /** 是否有"随机字符串"标签（广告素材 CDN 的典型特征，如 0638fb-1959038000） */
    private fun hasRandomLabel(domain: String): Boolean {
        for (lb in domain.split('.')) {
            if (lb.length < 10) continue
            var digits = 0
            var hyphens = 0
            var hexOnly = true
            for (ch in lb) {
                when {
                    ch.isDigit() -> digits++
                    ch == '-' -> hyphens++
                    ch in 'a'..'f' || ch in 'A'..'F' -> {
                    }
                    else -> hexOnly = false
                }
            }
            if (hexOnly && digits >= 4 && hyphens >= 1) return true
        }
        return false
    }

    // ==================================================================
    fun clear() {
        stats.clear()
        sequence.set(0)
    }

    /** 导出为可读文本 */
    fun toText(): String {
        val sb = StringBuilder()
        sb.append("# disAD 域名记录（按首次解析顺序倒序）\n")
        sb.append("# 共经历 ").append(totalSessions).append(" 次会话(启动)\n")
        sb.append("# 次数\t会话数\t状态\t域名\n")
        for (e in entries()) {
            sb.append(e.count).append('\t')
                .append(e.sessions).append('\t')
                .append(if (e.blocked) "拦截" else "放行").append('\t')
                .append(e.domain).append('\n')
        }
        return sb.toString()
    }
}
