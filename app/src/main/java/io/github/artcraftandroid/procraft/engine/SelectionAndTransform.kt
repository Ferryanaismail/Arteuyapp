package io.github.artcraftandroid.procraft.engine

import android.graphics.*
import io.github.artcraftandroid.procraft.model.TransformInterpolation
import io.github.artcraftandroid.procraft.model.TransformKind
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class SelectionMask(val width: Int, val height: Int) {
    val alpha = ByteArray(width * height)

    fun isEmpty(): Boolean = alpha.none { (it.toInt() and 0xFF) > 0 }

    fun clear() {
        alpha.fill(0)
    }

    fun invert() {
        for (i in alpha.indices) {
            val v = alpha[i].toInt() and 0xFF
            alpha[i] = (255 - v).toByte()
        }
    }

    fun selectFromBitmapAlpha(source: Bitmap) {
        val total = width * height
        val px = IntArray(total)
        source.getPixels(px, 0, width, 0, 0, width, height)
        for (i in 0 until total) {
            alpha[i] = ((px[i] ushr 24) and 0xFF).toByte()
        }
    }

    fun addRect(left: Float, top: Float, right: Float, bottom: Float, subtract: Boolean) {
        val x0 = min(left, right).toInt().coerceIn(0, width - 1)
        val x1 = max(left, right).toInt().coerceIn(0, width - 1)
        val y0 = min(top, bottom).toInt().coerceIn(0, height - 1)
        val y1 = max(top, bottom).toInt().coerceIn(0, height - 1)
        val v = if (subtract) 0.toByte() else 255.toByte()
        for (y in y0..y1) {
            val row = y * width
            for (x in x0..x1) {
                alpha[row + x] = v
            }
        }
    }

    fun addEllipse(left: Float, top: Float, right: Float, bottom: Float, subtract: Boolean) {
        val cx = (left + right) * 0.5f
        val cy = (top + bottom) * 0.5f
        val rx = max(1f, abs(right - left) * 0.5f)
        val ry = max(1f, abs(bottom - top) * 0.5f)
        val x0 = min(left, right).toInt().coerceIn(0, width - 1)
        val x1 = max(left, right).toInt().coerceIn(0, width - 1)
        val y0 = min(top, bottom).toInt().coerceIn(0, height - 1)
        val y1 = max(top, bottom).toInt().coerceIn(0, height - 1)
        val v = if (subtract) 0.toByte() else 255.toByte()
        for (y in y0..y1) {
            val ny = (y - cy) / ry
            val row = y * width
            for (x in x0..x1) {
                val nx = (x - cx) / rx
                if (nx * nx + ny * ny <= 1f) {
                    alpha[row + x] = v
                }
            }
        }
    }

    fun addPolygon(points: List<Pair<Float, Float>>, subtract: Boolean) {
        if (points.size < 3) return
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bmp)
        val path = Path().apply {
            moveTo(points.first().first, points.first().second)
            for (i in 1 until points.size) {
                lineTo(points[i].first, points[i].second)
            }
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        canvas.drawPath(path, paint)
        val buf = java.nio.ByteBuffer.allocate(width * height)
        bmp.copyPixelsToBuffer(buf)
        val drawn = buf.array()
        bmp.recycle()
        for (i in alpha.indices) {
            val d = drawn[i].toInt() and 0xFF
            if (d > 16) {
                alpha[i] = if (subtract) 0.toByte() else max(alpha[i].toInt() and 0xFF, d).toByte()
            }
        }
    }

    fun addFloodSelect(bmp: Bitmap, startX: Int, startY: Int, tolerance: Float, subtract: Boolean) {
        if (startX !in 0 until width || startY !in 0 until height) return
        val pixels = IntArray(width * height)
        bmp.getPixels(pixels, 0, width, 0, 0, width, height)
        val seed = pixels[startY * width + startX]
        val sr = Color.red(seed)
        val sg = Color.green(seed)
        val sb = Color.blue(seed)
        val sa = Color.alpha(seed)
        val maxDist = (tolerance.coerceIn(0.03f, 0.9f) * 441.67f).toInt()
        val visited = BooleanArray(width * height)
        val sx = IntArray(width * height)
        val sy = IntArray(width * height)
        var top = 0
        sx[top] = startX
        sy[top] = startY
        top++
        visited[startY * width + startX] = true
        val v = if (subtract) 0.toByte() else 255.toByte()

        fun match(idx: Int): Boolean {
            val c = pixels[idx]
            if (abs(Color.alpha(c) - sa) > maxDist) return false
            val dr = Color.red(c) - sr
            val dg = Color.green(c) - sg
            val db = Color.blue(c) - sb
            return dr * dr + dg * dg + db * db <= maxDist * maxDist
        }

        while (top > 0) {
            top--
            val cx = sx[top]
            val cy = sy[top]
            val row = cy * width
            alpha[row + cx] = v
            if (cx > 0 && !visited[row + cx - 1] && match(row + cx - 1)) {
                visited[row + cx - 1] = true; sx[top] = cx - 1; sy[top] = cy; top++
            }
            if (cx < width - 1 && !visited[row + cx + 1] && match(row + cx + 1)) {
                visited[row + cx + 1] = true; sx[top] = cx + 1; sy[top] = cy; top++
            }
            if (cy > 0 && !visited[(cy - 1) * width + cx] && match((cy - 1) * width + cx)) {
                visited[(cy - 1) * width + cx] = true; sx[top] = cx; sy[top] = cy - 1; top++
            }
            if (cy < height - 1 && !visited[(cy + 1) * width + cx] && match((cy + 1) * width + cx)) {
                visited[(cy + 1) * width + cx] = true; sx[top] = cx; sy[top] = cy + 1; top++
            }
        }
    }

    fun feather(radius: Int) {
        if (radius <= 0) return
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height) { i ->
            val a = alpha[i].toInt() and 0xFF
            Color.argb(a, a, a, a)
        }
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        ImageAdjustments.boxBlurInPlace(bmp, radius.coerceIn(1, 24), passes = 2)
        bmp.getPixels(pixels, 0, width, 0, 0, width, height)
        bmp.recycle()
        for (i in alpha.indices) {
            alpha[i] = Color.alpha(pixels[i]).toByte()
        }
    }

    fun toOverlayBitmap(): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val stripeCol = 0x552979FF
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val sel = alpha[row + x].toInt() and 0xFF
                if (sel == 0) {
                    val hatch = ((x + y) / 6) % 2 == 0
                    pixels[row + x] = if (hatch) stripeCol else 0x222979FF
                } else {
                    pixels[row + x] = 0
                }
            }
        }
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }

    fun fillBitmap(target: Bitmap, colorRgb: Int) {
        val pixels = IntArray(width * height)
        target.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val sel = alpha[i].toInt() and 0xFF
            if (sel > 127) {
                pixels[i] = colorRgb
            }
        }
        target.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    fun clearBitmap(target: Bitmap) {
        val pixels = IntArray(width * height)
        target.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val sel = alpha[i].toInt() and 0xFF
            if (sel > 127) {
                pixels[i] = Color.TRANSPARENT
            }
        }
        target.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    fun computeBounds(): RectF? {
        var minX = width
        var maxX = -1
        var minY = height
        var maxY = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if ((alpha[row + x].toInt() and 0xFF) > 16) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) return null
        return RectF(minX.toFloat(), minY.toFloat(), (maxX + 1).toFloat(), (maxY + 1).toFloat())
    }

    fun extractCopy(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val sPx = IntArray(width * height)
        val dPx = IntArray(width * height)
        source.getPixels(sPx, 0, width, 0, 0, width, height)
        for (i in sPx.indices) {
            val sel = alpha[i].toInt() and 0xFF
            if (sel > 16) {
                val c = sPx[i]
                val na = (Color.alpha(c) * sel) / 255
                dPx[i] = (na shl 24) or (c and 0x00FFFFFF)
            }
        }
        out.setPixels(dPx, 0, width, 0, 0, width, height)
        return out
    }
}

