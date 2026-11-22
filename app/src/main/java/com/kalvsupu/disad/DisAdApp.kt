package com.kalvsupu.disad

import android.app.Application

/**
 */
class DisAdApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 把上次的累计计数和近 N 次 DNS 记录读回来（重启后不该归零/变空）
        try {
            BlockStats.load(this)
            DnsLog.loadFromDisk(this)
        } catch (_: Throwable) {
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                CrashLog.record(this, "未捕获异常（线程 ${thread.name}）", error)
            } catch (_: Throwable) {
            }
            // 交给上一个处理器（系统默认），保持正常崩溃行为
            previous?.uncaughtException(thread, error)
        }
    }
}