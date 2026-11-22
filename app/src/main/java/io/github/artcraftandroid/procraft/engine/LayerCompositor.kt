package io.github.artcraftandroid.procraft.engine

import android.graphics.*
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import io.github.artcraftandroid.procraft.model.AnimationState
import io.github.artcraftandroid.procraft.model.CanvasAssistMode
import io.github.artcraftandroid.procraft.model.LayerBlendMode
import io.github.artcraftandroid.procraft.model.LayerState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class OnionSkinFrame(
    val bitmap: Bitmap,
    val stepOffset: Int,
)

class DisplayTile(
    val tx: Int,
    val ty: Int,
    val left: Int,
    val top: Int,
    val tileW: Int,
    val tileH: Int,
) {
    val bitmap: Bitmap = Bitmap.createBitmap(tileW, tileH, Bitmap.Config.ARGB_8888)
    val canvas: Canvas = Canvas(bitmap)
    var version by mutableIntStateOf(0)

    fun recycle() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

class CanvasTileCache(
    val width: Int,
    val height: Int,
    val tileSize: Int = TILE_SIZE,
) {
    companion object {
        const val TILE_SIZE = 256
    }

    val cols: Int = ((width + tileSize - 1) / tileSize).coerceAtLeast(1)
    val rows: Int = ((height + tileSize - 1) / tileSize).coerceAtLeast(1)
    val tiles: Array<DisplayTile> = Array(cols * rows) { idx ->
        val tx = idx % cols
        val ty = idx / cols
        val left = tx * tileSize
        val top = ty * tileSize
        val tw = min(tileSize, width - left).coerceAtLeast(1)
        val th = min(tileSize, height - top).coerceAtLeast(1)
        DisplayTile(tx, ty, left, top, tw, th)
    }

    private val scratchTile: Bitmap = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
    private val scratchCanvas: Canvas = Canvas(scratchTile)
    private val layerPaint = Paint()
    private val clipPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val onionPaint = Paint()
    private val srcRect = Rect()
    private val dstRect = Rect()

    fun recompositeAll(
        layers: List<LayerState>,
        backgroundRgb: Int,
        backgroundVisible: Boolean,
        animation: AnimationState,
        onionSkins: List<OnionSkinFrame> = emptyList(),
    ) {
        for (tile in tiles) {
            renderTile(tile, layers, backgroundRgb, backgroundVisible, animation, onionSkins)
        }
    }

    fun recompositeRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        layers: List<LayerState>,
        backgroundRgb: Int,
        backgroundVisible: Boolean,
        animation: AnimationState,
        onionSkins: List<OnionSkinFrame> = emptyList(),
    ) {
        val x0 = left.toInt().coerceIn(0, width - 1)
        val y0 = top.toInt().coerceIn(0, height - 1)
        val x1 = right.toInt().coerceIn(0, width - 1)
        val y1 = bottom.toInt().coerceIn(0, height - 1)
        if (x1 < x0 || y1 < y0) return

        val tx0 = (x0 / tileSize).coerceIn(0, cols - 1)
        val tx1 = (x1 / tileSize).coerceIn(0, cols - 1)
        val ty0 = (y0 / tileSize).coerceIn(0, rows - 1)
        val ty1 = (y1 / tileSize).coerceIn(0, rows - 1)
        for (ty in ty0..ty1) {
            val rowOff = ty * cols
            for (tx in tx0..tx1) {
                renderTile(tiles[rowOff + tx], layers, backgroundRgb, backgroundVisible, animation, onionSkins)
            }
        }
    }

    fun recompositeTiles(
        tileIndices: Collection<Int>,
        layers: List<LayerState>,
        backgroundRgb: Int,
        backgroundVisible: Boolean,
        animation: AnimationState,
        onionSkins: List<OnionSkinFrame> = emptyList(),
    ) {
        for (idx in tileIndices) {
            if (idx in tiles.indices) {
                renderTile(tiles[idx], layers, backgroundRgb, backgroundVisible, animation, onionSkins)
            }
        }
    }

    private fun renderTile(
        tile: DisplayTile,
        layers: List<LayerState>,
        backgroundRgb: Int,
        backgroundVisible: Boolean,
        animation: AnimationState,
        onionSkins: List<OnionSkinFrame> = emptyList(),
    ) {
        val c = tile.canvas
        if (backgroundVisible) {
            c.drawColor(backgroundRgb)
        } else {
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        }

        srcRect.set(tile.left, tile.top, tile.left + tile.tileW, tile.top + tile.tileH)
        dstRect.set(0, 0, tile.tileW, tile.tileH)

        if (animation.enabled && animation.assistMode == CanvasAssistMode.PAGE_ASSIST && layers.isNotEmpty()) {
            val activeIdx = animation.currentFrameIndex.coerceIn(0, layers.lastIndex)
            val activeLayer = layers[activeIdx]
            if (activeLayer.visible) {
                drawLayerSlice(c, activeLayer.bitmap, activeLayer.opacity, activeLayer.blendMode)
            }
            tile.version++
            return
        }

        if (animation.isAnimation && animation.onionSkinEnabled && onionSkins.isNotEmpty()) {
            for (skin in onionSkins) {
                if (skin.bitmap.isRecycled) continue
                val dist = abs(skin.stepOffset).coerceAtLeast(1)
                val tint = if (skin.stepOffset < 0) 0xFFE5484D.toInt() else 0xFF30A46C.toInt()
                val alpha = (animation.onionSkinOpacity / dist * 255f).toInt().coerceIn(10, 180)
                onionPaint.reset()
                onionPaint.alpha = alpha
                onionPaint.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_ATOP)
                c.drawBitmap(skin.bitmap, srcRect, dstRect, onionPaint)
            }
        }

        var i = 0
        while (i < layers.size) {
            val layer = layers[i]
            if (!layer.visible || layer.isMask || layer.bitmap.isRecycled) {
                i++
                continue
            }
            // Check if the next visible layer is a Layer Mask attached to this base layer
            val maskAbove = if (i + 1 < layers.size && layers[i + 1].isMask && layers[i + 1].visible && !layers[i + 1].bitmap.isRecycled) layers[i + 1] else null

            if (maskAbove != null) {
                scratchCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                scratchCanvas.drawBitmap(layer.bitmap, srcRect, dstRect, null)
                scratchCanvas.drawBitmap(maskAbove.bitmap, srcRect, dstRect, clipPaint)
                drawSliceWithRect(c, scratchTile, dstRect, dstRect, layer.opacity, layer.blendMode)
                i += 2
            } else if (layer.clippingMask && i > 0 && !layers[i - 1].bitmap.isRecycled) {
                val base = layers[i - 1]
                scratchCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                scratchCanvas.drawBitmap(layer.bitmap, srcRect, dstRect, null)
                scratchCanvas.drawBitmap(base.bitmap, srcRect, dstRect, clipPaint)
                drawSliceWithRect(c, scratchTile, dstRect, dstRect, layer.opacity, layer.blendMode)
                i++
            } else {
                drawLayerSlice(c, layer.bitmap, layer.opacity, layer.blendMode)
                i++
            }
        }
        tile.version++
    }

    private fun drawLayerSlice(
        canvas: Canvas,
        bmp: Bitmap,
        opacity: Float,
        blendMode: LayerBlendMode,
    ) {
        drawSliceWithRect(canvas, bmp, srcRect, dstRect, opacity, blendMode)
    }

    private fun drawSliceWithRect(
        canvas: Canvas,
        bmp: Bitmap,
        src: Rect,
        dst: Rect,
        opacity: Float,
        blendMode: LayerBlendMode,
    ) {
        if (bmp.isRecycled) return
        val alpha = (opacity.coerceIn(0f, 1f) * 255f).toInt()
        if (alpha <= 0) return
        layerPaint.reset()
        layerPaint.alpha = alpha
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            layerPaint.blendMode = LayerCompositor.toAndroidBlendMode(blendMode)
        } else {
            val pd = LayerCompositor.toPorterDuff(blendMode)
            if (pd != null) layerPaint.xfermode = PorterDuffXfermode(pd)
        }
        canvas.drawBitmap(bmp, src, dst, layerPaint)
    }

    fun samplePixel(x: Int, y: Int): Int? {
        if (x !in 0 until width || y !in 0 until height) return null
        val tx = (x / tileSize).coerceIn(0, cols - 1)
        val ty = (y / tileSize).coerceIn(0, rows - 1)
        val tile = tiles[ty * cols + tx]
        val lx = (x - tile.left).coerceIn(0, tile.tileW - 1)
        val ly = (y - tile.top).coerceIn(0, tile.tileH - 1)
        return tile.bitmap.getPixel(lx, ly)
    }

    fun assembleBitmap(target: Bitmap? = null): Bitmap {
        val out = if (target != null && target.width == width && target.height == height && target.isMutable) {
            target
        } else {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        val c = Canvas(out)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        for (tile in tiles) {
            c.drawBitmap(tile.bitmap, tile.left.toFloat(), tile.top.toFloat(), null)
        }
        return out
    }

    fun recycle() {
        for (tile in tiles) tile.recycle()
        if (!scratchTile.isRecycled) scratchTile.recycle()
    }
}

