package com.kalvsupu.disad

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 */
object ApiReview {

    private const val TAG = "disAD"
    private const val CONNECT_TIMEOUT_MS = 20_000
    /** 聊天读超时：400 个域名 + 2000 token，真机上 20~60 秒很常见 */
    private const val READ_TIMEOUT_MS = 120_000
    /** 模型列表读超时 */
    private const val LIST_TIMEOUT_MS = 30_000
    private const val MAX_DOMAINS_PER_ROUND = 400
    private const val CHANNEL_AI = "disad_ai_review"
    private const val NOTIF_AI_ID = 1004
    /** 两次审查的最小间隔：防止同一轮重复触发 */
    private const val MIN_REVIEW_GAP_MS = 10 * 60 * 1000L

    private val pool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "disad-ai").also { it.isDaemon = true }
    }

    @Volatile
    var busy = false
        private set

    /** 会话切换时调用：满足条件就异步跑一次审查 */
    fun maybeReviewAsync(ctx: Context) {
        try {
            if (Prefs.apiKey(ctx).isBlank()) return
            if (busy) return

            val sessions = DnsLog.totalSessions
            val every = Prefs.apiEvery(ctx).coerceAtLeast(1)
            val threshold = Prefs.apiThreshold(ctx).coerceAtLeast(500)

            // 累计拦截次数现在是落盘的（跨重启单调递增），所以真的能跨过 10000。
            // 触发条件改成"距上次审查又累计了 threshold 次"，这样每 10000 次都会再审一次，
            // 而不是只在第一次跨过阈值时触发一次。
            val blockedCount = BlockStats.blockedCount().toInt()
            val lastBlocked = Prefs.apiLastBlocked(ctx)
            val sinceLast = blockedCount - lastBlocked

            val bySession = sessions > 0 && sessions % every == 0 && sessions != Prefs.apiLastSession(ctx)
            val byBlocked = blockedCount > 0 && sinceLast >= threshold
            if (!bySession && !byBlocked) return
            // ★ 先占坑再跑：lastSession / lastBlocked 立刻写掉。
            //   原来只在审查结束后才写，同一个会话里被调用两次就会跑两遍（实测如此）。
            val nowMs = System.currentTimeMillis()
            if (nowMs - Prefs.apiLastReviewAt(ctx) < MIN_REVIEW_GAP_MS) {
                Log.i(TAG, "AI 审查跳过：距上次不足 ${MIN_REVIEW_GAP_MS / 60000} 分钟")
                return
            }
            Prefs.setApiLastSession(ctx, sessions)
            // 只有"拦截数触发"才推进拦截基线；会话触发的审查跟拦截数无关，
            // 推进了会把「距下次还差 N 次」顶回阈值，看起来像一直不动
            if (byBlocked) Prefs.setApiLastBlocked(ctx, blockedCount)
            Log.i(TAG, "AI 审查触发：会话=$sessions 每${every}次 拦截=$blockedCount 距上次=$sinceLast 阈值=$threshold")
            runAsync(ctx, if (byBlocked) "拦截数触发" else "会话触发", manual = false)
        } catch (t: Throwable) {
            Log.w(TAG, "AI 审查调度失败：${t.message}")
        }
    }

    /** 界面上的「立即审查一次」 */
    fun runNow(ctx: Context) {
        if (Prefs.apiKey(ctx).isBlank()) {
            Prefs.setApiStatus(ctx, "未配置 API Key，此功能不生效")
            return
        }
        runAsync(ctx, "手动触发", manual = true)
    }

    /**
     */
    private fun notify(ctx: Context, title: String, text: String, ongoing: Boolean) {
        try {
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_AI) == null) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(
                        CHANNEL_AI,
                        "AI 审查提醒",
                        android.app.NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        setShowBadge(false)
                        description = "AI 审查开始/完成时提醒一次"
                    }
                )
            }
            val open = android.app.PendingIntent.getActivity(
                ctx, 0,
                android.content.Intent(ctx, MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = android.app.Notification.Builder(ctx, CHANNEL_AI)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notify)
                .setAutoCancel(!ongoing)
                .setOngoing(ongoing)
                .setContentIntent(open)
                .build()
            nm.notify(NOTIF_AI_ID, n)
        } catch (_: Throwable) {
        }
    }

    private fun runAsync(ctx: Context, reason: String, manual: Boolean) {
        if (busy) {
            Prefs.setApiStatus(ctx, "已有一次审查在进行中")
            return
        }
        busy = true
        Prefs.setApiStatus(ctx, "正在审查（$reason）…")
        notify(ctx, "AI 审查已开始", "$reason · 正在判断漏网域名，完成后会再提醒", true)
        val app = ctx.applicationContext
        pool.execute {
            try {
                val added = doReview(app, manual)
                val okText = if (added > 0) "审查完成：新增 $added 个域名（$reason）"
                else "审查完成：没有发现新的漏网域名（$reason）"
                Prefs.setApiStatus(app, okText)
                notify(app, "AI 审查完成", okText, false)
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                Prefs.setApiStatus(app, "审查失败：$msg")
                notify(app, "AI 审查失败", msg, false)
                Log.w(TAG, "AI 审查失败：$msg")
            } finally {
                busy = false
                Prefs.setApiLastReviewAt(app, System.currentTimeMillis())
                Prefs.setApiLastSession(app, DnsLog.totalSessions)
                Prefs.setApiLastBlocked(
                    app,
                    try {
                        DnsLog.entries().count { it.blocked }
                    } catch (_: Throwable) {
                        0
                    }
                )
            }
        }
    }

    /** 真正干活的：取放行域名 → 问模型 → 解析 → 加入自动学习名单 */
    private fun doReview(ctx: Context, manual: Boolean): Int {
        val t0 = SystemClock.uptimeMillis()
        val entries = DnsLog.entries()
        if (entries.isEmpty()) return 0

        // 只问"放行的"域名（已拦的不需要再问），按出现次数从高到低
        val candidates = entries.asSequence()
            .filter { !it.blocked }
            .sortedByDescending { it.count }
            .map { it.domain }
            .take(MAX_DOMAINS_PER_ROUND)
            .toList()
        if (candidates.isEmpty()) return 0

        val base = Prefs.apiBase(ctx).trimEnd('/')
        if (base.isEmpty()) throw IllegalStateException("API 地址为空")
        val model = Prefs.apiModel(ctx).ifBlank { "deepseek-chat" }
        val key = Prefs.apiKey(ctx)

        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.0)
            put("max_tokens", 2000)
            put(
                "messages",
                org.json.JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("role", "system")
                            put(
                                "content",
                                "你是广告域名判定器。用户会给出一批域名，请判断哪些是广告/推广/追踪类域名。" +
                                    "只输出判定为广告或追踪的域名，每行一个，不要编号、不要解释、不要标点。" +
                                    "拿不准的一律不要输出。如果没有任何一个，输出空。"
                            )
                        }
                    )
                    put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", candidates.joinToString("\n"))
                        }
                    )
                }
            )
        }.toString()

        val text = chat(base + "/v1/chat/completions", key, body)
        val parsed = parseAnswer(text)
        if (parsed.isEmpty()) return 0

        // 只接受"原样出现在候选里"的域名，防止模型编造
        val allowed = candidates.toHashSet()
        val safe = parsed.filter { it in allowed }.distinct()
        if (safe.isEmpty()) return 0

        val added = AutoLearn.addDomains(ctx, safe, toAutoList = true, source = if (manual) "manual" else "auto")
        // ★ 加完立刻让运行中的隧道重载规则，否则新域名要等下次重启才生效
        if (added > 0) {
            try {
                com.kalvsupu.disad.vpn.AdBlockVpnService.reloadRules(ctx)
            } catch (_: Throwable) {
            }
        }
        Log.i(TAG, "AI 审查：候选 ${candidates.size}，模型给出 ${parsed.size}，采纳 ${safe.size}，新增 $added（${SystemClock.uptimeMillis() - t0}ms）")
        return added
    }

    private fun post(url: String, key: String, body: String): String = post(url, key, body, READ_TIMEOUT_MS)

    /**
     */
    private fun post(url: String, key: String, body: String, readTimeoutMs: Int): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            doOutput = true
            // ★ 不复用 keep-alive 连接：中转站/网关经常先关连接再回包，
            //   复用那条死连接就会干等到超时（表现为"第一次成功，之后全超时"）。
            setRequestProperty("Connection", "close")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $key")
        }
        try {
            val os: OutputStream = conn.outputStream
            os.write(body.toByteArray(Charsets.UTF_8))
            os.flush()
            os.close()

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: ""
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code ${text.take(80)}")
            }
            return text
        } finally {
            try {
                conn.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    /** 聊天请求：超时/连接异常时重试一次（中转站抖一下很常见） */
    private fun chat(url: String, key: String, body: String): String {
        var last: Throwable? = null
        for (attempt in 1..2) {
            try {
                return post(url, key, body, READ_TIMEOUT_MS)
            } catch (t: Throwable) {
                last = t
                Log.w(TAG, "AI 请求第 $attempt 次失败：${t.message}")
                if (attempt == 1) {
                    try {
                        Thread.sleep(800)
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        throw last ?: IllegalStateException("请求失败")
    }

    /** 从 OpenAI 兼容响应里取出 content，再按行解析成域名 */
    private fun parseAnswer(raw: String): List<String> {
        val content = try {
            JSONObject(raw)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } catch (_: Throwable) {
            return emptyList()
        }
        return content.lines()
            .map { it.trim().trim('`', '-', '*', ' ', '.', ',', '，', '。', '"', '\'') }
            .filter { looksLikeDomain(it) }
    }
    /**
     */
    fun testConnection(ctx: Context, model: String, onResult: (Boolean, String) -> Unit) {
        val base = Prefs.apiBase(ctx).trimEnd('/')
        val key = Prefs.apiKey(ctx)
        val app = ctx.applicationContext
        pool.execute {
            var ok = false
            var msg = ""
            try {
                if (key.isBlank()) throw IllegalStateException("没填 API Key")
                if (model.isBlank()) throw IllegalStateException("没选模型")
                val body = JSONObject().apply {
                    put("model", model)
                    put("max_tokens", 8)
                    put(
                        "messages",
                        org.json.JSONArray().put(
                            JSONObject().apply {
                                put("role", "user")
                                put("content", "ping")
                            }
                        )
                    )
                }.toString()
                chat("$base/v1/chat/completions", key, body)
                ok = true
                msg = "连通正常（模型 $model 有响应）"
                Log.i(TAG, "AI 连通测试通过：$model")
            } catch (t: Throwable) {
                msg = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "AI 连通测试失败：$msg")
            }
            val fOk = ok
            val fMsg = msg
            android.os.Handler(app.mainLooper).post { onResult(fOk, fMsg) }
        }
    }

    /**
     */
    fun listModels(ctx: Context, onResult: (List<String>, String?) -> Unit) {
        val base = Prefs.apiBase(ctx).trimEnd('/')
        val key = Prefs.apiKey(ctx)
        val app = ctx.applicationContext
        pool.execute {
            var ids: List<String> = emptyList()
            var err: String? = null
            try {
                if (key.isBlank()) throw IllegalStateException("先填 API Key")
                val conn = (URL("$base/v1/models").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = LIST_TIMEOUT_MS
                    setRequestProperty("Authorization", "Bearer $key")
                }
                try {
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val text = stream?.let {
                        BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
                    } ?: ""
                    if (code !in 200..299) throw IllegalStateException("HTTP $code ${text.take(80)}")
                    val arr = JSONObject(text).optJSONArray("data")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val id = arr.optJSONObject(i)?.optString("id") ?: continue
                            if (id.isNotBlank()) ids = ids + id
                        }
                    }
                    if (ids.isEmpty()) err = "返回里没有模型列表"
                } finally {
                    try {
                        conn.disconnect()
                    } catch (_: Throwable) {
                    }
                }
            } catch (t: Throwable) {
                err = t.message ?: t.javaClass.simpleName
            }
            val fIds = ids
            val fErr = err
            android.os.Handler(app.mainLooper).post { onResult(fIds, fErr) }
        }
    }
    private fun looksLikeDomain(s: String): Boolean {
        if (s.length < 4 || s.length > 120) return false
        if (s.contains(' ')) return false
        if (!s.contains('.')) return false
        return s.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
    }
}