data class LayerTransformState(
    val kind: TransformKind = TransformKind.UNIFORM,
    val interpolation: TransformInterpolation = TransformInterpolation.BILINEAR,
    val translateX: Float = 0f,
    val translateY: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotationDeg: Float = 0f,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val snapping: Boolean = true,
    // Movable pivot/anchor in source pixel coordinates (null = bounds center)
    val anchorX: Float? = null,
    val anchorY: Float? = null,
    // Optional selection/content bounds in source pixels (null = full canvas)
    val boundsLeft: Float? = null,
    val boundsTop: Float? = null,
    val boundsRight: Float? = null,
    val boundsBottom: Float? = null,
    // 4 corners distort offsets in pixels (TL, TR, BR, BL)
    val cornerOffsets: List<Pair<Float, Float>> = listOf(0f to 0f, 0f to 0f, 0f to 0f, 0f to 0f),
    // 9 mesh control offsets in pixels for 3x3 WARP (row-major 0..8)
    val warpOffsets: List<Pair<Float, Float>> = List(9) { 0f to 0f },
) {
    fun effectiveBounds(w: Float, h: Float): RectF {
        val l = boundsLeft ?: 0f
        val t = boundsTop ?: 0f
        val r = boundsRight ?: w
        val b = boundsBottom ?: h
        return RectF(l, t, r.coerceAtLeast(l + 2f), b.coerceAtLeast(t + 2f))
    }

    fun pivotX(w: Float, h: Float): Float = anchorX ?: effectiveBounds(w, h).centerX()

    fun pivotY(w: Float, h: Float): Float = anchorY ?: effectiveBounds(w, h).centerY()

    fun buildMatrix(cx: Float, cy: Float): Matrix {
        val ax = anchorX ?: cx
        val ay = anchorY ?: cy
        return Matrix().apply {
            postTranslate(-ax, -ay)
            postScale(if (flipH) -scaleX else scaleX, if (flipV) -scaleY else scaleY)
            postRotate(rotationDeg)
            postTranslate(ax + translateX, ay + translateY)
        }
    }

    fun worldAnchor(w: Float, h: Float): PointF {
        val bounds = effectiveBounds(w, h)
        val ax = anchorX ?: bounds.centerX()
        val ay = anchorY ?: bounds.centerY()
        return PointF(ax + translateX, ay + translateY)
    }

    fun hasCornerDistortion(): Boolean = cornerOffsets.any { abs(it.first) > 0.5f || abs(it.second) > 0.5f }

    fun hasWarp(): Boolean = warpOffsets.any { abs(it.first) > 0.5f || abs(it.second) > 0.5f }
}

