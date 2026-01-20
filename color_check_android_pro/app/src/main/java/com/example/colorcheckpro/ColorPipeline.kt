package com.example.colorcheckpro

import android.graphics.*
import kotlin.math.*

object ColorPipeline {

    data class SampleResult(
        val linRGB: DoubleArray, // 0..1 linear
        val sRGB: IntArray,      // 0..255
        val hex: String,
        val labD50: DoubleArray,
        val quality: Double,
        val note: String
    )

    // Calibration points: map measured linear channel -> target value
    // targets: B=0, G=0.18, W=1.0
    data class CalPoint(val kind: Kind, val lin: DoubleArray) {
        enum class Kind { WHITE, GRAY, BLACK }
        fun target(): Double = when (kind) {
            Kind.BLACK -> 0.0
            Kind.GRAY -> 0.18
            Kind.WHITE -> 1.0
        }
    }

    class Calibration {
        private val points = mutableListOf<CalPoint>()
        fun clear() = points.clear()
        fun add(p: CalPoint) {
            points.removeAll { it.kind == p.kind }
            points.add(p)
        }
        fun isReady(): Boolean = points.size >= 2

        private fun buildAnchors(ch: Int): List<Pair<Double, Double>> {
            val a = mutableListOf<Pair<Double, Double>>()
            for (p in points) a.add(p.lin[ch].coerceIn(0.0, 1.0) to p.target())
            if (a.none { it.second == 0.0 }) a.add(0.0 to 0.0)
            if (a.none { it.second == 1.0 }) a.add(1.0 to 1.0)
            return a.sortedBy { it.first }
        }

        private fun map1(xIn: Double, anchors: List<Pair<Double, Double>>): Double {
            val x = xIn.coerceIn(0.0, 1.0)
            for (i in 1 until anchors.size) {
                val (x0, y0) = anchors[i - 1]
                val (x1, y1) = anchors[i]
                if (x <= x1) {
                    val t = if (x1 == x0) 0.0 else (x - x0) / (x1 - x0)
                    return (y0 + t * (y1 - y0)).coerceIn(0.0, 1.0)
                }
            }
            return anchors.last().second.coerceIn(0.0, 1.0)
        }

        fun apply(lin: DoubleArray): DoubleArray {
            if (!isReady()) return lin
            val rA = buildAnchors(0)
            val gA = buildAnchors(1)
            val bA = buildAnchors(2)
            return doubleArrayOf(
                map1(lin[0], rA),
                map1(lin[1], gA),
                map1(lin[2], bA)
            )
        }
    }

    fun sampleAt(bitmap: Bitmap, x: Float, y: Float, win: Int, cal: Calibration? = null): SampleResult {
        val px = x.roundToInt().coerceIn(0, bitmap.width - 1)
        val py = y.roundToInt().coerceIn(0, bitmap.height - 1)

        val half = win / 2
        val x0 = (px - half).coerceIn(0, bitmap.width - 1)
        val x1 = (px + half).coerceIn(0, bitmap.width - 1)
        val y0 = (py - half).coerceIn(0, bitmap.height - 1)
        val y1 = (py + half).coerceIn(0, bitmap.height - 1)

        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        val arr = IntArray(w * h)
        bitmap.getPixels(arr, 0, w, x0, y0, w, h)

        val center = bitmap.getPixel(px, py)
        val centerLin = srgbIntToLin(center)

        // Collect pixels with weights
        val samples = ArrayList<DoubleArray>(arr.size)
        val weights = ArrayList<Double>(arr.size)

        var clipped = 0
        for (c in arr) {
            val r8 = (c ushr 16) and 255
            val g8 = (c ushr 8) and 255
            val b8 = c and 255
            if (r8 >= 254 && g8 >= 254 && b8 >= 254) clipped++

            val lin = srgb8ToLin(r8, g8, b8)

            // Weight-map: downweight likely specular/highlight
            val luma = 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2]
            val maxCh = max(lin[0], max(lin[1], lin[2]))
            val minCh = min(lin[0], min(lin[1], lin[2]))
            val chroma = maxCh - minCh

            var wgt = 1.0
            if (luma > 0.80 && chroma < 0.08) wgt *= 0.20
            if (luma > 0.92 && chroma < 0.05) wgt *= 0.10

            // Downweight deep shadows a bit (natural light / uneven illumination)
            if (luma < 0.06) wgt *= 0.60
            if (luma < 0.03) wgt *= 0.35

            samples.add(lin)
            weights.add(wgt)
        }

        // Robust trimmed mean around center color to avoid boundary bleed
        val pairs = samples.zip(weights)
            .map { it.first to it.second }
            .sortedBy { sqDist(it.first, centerLin) }

        val keep = max(16, (pairs.size * 0.55).roundToInt())
        val kept = pairs.take(keep)

