package com.kalvsupu.disad

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 */
object PillFind {

    private const val CELL = 8
    private const val BRIGHT = 180          // 近白
    private const val MAX_SAT = 60          // 低饱和（排除纯彩色底）
    private const val MIN_FILL = 0.06f      // 亮通道：格子内白像素占比下限
    private const val CONTRAST_MIN = 55     // 对比度通道：格子内亮度极差下限
    private const val MIN_H = 16
    private const val MAX_H = 110
    private const val MIN_W = 40
    private const val MAX_W = 420
    private const val MERGE_GAP = 44        // 同行碎片合并间距（原图像素）
    private const val MAX_CAND = 10

    data class Cand(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val by: String = "") {
        val cx: Int get() = (x0 + x1) / 2
        val cy: Int get() = (y0 + y1) / 2
        val w: Int get() = x1 - x0
        val h: Int get() = y1 - y0
        val area: Int get() = w * h
        override fun toString() = "[$x0,$y0][$x1,$y1]"
    }

    fun find(bmp: Bitmap): List<Cand> {
        val w = bmp.width
        val h = bmp.height
        if (w < 100 || h < 100) return emptyList()
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        val regions = listOf(
            // 右上 / 右下：QQ音乐、贴吧、B站、闲鱼都是这个位置
            intArrayOf((w * 0.80f).toInt(), 0, w, h),
            // ★ 顶部左半条带：微信 / 小程序广告把「广告⌄ 跳过」放在左上角
            //   （实测 1080 屏：跳过在 x≈232~312, y≈120~160）
            intArrayOf(0, 0, (w * 0.55f).toInt(), (h * 0.12f).toInt()),
            // 少见的居中跳过键
            intArrayOf((w * 0.25f).toInt(), (h * 0.28f).toInt(), (w * 0.75f).toInt(), (h * 0.78f).toInt())
        )
        val all = ArrayList<Cand>()

        for (r in regions) {
            val rx0 = r[0]
            val ry0 = r[1]
            val rx1 = r[2].coerceAtMost(w)
            val ry1 = r[3].coerceAtMost(h)
            if (rx1 - rx0 < CELL * 4 || ry1 - ry0 < CELL * 4) continue
            val gw = (rx1 - rx0) / CELL
            val gh = (ry1 - ry0) / CELL
            if (gw < 2 || gh < 2) continue

            val white = IntArray(gw * gh)
            val lumMin = IntArray(gw * gh) { 255 }
            val lumMax = IntArray(gw * gh)

            for (yy in 0 until gh * CELL) {
                val rowBase = (ry0 + yy) * w + rx0
                val cellBase = (yy / CELL) * gw
                for (xx in 0 until gw * CELL) {
                    val c = px[rowBase + xx]
                    val rr = (c shr 16) and 0xFF
                    val gg = (c shr 8) and 0xFF
                    val bb = c and 0xFF
                    val mx = if (rr > gg) (if (rr > bb) rr else bb) else (if (gg > bb) gg else bb)
                    val mn = if (rr < gg) (if (rr < bb) rr else bb) else (if (gg < bb) gg else bb)
                    val lum = (rr * 299 + gg * 587 + bb * 114) / 1000
                    val ci = cellBase + xx / CELL
                    if (mx > BRIGHT && mx - mn < MAX_SAT) white[ci]++
                    if (lum < lumMin[ci]) lumMin[ci] = lum
                    if (lum > lumMax[ci]) lumMax[ci] = lum
                }
            }

            val need = (CELL * CELL * MIN_FILL).toInt().coerceAtLeast(1)
            val actWhite = BooleanArray(gw * gh) { white[it] >= need }
            val actContrast = BooleanArray(gw * gh) { lumMax[it] - lumMin[it] > CONTRAST_MIN }

            all.addAll(collect(actWhite, gw, gh, rx0, ry0, "亮"))
            all.addAll(collect(actContrast, gw, gh, rx0, ry0, "对比"))
        }
        return prune(all).sortedWith(
            compareByDescending<Cand> { score(it, w, h) }.thenBy { it.area }
        ).take(MAX_CAND)
    }

