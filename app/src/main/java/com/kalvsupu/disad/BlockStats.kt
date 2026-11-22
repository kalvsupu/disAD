package com.kalvsupu.disad

import android.content.Context
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * 拦截 / 放行计数器。
 *
 * 以前只在内存里累加，进程一停就归零：主界面「DNS 拦截（次）」会跳回 0，
 * 而 AI 审查的「累计拦截 10000 次触发」也就永远跨不过去。
 * 现在把总数落盘到 Prefs，启动时读回，运行中每 200 次落一次。
 */
object BlockStats {

    /** 拦截数变化后多久写一次盘；放行数太频繁，单独隔久一点 */
    private const val FLUSH_BLOCKED_EVERY = 5L
    private const val FLUSH_ALLOWED_EVERY = 100L

    private val blockedCounter = AtomicLong(0)
    private val allowedCounter = AtomicLong(0)
    /** 本次打开 App 以来拦了多少（界面上的大数字用它，每次进前台重置） */
    private val sessionBlocked = AtomicLong(0)
    private val recent = ArrayDeque<String>()

    @Volatile
    private var loaded = false

    @Volatile
    var lastBlockedDomain: String = ""
        private set

    /** 进程启动时调用一次：把上次的总数读回来 */
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val total = Prefs.totalBlocked(ctx)
            blockedCounter.set(total)
            allowedCounter.set(Prefs.totalAllowed(ctx))
            // 升级兼容：旧版本"上次审查时的拦截数"记的是去重域名数（几百），
            // 比新的累计计数大，会让"距下次自动审查还差 N 次"永远卡在阈值不动
            if (Prefs.apiLastBlocked(ctx).toLong() > total) Prefs.setApiLastBlocked(ctx, 0)
        } catch (_: Throwable) {
        }
    }

    /** 把当前总数写回 Prefs（sync=true 立即落盘，进程可能马上被杀时用） */
    fun flush(ctx: Context, sync: Boolean = false) {
        try {
            Prefs.setTotalBlocked(ctx, blockedCounter.get(), sync)
            Prefs.setTotalAllowed(ctx, allowedCounter.get(), sync)
        } catch (_: Throwable) {
        }
    }

    fun onBlocked(ctx: Context, domain: String) {
        sessionBlocked.incrementAndGet()
        val n = blockedCounter.incrementAndGet()
        lastBlockedDomain = domain
        synchronized(recent) {
            recent.addFirst(domain)
            while (recent.size > 60) recent.removeLast()
        }
        // 拦截是低频事件，每 5 次写一次盘，界面上的累计数才不会"每次打开都清零"
        if (n % FLUSH_BLOCKED_EVERY == 0L) flush(ctx)
    }

    fun onAllowed(ctx: Context) {
        val n = allowedCounter.incrementAndGet()
        if (n % FLUSH_ALLOWED_EVERY == 0L) flush(ctx)
    }

    /** 累计拦截（落盘、跨重启） */
    fun blockedCount(): Long = blockedCounter.get()

    /** 本次打开以来拦截（界面上显示的大数字） */
    fun sessionBlockedCount(): Long = sessionBlocked.get()

    fun resetSession() {
        sessionBlocked.set(0)
    }

    fun allowedCount(): Long = allowedCounter.get()

    fun recentBlocked(): List<String> = synchronized(recent) { ArrayList(recent) }

    fun reset(ctx: Context) {
        sessionBlocked.set(0)
        blockedCounter.set(0)
        allowedCounter.set(0)
        lastBlockedDomain = ""
        synchronized(recent) { recent.clear() }
        flush(ctx, sync = true)
    }
}