        // Weighted mean
        var sw = 0.0
        var r = 0.0
        var g = 0.0
        var b = 0.0
        for ((v, wgt) in kept) {
            sw += wgt
            r += v[0] * wgt
            g += v[1] * wgt
            b += v[2] * wgt
        }
        if (sw < 1e-9) {
            // fallback to center pixel
            val lin = centerLin
            return finalize(lin, cal, note = "fallback")
        }
        var linOut = doubleArrayOf(r / sw, g / sw, b / sw)

        // Optional calibration
        if (cal != null && cal.isReady()) {
            linOut = cal.apply(linOut)
        }

        // Quality: based on spread and clipping
        val spread = sqrt(pairs.take(min(32, pairs.size)).map { sqDist(it.first, linOut) }.average())
        val q = (1.0 - (spread / 0.25)).coerceIn(0.0, 1.0) * (1.0 - clipped.toDouble() / arr.size.toDouble()).coerceIn(0.4, 1.0)

        val note = when {
            clipped > arr.size * 0.2 -> "highlights"
            spread > 0.18 -> "mixed area"
            else -> "ok"
        }

        return finalize(linOut, cal, qualityOverride = q, note = note)
    }

    fun sampleInMask(bitmap: Bitmap, maskPoints: List<PointF>, cal: Calibration? = null): SampleResult? {
        if (maskPoints.size < 3) return null
        val path = Path()
        path.moveTo(maskPoints[0].x, maskPoints[0].y)
        for (i in 1 until maskPoints.size) path.lineTo(maskPoints[i].x, maskPoints[i].y)
        path.close()

        val bounds = RectF()
        path.computeBounds(bounds, true)
        val x0 = bounds.left
            .coerceIn(0f, (bitmap.width - 1).toFloat())
            .toInt()

        val y0 = bounds.top
            .coerceIn(0f, (bitmap.height - 1).toFloat())
            .toInt()

        val x1 = bounds.right
            .coerceIn(0f, (bitmap.width - 1).toFloat())
            .toInt()

        val y1 = bounds.bottom
            .coerceIn(0f, (bitmap.height - 1).toFloat())
            .toInt()


        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        if (w <= 0 || h <= 0) return null

        // Rasterize mask for bounding box
        val maskBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val c = Canvas(maskBmp)
        c.translate(-x0.toFloat(), -y0.toFloat())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
        c.drawPath(path, p)

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, x0, y0, w, h)
        val mask = ByteArray(w * h)
        maskBmp.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(mask))

        val samples = ArrayList<DoubleArray>(w * h)
        val weights = ArrayList<Double>(w * h)
        var count = 0
        var clipped = 0

        for (i in pixels.indices) {
            val a = mask[i].toInt() and 0xFF
            if (a < 8) continue
            val c0 = pixels[i]
            val r8 = (c0 ushr 16) and 255
            val g8 = (c0 ushr 8) and 255
            val b8 = c0 and 255
            if (r8 >= 254 && g8 >= 254 && b8 >= 254) clipped++

            val lin = srgb8ToLin(r8, g8, b8)
            val luma = 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2]
            val maxCh = max(lin[0], max(lin[1], lin[2]))
            val minCh = min(lin[0], min(lin[1], lin[2]))
            val chroma = maxCh - minCh

            var wgt = (a / 255.0)
            if (luma > 0.80 && chroma < 0.08) wgt *= 0.20
            if (luma < 0.06) wgt *= 0.70

            samples.add(lin)
            weights.add(wgt)
            count++
        }

        if (count < 32) return null

        // Outlier rejection using median distance (cheap)
        val med = channelMedian(samples)
        val sorted = samples.zip(weights)
            .sortedBy { sqDist(it.first, med) }
        val keep = max(64, (sorted.size * 0.65).roundToInt())
        val kept = sorted.take(keep)

        var sw = 0.0
        var r = 0.0
        var g = 0.0
        var b = 0.0
        for ((v, wgt) in kept) {
            sw += wgt
            r += v[0] * wgt
            g += v[1] * wgt
            b += v[2] * wgt
        }
        if (sw < 1e-9) return null

        var linOut = doubleArrayOf(r / sw, g / sw, b / sw)
        if (cal != null && cal.isReady()) linOut = cal.apply(linOut)

        val spread = sqrt(kept.take(128).map { sqDist(it.first, linOut) }.average())
        val q = (1.0 - (spread / 0.25)).coerceIn(0.0, 1.0) * (1.0 - clipped.toDouble() / count.toDouble()).coerceIn(0.4, 1.0)
        val note = if (spread > 0.20) "textured" else "ok"

        return finalize(linOut, cal, qualityOverride = q, note = note)
    }

    private fun finalize(lin: DoubleArray, cal: Calibration?, qualityOverride: Double? = null, note: String): SampleResult {
        val linClamped = doubleArrayOf(lin[0].coerceIn(0.0, 1.0), lin[1].coerceIn(0.0, 1.0), lin[2].coerceIn(0.0, 1.0))
        val srgb = linToSrgb8(linClamped)
        val hex = String.format("#%02X%02X%02X", srgb[0], srgb[1], srgb[2])
        val lab = linToLabD50(linClamped)
        val q = qualityOverride ?: 0.75
        return SampleResult(linClamped, srgb, hex, lab, q, note)
    }

    // ---------- math helpers ----------

    private fun srgb8ToLin(r8: Int, g8: Int, b8: Int): DoubleArray = doubleArrayOf(srgbToLin01(r8 / 255.0), srgbToLin01(g8 / 255.0), srgbToLin01(b8 / 255.0))

    private fun srgbIntToLin(c: Int): DoubleArray {
        val r8 = (c ushr 16) and 255
        val g8 = (c ushr 8) and 255
        val b8 = c and 255
        return srgb8ToLin(r8, g8, b8)
    }

    private fun srgbToLin01(v: Double): Double = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    private fun linToSrgb01(v: Double): Double = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1.0 / 2.4) - 0.055

    private fun linToSrgb8(lin: DoubleArray): IntArray {
        val r = (linToSrgb01(lin[0]).coerceIn(0.0, 1.0) * 255.0).roundToInt()
        val g = (linToSrgb01(lin[1]).coerceIn(0.0, 1.0) * 255.0).roundToInt()
        val b = (linToSrgb01(lin[2]).coerceIn(0.0, 1.0) * 255.0).roundToInt()
        return intArrayOf(r, g, b)
    }

    private fun sqDist(a: DoubleArray, b: DoubleArray): Double {
        val dr = a[0] - b[0]
        val dg = a[1] - b[1]
        val db = a[2] - b[2]
        return dr * dr + dg * dg + db * db
    }

    private fun channelMedian(samples: List<DoubleArray>): DoubleArray {
        fun med(i: Int): Double {
            val v = samples.map { it[i] }.sorted()
            return v[v.size / 2]
        }
        return doubleArrayOf(med(0), med(1), med(2))
    }

    private fun linToLabD50(lin: DoubleArray): DoubleArray {
        // linear sRGB (D65) -> XYZ D65
        val r = lin[0]
        val g = lin[1]
        val b = lin[2]
        val X = 0.4124564 * r + 0.3575761 * g + 0.1804375 * b
        val Y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
        val Z = 0.0193339 * r + 0.1191920 * g + 0.9503041 * b

        // Bradford adaptation D65 -> D50
        val (Xd, Yd, Zd) = bradfordD65ToD50(X, Y, Z)

        // Lab D50
        val Xn = 0.96422
        val Yn = 1.0
        val Zn = 0.82521

        val fx = fLab(Xd / Xn)
        val fy = fLab(Yd / Yn)
        val fz = fLab(Zd / Zn)

        val L = 116.0 * fy - 16.0
        val a = 500.0 * (fx - fy)
        val bb = 200.0 * (fy - fz)
        return doubleArrayOf(L, a, bb)
    }

    private fun fLab(t: Double): Double {
        val d = 6.0 / 29.0
        return if (t > d * d * d) t.pow(1.0 / 3.0) else (t / (3 * d * d) + 4.0 / 29.0)
    }

    private fun bradfordD65ToD50(X: Double, Y: Double, Z: Double): Triple<Double, Double, Double> {
        // Bradford matrix
        val m = arrayOf(
            doubleArrayOf( 0.8951, 0.2664, -0.1614),
            doubleArrayOf(-0.7502, 1.7135,  0.0367),
            doubleArrayOf( 0.0389,-0.0685,  1.0296)
        )
        val mi = arrayOf(
            doubleArrayOf( 0.9869929, -0.1470543, 0.1599627),
            doubleArrayOf( 0.4323053,  0.5183603, 0.0492912),
            doubleArrayOf(-0.0085287,  0.0400428, 0.9684867)
        )
        val src = doubleArrayOf(X, Y, Z)
        val lms = mul(m, src)
        // white points
        val wpD65 = doubleArrayOf(0.95047, 1.0, 1.08883)
        val wpD50 = doubleArrayOf(0.96422, 1.0, 0.82521)
        val lms65 = mul(m, wpD65)
        val lms50 = mul(m, wpD50)
        val s = doubleArrayOf(
            lms50[0] / lms65[0],
            lms50[1] / lms65[1],
            lms50[2] / lms65[2]
        )
        val lmsAdapted = doubleArrayOf(lms[0] * s[0], lms[1] * s[1], lms[2] * s[2])
        val xyz = mul(mi, lmsAdapted)
        return Triple(xyz[0], xyz[1], xyz[2])
    }

    private fun mul(a: Array<DoubleArray>, v: DoubleArray): DoubleArray {
        return doubleArrayOf(
            a[0][0] * v[0] + a[0][1] * v[1] + a[0][2] * v[2],
            a[1][0] * v[0] + a[1][1] * v[1] + a[1][2] * v[2],
            a[2][0] * v[0] + a[2][1] * v[1] + a[2][2] * v[2]
        )
    }

    private fun Double.floorToInt(): Int = floor(this).toInt()
    private fun Double.ceilToInt(): Int = ceil(this).toInt()
}
