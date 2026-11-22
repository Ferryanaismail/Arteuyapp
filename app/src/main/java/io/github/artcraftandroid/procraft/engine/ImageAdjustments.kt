package io.github.artcraftandroid.procraft.engine

import android.graphics.*
import io.github.artcraftandroid.procraft.model.AdjustmentKind
import io.github.artcraftandroid.procraft.model.CurveChannel
import io.github.artcraftandroid.procraft.model.GradientPreset
import io.github.artcraftandroid.procraft.model.LiquifyMode
import kotlin.math.*
import kotlin.random.Random

data class HsbParams(
    val hueDeg: Float = 0f,       // -180..180
    val saturation: Float = 0.5f, // 0..1 (0.5 neutral)
    val brightness: Float = 0.5f, // 0..1 (0.5 neutral)
)

data class BalanceParams(
    val cyanRed: Float = 0f,     // -1..1
    val magentaGreen: Float = 0f,// -1..1
    val yellowBlue: Float = 0f,  // -1..1
)

object ImageAdjustments {

    fun applyFilter(
        source: Bitmap,
        kind: AdjustmentKind,
        amount: Float,
        hsb: HsbParams = HsbParams(),
        balance: BalanceParams = BalanceParams(),
        curvesPoints: List<Float> = listOf(0f, 0.25f, 0.5f, 0.75f, 1f),
        curveChannel: CurveChannel = CurveChannel.ALL,
        gradient: GradientPreset = BrushLibraryCatalog.gradientPresets().first(),
        blurAngleDeg: Float = 0f,
    ): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        when (kind) {
            AdjustmentKind.HSB -> applyHsb(out, hsb)
            AdjustmentKind.COLOR_BALANCE -> applyBalance(out, balance)
            AdjustmentKind.CURVES -> applyCurves(out, curvesPoints, curveChannel)
            AdjustmentKind.GRADIENT_MAP -> applyGradientMap(out, gradient, amount)
            AdjustmentKind.GAUSSIAN_BLUR -> {
                val radius = (amount * 26f).roundToInt().coerceIn(0, 26)
                if (radius > 0) boxBlurInPlace(out, radius, passes = 3)
            }
            AdjustmentKind.MOTION_BLUR -> applyMotionBlur(source, out, amount, blurAngleDeg)
            AdjustmentKind.PERSPECTIVE_BLUR -> applyPerspectiveBlur(source, out, amount)
            AdjustmentKind.NOISE -> applyNoise(out, amount)
            AdjustmentKind.SHARPEN -> applySharpen(source, out, amount)
            AdjustmentKind.BLOOM -> applyBloom(source, out, amount)
            AdjustmentKind.GLITCH -> applyGlitch(source, out, amount)
            AdjustmentKind.HALFTONE -> applyHalftone(source, out, amount)
            AdjustmentKind.CHROMATIC_ABERRATION -> applyChromaticAberration(source, out, amount)
            AdjustmentKind.LIQUIFY, AdjustmentKind.CLONE -> Unit
        }
        return out
    }

    fun blendOriginalAndFilteredByMask(
        original: Bitmap,
        filtered: Bitmap,
        maskAlpha: ByteArray,
        target: Bitmap,
    ) {
        val w = target.width
        val h = target.height
        val total = w * h
        if (maskAlpha.size != total) return
        val oPx = IntArray(total)
        val fPx = IntArray(total)
        val dPx = IntArray(total)
        original.getPixels(oPx, 0, w, 0, 0, w, h)
        filtered.getPixels(fPx, 0, w, 0, 0, w, h)
        for (i in 0 until total) {
            val m = maskAlpha[i].toInt() and 0xFF
            if (m <= 0) {
                dPx[i] = oPx[i]
            } else if (m >= 254) {
                dPx[i] = fPx[i]
            } else {
                val k = m / 255f
                val inv = 1f - k
                val c0 = oPx[i]
                val c1 = fPx[i]
                val a = (Color.alpha(c0) * inv + Color.alpha(c1) * k).roundToInt().coerceIn(0, 255)
                val r = (Color.red(c0) * inv + Color.red(c1) * k).roundToInt().coerceIn(0, 255)
                val g = (Color.green(c0) * inv + Color.green(c1) * k).roundToInt().coerceIn(0, 255)
                val b = (Color.blue(c0) * inv + Color.blue(c1) * k).roundToInt().coerceIn(0, 255)
                dPx[i] = Color.argb(a, r, g, b)
            }
        }
        target.setPixels(dPx, 0, w, 0, 0, w, h)
    }

    private fun applyHsb(bmp: Bitmap, p: HsbParams) {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val hsv = FloatArray(3)
        val satMul = (p.saturation * 2f).coerceIn(0f, 2f)
        val valShift = (p.brightness - 0.5f) * 1.2f
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = Color.alpha(c)
            if (a == 0) continue
            Color.colorToHSV(c, hsv)
            hsv[0] = ((hsv[0] + p.hueDeg) % 360f + 360f) % 360f
            hsv[1] = (hsv[1] * satMul).coerceIn(0f, 1f)
            hsv[2] = (hsv[2] + valShift).coerceIn(0f, 1f)
            pixels[i] = Color.HSVToColor(a, hsv)
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun applyBalance(bmp: Bitmap, p: BalanceParams) {
        val cm = ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, p.cyanRed * 90f,
                0f, 1f, 0f, 0f, p.magentaGreen * 90f,
                0f, 0f, 1f, 0f, p.yellowBlue * 90f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        val c = Canvas(bmp)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        c.drawBitmap(copy, 0f, 0f, paint)
        copy.recycle()
    }

    private fun applyCurves(bmp: Bitmap, pts: List<Float>, channel: CurveChannel) {
        if (pts.size < 2) return
        // Catmull-Rom spline interpolation for smooth tonal curve LUT
        val lut = IntArray(256) { v ->
            val tNorm = v / 255f
            val n = pts.size - 1
            val pos = tNorm * n
            val seg = pos.toInt().coerceIn(0, n - 1)
            val t = pos - seg
            val p0 = pts[(seg - 1).coerceAtLeast(0)]
            val p1 = pts[seg]
            val p2 = pts[(seg + 1).coerceAtMost(n)]
            val p3 = pts[(seg + 2).coerceAtMost(n)]
            val t2 = t * t
            val t3 = t2 * t
            val out = 0.5f * (
                (2f * p1) +
                    (-p0 + p2) * t +
                    (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 +
                    (-p0 + 3f * p1 - 3f * p2 + p3) * t3
                )
            (out * 255f).roundToInt().coerceIn(0, 255)
        }
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = c ushr 24
            if (a == 0) continue
            val rIn = (c shr 16) and 0xFF
            val gIn = (c shr 8) and 0xFF
            val bIn = c and 0xFF
            val r = if (channel == CurveChannel.ALL || channel == CurveChannel.RED) lut[rIn] else rIn
            val g = if (channel == CurveChannel.ALL || channel == CurveChannel.GREEN) lut[gIn] else gIn
            val b = if (channel == CurveChannel.ALL || channel == CurveChannel.BLUE) lut[bIn] else bIn
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun applyGradientMap(bmp: Bitmap, gradient: GradientPreset, strength: Float) {
        val stops = gradient.colors
        if (stops.size < 2) return
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val mix = strength.coerceIn(0f, 1f)
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = Color.alpha(c)
            if (a == 0) continue
            val lum = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
            val scaled = lum * (stops.size - 1)
            val idx = scaled.toInt().coerceIn(0, stops.size - 2)
            val f = scaled - idx
            val c0 = stops[idx]
            val c1 = stops[idx + 1]
            val mr = (Color.red(c0) + (Color.red(c1) - Color.red(c0)) * f)
            val mg = (Color.green(c0) + (Color.green(c1) - Color.green(c0)) * f)
            val mb = (Color.blue(c0) + (Color.blue(c1) - Color.blue(c0)) * f)
            val fr = (Color.red(c) * (1f - mix) + mr * mix).roundToInt().coerceIn(0, 255)
            val fg = (Color.green(c) * (1f - mix) + mg * mix).roundToInt().coerceIn(0, 255)
            val fb = (Color.blue(c) * (1f - mix) + mb * mix).roundToInt().coerceIn(0, 255)
            pixels[i] = Color.argb(a, fr, fg, fb)
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    fun boxBlurInPlace(bmp: Bitmap, radius: Int, passes: Int = 2) {
        if (radius <= 0) return
        val w = bmp.width
        val h = bmp.height
        val src = IntArray(w * h)
        val dst = IntArray(w * h)
        bmp.getPixels(src, 0, w, 0, 0, w, h)
        val div = radius * 2 + 1
        repeat(passes) {
            for (y in 0 until h) {
                var sumA = 0; var sumR = 0; var sumG = 0; var sumB = 0
                val row = y * w
                for (dx in -radius..radius) {
                    val x = dx.coerceIn(0, w - 1)
                    val c = src[row + x]
                    sumA += (c ushr 24) and 0xFF
                    sumR += (c shr 16) and 0xFF
                    sumG += (c shr 8) and 0xFF
                    sumB += c and 0xFF
                }
                for (x in 0 until w) {
                    dst[row + x] = ((sumA / div) shl 24) or ((sumR / div) shl 16) or ((sumG / div) shl 8) or (sumB / div)
                    val rem = (x - radius).coerceIn(0, w - 1)
                    val add = (x + radius + 1).coerceIn(0, w - 1)
                    val cRem = src[row + rem]
                    val cAdd = src[row + add]
                    sumA += ((cAdd ushr 24) and 0xFF) - ((cRem ushr 24) and 0xFF)
                    sumR += ((cAdd shr 16) and 0xFF) - ((cRem shr 16) and 0xFF)
                    sumG += ((cAdd shr 8) and 0xFF) - ((cRem shr 8) and 0xFF)
                    sumB += (cAdd and 0xFF) - (cRem and 0xFF)
                }
            }
            for (x in 0 until w) {
                var sumA = 0; var sumR = 0; var sumG = 0; var sumB = 0
                for (dy in -radius..radius) {
                    val y = dy.coerceIn(0, h - 1)
                    val c = dst[y * w + x]
                    sumA += (c ushr 24) and 0xFF
                    sumR += (c shr 16) and 0xFF
                    sumG += (c shr 8) and 0xFF
                    sumB += c and 0xFF
                }
                for (y in 0 until h) {
                    src[y * w + x] = ((sumA / div) shl 24) or ((sumR / div) shl 16) or ((sumG / div) shl 8) or (sumB / div)
                    val rem = (y - radius).coerceIn(0, h - 1)
                    val add = (y + radius + 1).coerceIn(0, h - 1)
                    val cRem = dst[rem * w + x]
                    val cAdd = dst[add * w + x]
                    sumA += ((cAdd ushr 24) and 0xFF) - ((cRem ushr 24) and 0xFF)
                    sumR += ((cAdd shr 16) and 0xFF) - ((cRem shr 16) and 0xFF)
                    sumG += ((cAdd shr 8) and 0xFF) - ((cRem shr 8) and 0xFF)
                    sumB += (cAdd and 0xFF) - (cRem and 0xFF)
                }
            }
        }
        bmp.setPixels(src, 0, w, 0, 0, w, h)
    }

    private fun applyMotionBlur(source: Bitmap, out: Bitmap, amount: Float, angleDeg: Float) {
        val dist = amount * 34f
        if (dist < 1f) return
        val rad = Math.toRadians(angleDeg.toDouble())
        val dx = cos(rad).toFloat()
        val dy = sin(rad).toFloat()
        val c = Canvas(out)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val taps = 11
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (255f / taps).roundToInt().coerceAtLeast(18) }
        for (i in 0 until taps) {
            val offset = ((i.toFloat() / (taps - 1)) - 0.5f) * 2f * dist
            c.drawBitmap(source, dx * offset, dy * offset, paint)
        }
    }

    private fun applyPerspectiveBlur(source: Bitmap, out: Bitmap, amount: Float) {
        val zoom = amount * 0.25f
        if (zoom < 0.005f) return
        val c = Canvas(out)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val taps = 8
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (255f / taps).roundToInt().coerceAtLeast(24) }
        val cx = source.width * 0.5f
        val cy = source.height * 0.5f
        for (i in 0 until taps) {
            val s = 1f + (i.toFloat() / (taps - 1)) * zoom
            c.save()
            c.scale(s, s, cx, cy)
            c.drawBitmap(source, 0f, 0f, paint)
            c.restore()
        }
    }

    private fun applyNoise(bmp: Bitmap, amount: Float) {
        val strength = (amount * 110f).roundToInt()
        if (strength <= 0) return
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val rng = Random(42)
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = Color.alpha(c)
            if (a == 0) continue
            val n = rng.nextInt(-strength, strength + 1)
            val r = (Color.red(c) + n).coerceIn(0, 255)
            val g = (Color.green(c) + n).coerceIn(0, 255)
            val b = (Color.blue(c) + n).coerceIn(0, 255)
            pixels[i] = Color.argb(a, r, g, b)
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun applySharpen(source: Bitmap, out: Bitmap, amount: Float) {
        val blurred = source.copy(Bitmap.Config.ARGB_8888, true)
        boxBlurInPlace(blurred, 2, passes = 2)
        val w = source.width
        val h = source.height
        val sPx = IntArray(w * h)
        val bPx = IntArray(w * h)
        source.getPixels(sPx, 0, w, 0, 0, w, h)
        blurred.getPixels(bPx, 0, w, 0, 0, w, h)
        blurred.recycle()
        val k = amount * 2.2f
        for (i in sPx.indices) {
            val c = sPx[i]
            val a = Color.alpha(c)
            if (a == 0) continue
            val bc = bPx[i]
            val r = (Color.red(c) + ((Color.red(c) - Color.red(bc)) * k)).roundToInt().coerceIn(0, 255)
            val g = (Color.green(c) + ((Color.green(c) - Color.green(bc)) * k)).roundToInt().coerceIn(0, 255)
            val b = (Color.blue(c) + ((Color.blue(c) - Color.blue(bc)) * k)).roundToInt().coerceIn(0, 255)
            sPx[i] = Color.argb(a, r, g, b)
        }
        out.setPixels(sPx, 0, w, 0, 0, w, h)
    }

    private fun applyBloom(source: Bitmap, out: Bitmap, amount: Float) {
        val glow = source.copy(Bitmap.Config.ARGB_8888, true)
        boxBlurInPlace(glow, (4 + amount * 14f).roundToInt(), passes = 2)
        val c = Canvas(out)
        val paint = Paint().apply {
            alpha = (amount * 210f).roundToInt().coerceIn(0, 255)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        }
        c.drawBitmap(glow, 0f, 0f, paint)
        glow.recycle()
    }

    private fun applyGlitch(source: Bitmap, out: Bitmap, amount: Float) {
        val shift = (amount * 44f).roundToInt()
        if (shift <= 0) return
        val w = source.width
        val h = source.height
        val sPx = IntArray(w * h)
        val dPx = IntArray(w * h)
        source.getPixels(sPx, 0, w, 0, 0, w, h)
        val bandH = max(8, h / 24)
        val rng = Random(1337)
        var bandShift = 0
        for (y in 0 until h) {
            if (y % bandH == 0) {
                bandShift = if (rng.nextFloat() < 0.45f) rng.nextInt(-shift, shift + 1) else 0
            }
            val row = y * w
            for (x in 0 until w) {
                val sxR = (x + bandShift + shift / 2).coerceIn(0, w - 1)
                val sxG = (x + bandShift).coerceIn(0, w - 1)
                val sxB = (x + bandShift - shift / 2).coerceIn(0, w - 1)
                val cR = sPx[row + sxR]
                val cG = sPx[row + sxG]
                val cB = sPx[row + sxB]
                dPx[row + x] = Color.argb(Color.alpha(cG), Color.red(cR), Color.green(cG), Color.blue(cB))
            }
        }
        out.setPixels(dPx, 0, w, 0, 0, w, h)
    }

    private fun applyHalftone(source: Bitmap, out: Bitmap, amount: Float) {
        val cell = (6 + amount * 22f).roundToInt().coerceIn(6, 28)
        val w = source.width
        val h = source.height
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (cy in cell / 2 until h step cell) {
            for (cx in cell / 2 until w step cell) {
                val px = source.getPixel(cx.coerceIn(0, w - 1), cy.coerceIn(0, h - 1))
                val a = Color.alpha(px)
                if (a < 16) continue
                val lum = (0.299f * Color.red(px) + 0.587f * Color.green(px) + 0.114f * Color.blue(px)) / 255f
                val r = (1f - lum) * (cell * 0.52f)
                if (r > 0.5f) {
                    paint.color = Color.argb(a, Color.red(px), Color.green(px), Color.blue(px))
                    c.drawCircle(cx.toFloat(), cy.toFloat(), r, paint)
                }
            }
        }
    }

    private fun applyChromaticAberration(source: Bitmap, out: Bitmap, amount: Float) {
        val offset = (amount * 26f).roundToInt()
        if (offset <= 0) return
        val w = source.width
        val h = source.height
        val sPx = IntArray(w * h)
        val dPx = IntArray(w * h)
        source.getPixels(sPx, 0, w, 0, 0, w, h)
        val cx = w * 0.5f
        val cy = h * 0.5f
        for (y in 0 until h) {
            val row = y * w
            val ny = (y - cy) / max(1f, cy)
            for (x in 0 until w) {
                val nx = (x - cx) / max(1f, cx)
                val rx = (x + (nx * offset).roundToInt()).coerceIn(0, w - 1)
                val ry = (y + (ny * offset).roundToInt()).coerceIn(0, h - 1)
                val bx = (x - (nx * offset).roundToInt()).coerceIn(0, w - 1)
                val by = (y - (ny * offset).roundToInt()).coerceIn(0, h - 1)
                val cR = sPx[ry * w + rx]
                val cG = sPx[row + x]
                val cB = sPx[by * w + bx]
                dPx[row + x] = Color.argb(Color.alpha(cG), Color.red(cR), Color.green(cG), Color.blue(cB))
            }
        }
        out.setPixels(dPx, 0, w, 0, 0, w, h)
    }

    fun applyLiquifyDab(
        bmp: Bitmap,
        mode: LiquifyMode,
        cx: Float,
        cy: Float,
        dx: Float,
        dy: Float,
        radius: Float,
        pressure: Float,
    ) {
        val w = bmp.width
        val h = bmp.height
        val rInt = radius.roundToInt().coerceIn(8, 260)
        val left = (cx - rInt).roundToInt().coerceIn(0, w - 1)
        val top = (cy - rInt).roundToInt().coerceIn(0, h - 1)
        val right = (cx + rInt).roundToInt().coerceIn(0, w - 1)
        val bottom = (cy + rInt).roundToInt().coerceIn(0, h - 1)
        val bw = right - left + 1
        val bh = bottom - top + 1
        if (bw <= 1 || bh <= 1) return

        val src = IntArray(bw * bh)
        val dst = IntArray(bw * bh)
        bmp.getPixels(src, 0, bw, left, top, bw, bh)
        System.arraycopy(src, 0, dst, 0, src.size)

        val rSq = (rInt * rInt).toFloat()
        val strength = pressure.coerceIn(0.15f, 1f) * 0.45f
        for (y in 0 until bh) {
            val py = top + y - cy
            for (x in 0 until bw) {
                val px = left + x - cx
                val dSq = px * px + py * py
                if (dSq >= rSq || dSq < 0.001f) continue
                val d = sqrt(dSq)
                val falloff = (1f - d / rInt).pow(2)
                var sx = left + x.toFloat()
                var sy = top + y.toFloat()
                when (mode) {
                    LiquifyMode.PUSH -> {
                        sx -= dx * falloff
                        sy -= dy * falloff
                    }
                    LiquifyMode.PINCH -> {
                        val k = falloff * strength
                        sx += px * k
                        sy += py * k
                    }
                    LiquifyMode.EXPAND -> {
                        val k = falloff * strength
                        sx -= px * k
                        sy -= py * k
                    }
                    LiquifyMode.TWIRL_CW, LiquifyMode.TWIRL_CCW -> {
                        val dir = if (mode == LiquifyMode.TWIRL_CW) -1f else 1f
                        val ang = dir * falloff * strength * 1.4f
                        val ca = cos(ang)
                        val sa = sin(ang)
                        sx = cx + px * ca - py * sa
                        sy = cy + px * sa + py * ca
                    }
                }
                val ix = (sx - left).roundToInt().coerceIn(0, bw - 1)
                val iy = (sy - top).roundToInt().coerceIn(0, bh - 1)
                dst[y * bw + x] = src[iy * bw + ix]
            }
        }
        bmp.setPixels(dst, 0, bw, left, top, bw, bh)
    }
}