object TransformEngine {
    fun applyTransform(source: Bitmap, t: LayerTransformState): Bitmap {
        val w = source.width
        val h = source.height
        val wf = w.toFloat()
        val hf = h.toFloat()
        val bounds = t.effectiveBounds(wf, hf)
        val defaultCx = bounds.centerX()
        val defaultCy = bounds.centerY()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val filter = t.interpolation == TransformInterpolation.BILINEAR
        val paint = Paint(if (filter) (Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) else 0)

        if (t.kind == TransformKind.WARP && t.hasWarp()) {
            val matrix = t.buildMatrix(defaultCx, defaultCy)
            val verts = FloatArray(9 * 2)
            for (r in 0..2) {
                for (col in 0..2) {
                    val idx = r * 3 + col
                    val off = t.warpOffsets.getOrElse(idx) { 0f to 0f }
                    verts[idx * 2] = col * (wf * 0.5f) + off.first
                    verts[idx * 2 + 1] = r * (hf * 0.5f) + off.second
                }
            }
            c.save()
            c.concat(matrix)
            c.drawBitmapMesh(source, 2, 2, verts, 0, null, 0, paint)
            c.restore()
            return out
        }

        if (t.kind == TransformKind.DISTORT && t.hasCornerDistortion()) {
            val matrix = t.buildMatrix(defaultCx, defaultCy)
            val bl = bounds.left
            val bt = bounds.top
            val br = bounds.right
            val bb = bounds.bottom
            val src = floatArrayOf(bl, bt, br, bt, br, bb, bl, bb)
            val tl = t.cornerOffsets[0]
            val tr = t.cornerOffsets[1]
            val cbr = t.cornerOffsets[2]
            val cbl = t.cornerOffsets[3]
            val dst = floatArrayOf(
                bl + tl.first, bt + tl.second,
                br + tr.first, bt + tr.second,
                br + cbr.first, bb + cbr.second,
                bl + cbl.first, bb + cbl.second,
            )
            val poly = Matrix().apply { setPolyToPoly(src, 0, dst, 0, 4) }
            poly.postConcat(matrix)
            c.drawBitmap(source, poly, paint)
            return out
        }

        val matrix = t.buildMatrix(defaultCx, defaultCy)
        c.drawBitmap(source, matrix, paint)
        return out
    }

