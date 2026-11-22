package com.kalvsupu.disad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import java.nio.FloatBuffer

/**
 */
object TextRec {

    private const val TAG = "disAD"
    private const val MODEL = "ocr/rec.onnx"
    private const val DICT = "ocr/dict.txt"
    private const val IN_H = 48
    private const val MAX_W = 640

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var chars: List<String> = emptyList()

    @Volatile
    var ready = false
        private set

    /** 实际生效的执行提供者：xnnpack / cpu */
    @Volatile
    var provider = "-"
        private set

    @Volatile
    var loadMs = -1L
        private set

    @Synchronized
    fun init(ctx: Context): Boolean {
        if (ready) return true
        return try {
            val t0 = SystemClock.uptimeMillis()
            chars = ctx.assets.open(DICT).bufferedReader().use { it.readLines() }
            val bytes = ctx.assets.open(MODEL).use { it.readBytes() }
            val e = OrtEnvironment.getEnvironment()
            env = e

            // ① 先试 XNNPACK，失败退回普通 CPU —— 不支持时不要整个功能挂掉
            session = try {
                val opt = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(1)
                    setInterOpNumThreads(1)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    try {
                        addXnnpack(mapOf("intra_op_num_threads" to "4"))
                    } catch (_: Throwable) {
                        addXnnpack(emptyMap())
                    }
                }
                provider = "xnnpack"
                e.createSession(bytes, opt)
            } catch (t: Throwable) {
                Log.w(TAG, "XNNPACK 不可用（${t.message}），退回 CPU")
                val opt = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(1)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                provider = "cpu"
                e.createSession(bytes, opt)
            }

            ready = true
            loadMs = SystemClock.uptimeMillis() - t0
            Log.i(TAG, "OCR 就绪：${chars.size} 字符 / $provider / ${loadMs}ms")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "OCR 初始化失败：${t.message}", t)
            false
        }
    }

    /** 返回 (文字, 置信度)；失败返回 null */
    fun recognize(bmp: Bitmap): Pair<String, Float>? {
        val s = session ?: return null
        val e = env ?: return null
        return try {
            var w = (bmp.width.toFloat() * IN_H / bmp.height).toInt().coerceAtLeast(8)
            if (w > MAX_W) w = MAX_W
            val scaled = Bitmap.createScaledBitmap(bmp, w, IN_H, true)

            val px = IntArray(w * IN_H)
            scaled.getPixels(px, 0, w, 0, 0, w, IN_H)
            if (scaled !== bmp) scaled.recycle()

            stretch(px)          // ② 直方图拉伸

            val data = FloatArray(3 * IN_H * w)
            val plane = IN_H * w
            for (i in px.indices) {
                val c = px[i]
                val r = ((c shr 16) and 0xFF) / 255f
                val g = ((c shr 8) and 0xFF) / 255f
                val b = (c and 0xFF) / 255f
                data[i] = (r - 0.5f) * 2f
                data[plane + i] = (g - 0.5f) * 2f
                data[plane * 2 + i] = (b - 0.5f) * 2f
            }

            OnnxTensor.createTensor(
                e, FloatBuffer.wrap(data), longArrayOf(1, 3, IN_H.toLong(), w.toLong())
            ).use { tensor ->
                s.run(mapOf(s.inputNames.iterator().next() to tensor)).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    val logits = out[0].value as Array<Array<FloatArray>>
                    ctcDecode(logits[0])
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "OCR 识别失败：${t.message}")
            null
        }
    }

    /**
     */
    private fun stretch(px: IntArray) {
        val hist = IntArray(256)
        for (c in px) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            hist[(r * 299 + g * 587 + b * 114) / 1000]++
        }
        val total = px.size
        val cut = (total * 0.02f).toInt()
        var lo = 0
        var acc = 0
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= cut) {
                lo = i
                break
            }
        }
        var hi = 255
        acc = 0
        for (i in 255 downTo 0) {
            acc += hist[i]
            if (acc >= cut) {
                hi = i
                break
            }
        }
        val span = hi - lo
        if (span < 24) return          // 几乎没对比度，拉伸只会放大噪点
        val lut = IntArray(256) { v ->
            val t = (v - lo) * 255 / span
            if (t < 0) 0 else if (t > 255) 255 else t
        }
        for (i in px.indices) {
            val c = px[i]
            val r = lut[(c shr 16) and 0xFF]
            val g = lut[(c shr 8) and 0xFF]
            val b = lut[c and 0xFF]
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun ctcDecode(step: Array<FloatArray>): Pair<String, Float> {
        val sb = StringBuilder()
        var prev = -1
        var scoreSum = 0f
        var scoreN = 0
        for (row in step) {
            var best = 0
            var bestV = row[0]
            for (i in 1 until row.size) {
                if (row[i] > bestV) {
                    bestV = row[i]
                    best = i
                }
            }
            if (best != 0 && best != prev) {
                when {
                    best <= chars.size -> sb.append(chars[best - 1])
                    best == chars.size + 1 -> sb.append(' ')
                }
                scoreSum += bestV
                scoreN++
            }
            prev = best
        }
        val score = if (scoreN == 0) 0f else scoreSum / scoreN
        return sb.toString().trim() to score
    }
}