    /**
     */
    private fun score(c: Cand, w: Int, h: Int): Int {
        var s = 0
        val rightSide = c.x1 > (w * 0.68).toInt()
        val topZone = rightSide && c.y1 < (h * 0.26).toInt()
        val bottomZone = rightSide && c.y0 > (h * 0.70).toInt()
        if (topZone || bottomZone) s += 6
        if (c.h in 22..95) s += 3
        if (c.w in 60..340) s += 1
        // ★ 顶部条带优先：微信那种「广告⌄ 跳过」在左上角；
        //   同时它的尺寸特征很稳定（高 28~64、宽 60~220，2~4 个字）
        if (c.y1 < (h * 0.13).toInt()) {
            s += 6
            if (c.h in 28..64 && c.w in 60..220) s += 4
        }
        if (c.cy < h * 0.18) s += 2
        if (h - c.cy < h * 0.20) s += 2
        return s
    }

    /** 从激活格子图里抠出连通块，返回尺寸像按钮的框 */
    private fun collect(
        act: BooleanArray, gw: Int, gh: Int, rx0: Int, ry0: Int, tag: String
    ): List<Cand> {
        val seen = BooleanArray(gw * gh)
        val out = ArrayList<Cand>()
        for (start in act.indices) {
            if (!act[start] || seen[start]) continue
            var minx = Int.MAX_VALUE
            var maxx = -1
            var miny = Int.MAX_VALUE
            var maxy = -1
            val stack = ArrayDeque<Int>()
            stack.addLast(start)
            seen[start] = true
            while (stack.isNotEmpty()) {
                val idx = stack.removeLast()
                val cy = idx / gw
                val cx = idx % gw
                if (cx < minx) minx = cx
                if (cx > maxx) maxx = cx
                if (cy < miny) miny = cy
                if (cy > maxy) maxy = cy
                for (dy in -1..1) {
                    val ny = cy + dy
                    if (ny < 0 || ny >= gh) continue
                    for (dx in -2..2) {
                        val nx = cx + dx
                        if (nx < 0 || nx >= gw) continue
                        val ni = ny * gw + nx
                        if (act[ni] && !seen[ni]) {
                            seen[ni] = true
                            stack.addLast(ni)
                        }
                    }
                }
            }
            val bx0 = rx0 + minx * CELL
            val bx1 = rx0 + (maxx + 1) * CELL
            val by0 = ry0 + miny * CELL
            val by1 = ry0 + (maxy + 1) * CELL
            val bw = bx1 - bx0
            val bh = by1 - by0
            if (bh in MIN_H..MAX_H && bw in MIN_W..MAX_W) out.add(Cand(bx0, by0, bx1, by1, tag))
        }
        return out
    }

    /**
     */
    private fun prune(input: List<Cand>): List<Cand> {
        // ① 先按中心点去重（中心落在已有框里就丢弃）
        val kept = ArrayList<Cand>()
        for (c in input.sortedByDescending { it.area }) {
            if (kept.any { k -> c.cx in k.x0..k.x1 && c.cy in k.y0..k.y1 }) continue
            kept.add(c)
        }
        // ② 再合并同一行的碎片（「跳过」和倒计时数字是两个块）
        var merged = true
        while (merged) {
            merged = false
            outer@ for (i in kept.indices) {
                for (j in i + 1 until kept.size) {
                    val a = kept[i]
                    val b = kept[j]
                    val oy = minOf(a.y1, b.y1) - maxOf(a.y0, b.y0)
                    val hh = minOf(a.h, b.h)
                    if (hh <= 0 || oy.toFloat() / hh < 0.5f) continue
                    val gapAB = b.x0 - a.x1
                    val gapBA = a.x0 - b.x1
                    if (!((gapAB in -8..MERGE_GAP) || (gapBA in -8..MERGE_GAP))) continue
                    kept[i] = Cand(
                        minOf(a.x0, b.x0), minOf(a.y0, b.y0),
                        maxOf(a.x1, b.x1), maxOf(a.y1, b.y1), a.by
                    )
                    kept.removeAt(j)
                    merged = true
                    break@outer
                }
            }
        }
        return kept
    }

    /** 把候选框画到图上（绿框），用来肉眼核对 */
    fun draw(src: Bitmap, cands: List<Cand>): Bitmap {
        val copy = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
        val canvas = Canvas(copy)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.rgb(0, 255, 80)
        }
        val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 255, 80)
            textSize = 34f
            isFakeBoldText = true
        }
        for ((i, c) in cands.withIndex()) {
            canvas.drawRect(c.x0.toFloat(), c.y0.toFloat(), c.x1.toFloat(), c.y1.toFloat(), paint)
            canvas.drawText("#${i + 1}", (c.x0 + 4).toFloat(), (c.y0 - 8).coerceAtLeast(36).toFloat(), num)
        }
        return copy
    }
}