    fun computeBitmapBounds(bmp: Bitmap): RectF? {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w
        var maxX = -1
        var minY = h
        var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((px[row + x] ushr 24) > 8) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) return null
        return RectF(minX.toFloat(), minY.toFloat(), (maxX + 1).toFloat(), (maxY + 1).toFloat())
    }

    fun interpolateTransform(
        start: LayerTransformState,
        end: LayerTransformState,
        easedT: Float,
    ): LayerTransformState {
        fun lerpF(a: Float, b: Float) = a + (b - a) * easedT
        val ax = when {
            start.anchorX != null && end.anchorX != null -> lerpF(start.anchorX, end.anchorX)
            else -> end.anchorX ?: start.anchorX
        }
        val ay = when {
            start.anchorY != null && end.anchorY != null -> lerpF(start.anchorY, end.anchorY)
            else -> end.anchorY ?: start.anchorY
        }
        val interpCorners = (0 until 4).map { i ->
            val ca = start.cornerOffsets.getOrElse(i) { 0f to 0f }
            val cb = end.cornerOffsets.getOrElse(i) { 0f to 0f }
            lerpF(ca.first, cb.first) to lerpF(ca.second, cb.second)
        }
        val interpWarp = (0 until 9).map { i ->
            val wa = start.warpOffsets.getOrElse(i) { 0f to 0f }
            val wb = end.warpOffsets.getOrElse(i) { 0f to 0f }
            lerpF(wa.first, wb.first) to lerpF(wa.second, wb.second)
        }
        return end.copy(
            translateX = lerpF(start.translateX, end.translateX),
            translateY = lerpF(start.translateY, end.translateY),
            scaleX = lerpF(start.scaleX, end.scaleX).coerceAtLeast(0.05f),
            scaleY = lerpF(start.scaleY, end.scaleY).coerceAtLeast(0.05f),
            rotationDeg = lerpF(start.rotationDeg, end.rotationDeg),
            anchorX = ax,
            anchorY = ay,
            cornerOffsets = interpCorners,
            warpOffsets = interpWarp,
        )
    }

    fun fitToCanvas(bmp: Bitmap): LayerTransformState {
        val w = bmp.width
        val h = bmp.height
        val bounds = computeBitmapBounds(bmp) ?: return LayerTransformState()
        val bw = bounds.width().coerceAtLeast(1f)
        val bh = bounds.height().coerceAtLeast(1f)
        val scale = min(w / bw, h / bh)
        val bCx = bounds.centerX()
        val bCy = bounds.centerY()
        return LayerTransformState(
            scaleX = scale,
            scaleY = scale,
            translateX = (w * 0.5f - bCx) * scale,
            translateY = (h * 0.5f - bCy) * scale,
            anchorX = bCx,
            anchorY = bCy,
            boundsLeft = bounds.left,
            boundsTop = bounds.top,
            boundsRight = bounds.right,
            boundsBottom = bounds.bottom,
        )
    }
}
