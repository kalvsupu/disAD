package com.baidukiller.lite

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * ============================================================================
 *  运行统计（纯内存，进程退出即清空，不做任何持久化）
 * ============================================================================
 */
object BlockStats {

    private val blockedCounter = AtomicLong(0)
    private val allowedCounter = AtomicLong(0)
    private val recent = ArrayDeque<String>()          // 最近被拦截的域名（最多 60 条）

    @Volatile
    var lastBlockedDomain: String = ""
        private set

    fun onBlocked(domain: String) {
        blockedCounter.incrementAndGet()
        lastBlockedDomain = domain
        synchronized(recent) {
            recent.addFirst(domain)
            while (recent.size > 60) recent.removeLast()
        }
    }

    fun onAllowed() {
        allowedCounter.incrementAndGet()
    }

    fun blockedCount(): Long = blockedCounter.get()

    fun allowedCount(): Long = allowedCounter.get()

    fun recentBlocked(): List<String> = synchronized(recent) { ArrayList(recent) }

    fun reset() {
        blockedCounter.set(0)
        allowedCounter.set(0)
        lastBlockedDomain = ""
        synchronized(recent) { recent.clear() }
    }
}
