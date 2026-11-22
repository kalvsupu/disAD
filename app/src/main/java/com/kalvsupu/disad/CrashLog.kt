package com.kalvsupu.disad

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 */
object CrashLog {

    private const val FILE_NAME = "last_crash.txt"
    private const val MAX_CHARS = 20_000

    /** 记下一次异常（崩溃或启动失败） */
    fun record(ctx: Context, what: String, t: Throwable) {
        try {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val sb = StringBuilder(4096)
            sb.append("时间：").append(time).append('\n')
            sb.append("类型：").append(what).append('\n')
            sb.append("异常：").append(t.javaClass.name)
                .append(": ").append(t.message ?: "(无消息)").append("\n\n")
            sb.append(Log.getStackTraceString(t))
            var text = sb.toString()
            if (text.length > MAX_CHARS) text = text.substring(0, MAX_CHARS) + "\n…（已截断）"
            File(ctx.filesDir, FILE_NAME).writeText(text)
        } catch (_: Throwable) {
            // 记录失败本身绝不能再抛出去
        }
    }

    fun read(ctx: Context): String? = try {
        val f = File(ctx.filesDir, FILE_NAME)
        if (f.exists() && f.length() > 0) f.readText() else null
    } catch (_: Throwable) {
        null
    }

    fun has(ctx: Context): Boolean = read(ctx) != null

    fun clear(ctx: Context) {
        try {
            File(ctx.filesDir, FILE_NAME).delete()
        } catch (_: Throwable) {
        }
    }
}