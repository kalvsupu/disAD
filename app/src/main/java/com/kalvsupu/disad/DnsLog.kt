package com.kalvsupu.disad

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

/**
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

    // ==================================================================
    //  近 N 条原始记录（可查看 / 导出 / 按条数清除）
    /** 一条原始记录：时间戳 / 域名 / 是否被拦 / 会话序号 */
    class Rec(val at: Long, val domain: String, val blocked: Boolean, val session: Int)

    /** 最多保留多少条原始记录（内存里，约 5000 × 60 字节） */
    private const val MAX_RECENT = 5000
    private val recent = ArrayDeque<Rec>()
    private val recentLock = Any()
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
    /**
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

            // 原始记录（供"近 N 次"查看/导出）
            synchronized(recentLock) {
                recent.addLast(Rec(System.currentTimeMillis(), domain, blocked, sessionIndex))
                while (recent.size > MAX_RECENT) recent.removeFirst()
            }
        } catch (_: Throwable) {
        }
    }

    // ==================================================================
    //  查询
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
    /** 最近 n 条记录（新的在前）；n <= 0 时返回全部 */
    fun recentRecords(n: Int): List<Rec> = synchronized(recentLock) {
        val list = recent.toList()
        if (n <= 0 || n >= list.size) list.asReversed() else list.takeLast(n).asReversed()
    }

    fun recentCount(): Int = synchronized(recentLock) { recent.size }

    /**
     */
    fun recentSessionGroups(n: Int): List<Pair<Int, List<Rec>>> {
        val all = synchronized(recentLock) { recent.toList() }
        if (all.isEmpty()) return emptyList()
        val bySession = LinkedHashMap<Int, MutableList<Rec>>()
        for (r in all) {
            bySession.getOrPut(r.session) { ArrayList() }.add(r)
        }
        val sessions = bySession.keys.sortedDescending()
        val take = if (n <= 0) sessions else sessions.take(n)
        return take.map { s -> s to bySession[s]!!.asReversed() }
    }

    /** 只保留最近 n 次会话，更早的整段丢掉 */
    fun keepRecentSessions(n: Int) {
        synchronized(recentLock) {
            if (n <= 0) {
                recent.clear()
                return
            }
            val sessions = recent.map { it.session }.distinct()
            if (sessions.size <= n) return
            val keepFrom = sessions.sortedDescending().take(n).min()
            val kept = recent.filter { it.session >= keepFrom }
            recent.clear()
            recent.addAll(kept)
        }
    }

    /** 只保留最近 n 条，更早的直接丢掉 */
    fun keepRecentOnly(n: Int) {
        synchronized(recentLock) {
            if (n <= 0) {
                recent.clear()
            } else {
                while (recent.size > n) recent.removeFirst()
            }
        }
    }

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

    // ==================================================================
    //  落盘：重启后「近 N 次记录」不再是空的
    //  格式（TSV，只用 ASCII 标签，域名单独一列）：
    //    S <sessionIndex>
    //    E <order> <count> <sessions> <b|a> <domain>
    //    R <at> <0|1> <session> <domain>
    private const val FILE_NAME = "dnslog.tsv"

    @Volatile
    private var loaded = false

    @Volatile
    private var lastSaveAt = 0L

    private fun file(ctx: android.content.Context) = java.io.File(ctx.filesDir, FILE_NAME)

    /** 启动时读回上次的域名统计 + 原始记录 */
    fun loadFromDisk(ctx: android.content.Context) {
        if (loaded) return
        loaded = true
        try {
            val f = file(ctx)
            if (!f.exists() || f.length() == 0L) return
            var maxOrder = 0L
            var maxSession = 0
            val statList = ArrayList<Stat>()
            val statDomains = ArrayList<String>()
            val recList = ArrayList<Rec>()

            f.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isEmpty()) return@forEach
                    val p = line.split('\t')
                    when {
                        p[0] == "S" && p.size >= 2 -> {
                            maxSession = p[1].toIntOrNull() ?: 0
                        }
                        p[0] == "E" && p.size >= 6 -> {
                            val order = p[1].toLongOrNull() ?: 0L
                            val count = p[2].toLongOrNull() ?: 0L
                            val sessions = p[3].toIntOrNull() ?: 0
                            val blocked = p[4] == "b"
                            val domain = p[5]
                            val st = Stat()
                            st.count.add(count)
                            st.sessions = sessions
                            st.lastSession = maxSession
                            st.everBlocked = blocked
                            st.everAllowed = !blocked
                            st.order = order
                            statList.add(st)
                            statDomains.add(domain)
                            if (order > maxOrder) maxOrder = order
                        }
                        p[0] == "R" && p.size >= 5 -> {
                            val at = p[1].toLongOrNull() ?: 0L
                            val blocked = p[2] == "1"
                            val session = p[3].toIntOrNull() ?: 0
                            recList.add(Rec(at, p[4], blocked, session))
                        }
                    }
                }
            }

            for (i in statDomains.indices) {
                if (stats.size >= MAX_DOMAINS) break
                stats[statDomains[i]] = statList[i]
            }
            sequence.set(maxOrder)
            sessionIndex = maxSession
            totalSessions = maxSession
            synchronized(recentLock) {
                recent.clear()
                recent.addAll(recList.takeLast(MAX_RECENT))
            }
        } catch (_: Throwable) {
        }
    }

    /** 写回磁盘（每 10 秒最多一次）；force=true 时立即写 */
    fun saveToDisk(ctx: android.content.Context, force: Boolean = false) {
        try {
            val now = SystemClock.uptimeMillis()
            if (!force && now - lastSaveAt < 10_000L) return
            lastSaveAt = now

            val sb = StringBuilder(64 * 1024)
            sb.append("S\t").append(sessionIndex).append('\n')
            for (e in entries()) {
                sb.append("E\t").append(e.order).append('\t')
                    .append(e.count).append('\t')
                    .append(e.sessions).append('\t')
                    .append(if (e.blocked) "b" else "a").append('\t')
                    .append(e.domain).append('\n')
            }
            synchronized(recentLock) {
                for (r in recent) {
                    sb.append("R\t").append(r.at).append('\t')
                        .append(if (r.blocked) "1" else "0").append('\t')
                        .append(r.session).append('\t')
                        .append(r.domain).append('\n')
                }
            }

            val target = file(ctx)
            val tmp = java.io.File(target.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(sb.toString(), Charsets.UTF_8)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                // rename 失败就退化成直接覆盖，至少别丢数据
                target.writeText(sb.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (_: Throwable) {
        }
    }
}