object LayerCompositor {

    fun compositeForTimelapse(
        width: Int,
        height: Int,
        layers: List<LayerState>,
        backgroundRgb: Int,
        backgroundVisible: Boolean,
        animation: AnimationState,
    ): Bitmap {
        val visibleNonPrivate = layers.filter { !it.isPrivate }
        return composite(width, height, visibleNonPrivate, backgroundRgb, backgroundVisible, animation)
    }

    fun composite(
        width: Int,
        height: Int,
        layers: List<LayerState>,
        backgroundRgb: Int,
        backgroundVisible: Boolean,
        animation: AnimationState,
        target: Bitmap? = null,
        onionSkins: List<OnionSkinFrame> = emptyList(),
    ): Bitmap {
        val out = if (target != null && target.width == width && target.height == height && target.isMutable) {
            target
        } else {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        val canvas = Canvas(out)
        if (backgroundVisible) {
            canvas.drawColor(backgroundRgb)
        } else {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        }

        if (animation.enabled && animation.assistMode == CanvasAssistMode.PAGE_ASSIST && layers.isNotEmpty()) {
            val activeIdx = animation.currentFrameIndex.coerceIn(0, layers.lastIndex)
            val activeLayer = layers[activeIdx]
            if (activeLayer.visible) {
                drawLayer(canvas, activeLayer)
            }
            return out
        }

        if (animation.isAnimation && animation.onionSkinEnabled && onionSkins.isNotEmpty()) {
            val onionPaint = Paint()
            for (skin in onionSkins) {
                if (skin.bitmap.isRecycled) continue
                val dist = abs(skin.stepOffset).coerceAtLeast(1)
                val tint = if (skin.stepOffset < 0) 0xFFE5484D.toInt() else 0xFF30A46C.toInt()
                val alpha = (animation.onionSkinOpacity / dist * 255f).toInt().coerceIn(10, 180)
                onionPaint.reset()
                onionPaint.alpha = alpha
                onionPaint.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_ATOP)
                canvas.drawBitmap(skin.bitmap, 0f, 0f, onionPaint)
            }
        }

        val paint = Paint()
        var scratch: Bitmap? = null
        var clipPaint: Paint? = null
        var i = 0
        while (i < layers.size) {
            val layer = layers[i]
            if (!layer.visible || layer.isMask || layer.bitmap.isRecycled) {
                i++
                continue
            }
            val maskAbove = if (i + 1 < layers.size && layers[i + 1].isMask && layers[i + 1].visible && !layers[i + 1].bitmap.isRecycled) layers[i + 1] else null
            if (maskAbove != null) {
                val s = scratch ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { scratch = it }
                val sc = Canvas(s)
                sc.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                sc.drawBitmap(layer.bitmap, 0f, 0f, null)
                val cp = clipPaint ?: Paint().apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                }.also { clipPaint = it }
                sc.drawBitmap(maskAbove.bitmap, 0f, 0f, cp)
                drawBitmapWithBlend(canvas, s, layer.opacity, layer.blendMode, paint)
                i += 2
            } else if (layer.clippingMask && i > 0 && !layers[i - 1].bitmap.isRecycled) {
                val base = layers[i - 1]
                val s = scratch ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { scratch = it }
                val sc = Canvas(s)
                sc.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                sc.drawBitmap(layer.bitmap, 0f, 0f, null)
                val cp = clipPaint ?: Paint().apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                }.also { clipPaint = it }
                sc.drawBitmap(base.bitmap, 0f, 0f, cp)
                drawBitmapWithBlend(canvas, s, layer.opacity, layer.blendMode, paint)
                i++
            } else {
                drawBitmapWithBlend(canvas, layer.bitmap, layer.opacity, layer.blendMode, paint)
                i++
            }
        }
        scratch?.recycle()
        return out
    }

    private fun drawLayer(canvas: Canvas, layer: LayerState) {
        drawBitmapWithBlend(canvas, layer.bitmap, layer.opacity, layer.blendMode, Paint())
    }

    private fun drawBitmapWithBlend(
        canvas: Canvas,
        bmp: Bitmap,
        opacity: Float,
        blendMode: LayerBlendMode,
        paint: Paint,
    ) {
        if (bmp.isRecycled) return
        val alpha = (opacity.coerceIn(0f, 1f) * 255f).toInt()
        if (alpha <= 0) return
        paint.reset()
        paint.alpha = alpha
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            paint.blendMode = toAndroidBlendMode(blendMode)
        } else {
            val pd = toPorterDuff(blendMode)
            if (pd != null) paint.xfermode = PorterDuffXfermode(pd)
        }
        canvas.drawBitmap(bmp, 0f, 0f, paint)
    }

    internal fun toAndroidBlendMode(mode: LayerBlendMode): BlendMode {
        return when (mode) {
            LayerBlendMode.NORMAL -> BlendMode.SRC_OVER
            LayerBlendMode.MULTIPLY -> BlendMode.MULTIPLY
            LayerBlendMode.DARKEN -> BlendMode.DARKEN
            LayerBlendMode.COLOR_BURN -> BlendMode.COLOR_BURN
            LayerBlendMode.LINEAR_BURN -> BlendMode.MODULATE
            LayerBlendMode.LIGHTEN -> BlendMode.LIGHTEN
            LayerBlendMode.SCREEN -> BlendMode.SCREEN
            LayerBlendMode.COLOR_DODGE -> BlendMode.COLOR_DODGE
            LayerBlendMode.ADD -> BlendMode.PLUS
            LayerBlendMode.OVERLAY -> BlendMode.OVERLAY
            LayerBlendMode.SOFT_LIGHT -> BlendMode.SOFT_LIGHT
            LayerBlendMode.HARD_LIGHT -> BlendMode.HARD_LIGHT
            LayerBlendMode.DIFFERENCE -> BlendMode.DIFFERENCE
            LayerBlendMode.EXCLUSION -> BlendMode.EXCLUSION
            LayerBlendMode.SUBTRACT -> BlendMode.DIFFERENCE
            LayerBlendMode.DIVIDE -> BlendMode.COLOR_DODGE
            LayerBlendMode.HUE -> BlendMode.HUE
            LayerBlendMode.SATURATION -> BlendMode.SATURATION
            LayerBlendMode.COLOR -> BlendMode.COLOR
            LayerBlendMode.LUMINOSITY -> BlendMode.LUMINOSITY
        }
    }

    internal fun toPorterDuff(mode: LayerBlendMode): PorterDuff.Mode? = when (mode) {
        LayerBlendMode.NORMAL -> PorterDuff.Mode.SRC_OVER
        LayerBlendMode.MULTIPLY -> PorterDuff.Mode.MULTIPLY
        LayerBlendMode.DARKEN -> PorterDuff.Mode.DARKEN
        LayerBlendMode.LIGHTEN -> PorterDuff.Mode.LIGHTEN
        LayerBlendMode.SCREEN -> PorterDuff.Mode.SCREEN
        LayerBlendMode.ADD -> PorterDuff.Mode.ADD
        LayerBlendMode.OVERLAY -> PorterDuff.Mode.OVERLAY
        else -> null
    }

    fun colorDrop(
        target: Bitmap,
        reference: Bitmap,
        startX: Int,
        startY: Int,
        fillRgb: Int,
        threshold: Float,
        alphaLock: Boolean,
    ) {
        val w = target.width
        val h = target.height
        if (startX !in 0 until w || startY !in 0 until h) return

        val refPixels = IntArray(w * h)
        reference.getPixels(refPixels, 0, w, 0, 0, w, h)
        val outPixels = if (target === reference) refPixels.copyOf() else IntArray(w * h).also {
            target.getPixels(it, 0, w, 0, 0, w, h)
        }

        val seedColor = refPixels[startY * w + startX]
        val sr = Color.red(seedColor)
        val sg = Color.green(seedColor)
        val sb = Color.blue(seedColor)
        val sa = Color.alpha(seedColor)
        val maxDist = (threshold.coerceIn(0.02f, 0.95f) * 441.67f).toInt()

        val visited = BooleanArray(w * h)
        val stackX = IntArray(w * h)
        val stackY = IntArray(w * h)
        var top = 0
        stackX[top] = startX
        stackY[top] = startY
        top++
        visited[startY * w + startX] = true

        fun matches(idx: Int): Boolean {
            val c = refPixels[idx]
            val da = abs(Color.alpha(c) - sa)
            if (da > maxDist) return false
            val dr = Color.red(c) - sr
            val dg = Color.green(c) - sg
            val db = Color.blue(c) - sb
            val distSq = dr * dr + dg * dg + db * db
            return distSq <= maxDist * maxDist
        }

        while (top > 0) {
            top--
            val cx = stackX[top]
            val cy = stackY[top]
            val rowOffset = cy * w
            var lx = cx
            while (lx > 0 && !visited[rowOffset + lx - 1] && matches(rowOffset + lx - 1)) {
                lx--
                visited[rowOffset + lx] = true
            }
            var rx = cx
            while (rx < w - 1 && !visited[rowOffset + rx + 1] && matches(rowOffset + rx + 1)) {
                rx++
                visited[rowOffset + rx] = true
            }
            for (x in lx..rx) {
                val idx = rowOffset + x
                if (alphaLock) {
                    val existingAlpha = Color.alpha(outPixels[idx])
                    if (existingAlpha > 0) {
                        outPixels[idx] = (existingAlpha shl 24) or (fillRgb and 0x00FFFFFF)
                    }
                } else {
                    outPixels[idx] = fillRgb
                }
                if (cy > 0) {
                    val up = (cy - 1) * w + x
                    if (!visited[up] && matches(up)) {
                        visited[up] = true
                        stackX[top] = x
                        stackY[top] = cy - 1
                        top++
                    }
                }
                if (cy < h - 1) {
                    val dn = (cy + 1) * w + x
                    if (!visited[dn] && matches(dn)) {
                        visited[dn] = true
                        stackX[top] = x
                        stackY[top] = cy + 1
                        top++
                    }
                }
            }
        }
        target.setPixels(outPixels, 0, w, 0, 0, w, h)
    }

    fun makeThumbnail(bmp: Bitmap, maxDim: Int = 128): Bitmap {
        if (bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) {
            return Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        }
        val scale = min(maxDim.toFloat() / bmp.width, maxDim.toFloat() / bmp.height).coerceAtMost(1f)
        val tw = max(16, (bmp.width * scale).toInt())
        val th = max(16, (bmp.height * scale).toInt())
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        c.drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), Rect(0, 0, tw, th), paint)
        return out
    }
}
