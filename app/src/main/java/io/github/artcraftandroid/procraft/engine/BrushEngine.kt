package io.github.artcraftandroid.procraft.engine

import android.graphics.*
import io.github.artcraftandroid.procraft.model.*
import kotlin.math.*
import kotlin.random.Random

data class StrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 1f,
    val tilt: Float = 0f,
    val timeMs: Long = System.currentTimeMillis(),
)

data class RecognizedShape(
    val kind: String, // "Line", "Circle", "Ellipse", "Rectangle", "Triangle"
    val points: List<StrokePoint>,
)

class BrushStrokeSession(
    val tool: ToolKind,
    val brush: BrushSettings,
    val colorRgb: Int,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val alphaLock: Boolean,
    val guide: GuideConfig,
    val selectionMask: ByteArray?,
    val targetBitmap: Bitmap,
    private val baseLayerSnapshot: Bitmap? = null,
    private val onBeforeModifyRect: ((Float, Float, Float, Float) -> Unit)? = null,
) {
    private val rawPoints = mutableListOf<StrokePoint>()
    private var lastDrawnPoint: StrokePoint? = null
    private var filteredX = 0f
    private var filteredY = 0f
    private var smudgeSampleColor: Int = colorRgb
    private var wetSampleColor: Int = colorRgb
    private val strokeSeed = Random.nextInt()
    private val rng = Random(strokeSeed)
    private val baseHsv = FloatArray(3).also { Color.colorToHSV(colorRgb, it) }

    private val maxRadiusPx: Float = run {
        val minDim = min(canvasWidth, canvasHeight).toFloat().coerceAtLeast(256f)
        (2f + brush.size.pow(1.6f) * minDim * 0.14f).coerceIn(1.5f, minDim * 0.25f)
    }

    private val stepPx: Float = run {
        val spacing = brush.spacing.coerceIn(0.03f, 0.75f)
        (maxRadiusPx * 2f * spacing).coerceAtLeast(max(1.5f, maxRadiusPx * 0.14f))
    }

    private val workCanvas = Canvas(targetBitmap)
    private val alphaLockMode = if (alphaLock) PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) else null
    private val eraseMode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)

    private val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        if (tool == ToolKind.ERASE) {
            xfermode = eraseMode
            color = Color.BLACK
        } else if (alphaLockMode != null) {
            xfermode = alphaLockMode
        }
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        if (alphaLockMode != null) xfermode = alphaLockMode
        style = Paint.Style.STROKE
    }

    private val hasCustomTip: Boolean = !brush.tipPath.isNullOrBlank()
    private val stampSprite: Bitmap? = buildStampSprite()
    private var lastTintColor: Int = if (tool == ToolKind.PAINT && !hasCustomTip) colorRgb else Int.MIN_VALUE
    private var lastTintFilter: PorterDuffColorFilter? = null

    private val dstRectF = RectF()

    private var dirtyLeft = Float.MAX_VALUE
    private var dirtyTop = Float.MAX_VALUE
    private var dirtyRight = -Float.MAX_VALUE
    private var dirtyBottom = -Float.MAX_VALUE
    private var hasDirty = false

    private fun buildStampSprite(): Bitmap? {
        val tipFile = brush.tipPath
        if (!tipFile.isNullOrBlank()) {
            val decoded = BitmapFactory.decodeFile(tipFile)
            if (decoded != null) return decoded
        }
        val needsSoftSprite = when (brush.shapeKind) {
            BrushShapeKind.SOFT_AIR -> true
            BrushShapeKind.WATERCOLOR_BLOOM -> true
            BrushShapeKind.ROUND, BrushShapeKind.INK_NIB -> brush.hardness < 0.90f && tool != ToolKind.ERASE
            else -> false
        }
        if (!needsSoftSprite) return null

        val size = 128
        val half = size * 0.5f
        val sprite = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(sprite)
        val baseRgb = if (tool == ToolKind.PAINT) colorRgb else Color.WHITE
        val rr = Color.red(baseRgb)
        val gg = Color.green(baseRgb)
        val bb = Color.blue(baseRgb)

        when (brush.shapeKind) {
            BrushShapeKind.SOFT_AIR -> {
                val centerCol = Color.argb(145, rr, gg, bb)
                val midCol = Color.argb(64, rr, gg, bb)
                val edgeCol = Color.argb(0, rr, gg, bb)
                val hardStop = brush.hardness.coerceIn(0f, 0.72f)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(
                        half,
                        half,
                        half - 1f,
                        intArrayOf(centerCol, midCol, edgeCol),
                        floatArrayOf(0f, (hardStop + 1f) * 0.5f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                }
                c.drawCircle(half, half, half - 1f, p)
            }
            BrushShapeKind.WATERCOLOR_BLOOM -> {
                val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(92, rr, gg, bb)
                }
                c.drawCircle(half, half, half * 0.92f, fillP)
                val edgeP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = size * 0.07f
                    color = Color.argb(72, rr, gg, bb)
                }
                c.drawCircle(half, half, half * 0.88f, edgeP)
            }
            else -> {
                val solidStop = brush.hardness.coerceIn(0.15f, 0.92f)
                val solidCol = Color.argb(255, rr, gg, bb)
                val edgeCol = Color.argb(0, rr, gg, bb)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(
                        half,
                        half,
                        half - 1f,
                        intArrayOf(solidCol, solidCol, edgeCol),
                        floatArrayOf(0f, solidStop, 1f),
                        Shader.TileMode.CLAMP,
                    )
                }
                c.drawCircle(half, half, half - 1f, p)
            }
        }
        return sprite
    }

    fun consumeDirtyRect(out: RectF): Boolean {
        if (!hasDirty) return false
        out.set(
            dirtyLeft.coerceIn(0f, canvasWidth.toFloat()),
            dirtyTop.coerceIn(0f, canvasHeight.toFloat()),
            dirtyRight.coerceIn(0f, canvasWidth.toFloat()),
            dirtyBottom.coerceIn(0f, canvasHeight.toFloat()),
        )
        dirtyLeft = Float.MAX_VALUE
        dirtyTop = Float.MAX_VALUE
        dirtyRight = -Float.MAX_VALUE
        dirtyBottom = -Float.MAX_VALUE
        hasDirty = false
        return out.right > out.left && out.bottom > out.top
    }

    private fun recordDirty(l: Float, t: Float, r: Float, b: Float) {
        if (l < dirtyLeft) dirtyLeft = l
        if (t < dirtyTop) dirtyTop = t
        if (r > dirtyRight) dirtyRight = r
        if (b > dirtyBottom) dirtyBottom = b
        hasDirty = true
    }

    fun start(p: StrokePoint) {
        val snapped = applyAssistSnap(p, isStart = true)
        filteredX = snapped.x
        filteredY = snapped.y
        rawPoints.add(snapped)
        lastDrawnPoint = snapped
        if (tool == ToolKind.SMUDGE) {
            smudgeSampleColor = samplePixel(snapped.x, snapped.y)
        } else if (brush.wetMix > 0.02f && tool == ToolKind.PAINT) {
            val under = samplePixel(snapped.x, snapped.y)
            wetSampleColor = blendArgb(colorRgb, under, brush.wetMix * 0.6f)
        }
        stampWithSymmetry(snapped, if (brush.taperStart > 0f) (1f - brush.taperStart * 0.7f).coerceIn(0.2f, 1f) else 1f)
    }

    fun move(p: StrokePoint) {
        val snapped = applyAssistSnap(p, isStart = false)
        val smooth = (brush.streamline * 0.78f).coerceIn(0f, 0.86f)
        filteredX = filteredX * smooth + snapped.x * (1f - smooth)
        filteredY = filteredY * smooth + snapped.y * (1f - smooth)
        val curr = StrokePoint(filteredX, filteredY, snapped.pressure, snapped.tilt, snapped.timeMs)
        rawPoints.add(curr)

        val prev = lastDrawnPoint ?: curr
        val dx = curr.x - prev.x
        val dy = curr.y - prev.y
        val dist = hypot(dx, dy)
        if (dist < stepPx * 0.5f) return

        val steps = max(1, (dist / stepPx).toInt())
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val ix = prev.x + dx * t
            val iy = prev.y + dy * t
            val ip = prev.pressure + (curr.pressure - prev.pressure) * t
            val pt = StrokePoint(ix, iy, ip, curr.tilt, curr.timeMs)
            if (tool == ToolKind.SMUDGE) {
                val sampled = samplePixel(ix, iy)
                smudgeSampleColor = blendArgb(smudgeSampleColor, sampled, 0.32f)
            } else if (brush.wetMix > 0.02f && tool == ToolKind.PAINT) {
                val sampled = samplePixel(ix, iy)
                wetSampleColor = blendArgb(wetSampleColor, sampled, brush.wetMix * 0.28f)
            }
            stampWithSymmetry(pt, taperForIndex(rawPoints.size))
        }
        lastDrawnPoint = curr
    }

    fun end(): RecognizedShape? {
        // Apply taperEnd soft tail if configured
        val last = lastDrawnPoint
        if (last != null && brush.taperEnd > 0.08f && rawPoints.size >= 4) {
            val prev = rawPoints[rawPoints.size - 2]
            val dx = last.x - prev.x
            val dy = last.y - prev.y
            val d = hypot(dx, dy).coerceAtLeast(1f)
            val tailLen = maxRadiusPx * (0.4f + brush.taperEnd * 1.4f)
            val ux = dx / d
            val uy = dy / d
            val steps = 3
            for (k in 1..steps) {
                val f = k.toFloat() / steps
                val taper = (1f - brush.taperEnd * f).coerceIn(0.08f, 1f)
                stampWithSymmetry(
                    StrokePoint(last.x + ux * tailLen * f * 0.35f, last.y + uy * tailLen * f * 0.35f, last.pressure * (1f - f * 0.5f)),
                    taper,
                )
            }
        }
        stampSprite?.recycle()
        return null
    }

    fun tryQuickShape(): RecognizedShape? {
        if (!brush.quickShapeEnabled || rawPoints.size < 8) return null
        val start = rawPoints.first()
        val end = rawPoints.last()
        val directDist = hypot(end.x - start.x, end.y - start.y)
        var pathLen = 0f
        for (i in 1 until rawPoints.size) {
            pathLen += hypot(rawPoints[i].x - rawPoints[i - 1].x, rawPoints[i].y - rawPoints[i - 1].y)
        }
        if (pathLen < maxRadiusPx * 2f) return null

        if (directDist > 20f && pathLen / directDist < 1.12f) {
            return RecognizedShape("Line", listOf(start, end))
        }

        if (directDist < max(36f, pathLen * 0.16f)) {
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            var cx = 0f
            var cy = 0f
            for (pt in rawPoints) {
                if (pt.x < minX) minX = pt.x
                if (pt.x > maxX) maxX = pt.x
                if (pt.y < minY) minY = pt.y
                if (pt.y > maxY) maxY = pt.y
                cx += pt.x
                cy += pt.y
            }
            cx /= rawPoints.size
            cy /= rawPoints.size
            val rx = (maxX - minX) * 0.5f
            val ry = (maxY - minY) * 0.5f
            if (rx < 12f || ry < 12f) return null

            var ellipseErr = 0f
            var rectErr = 0f
            for (pt in rawPoints) {
                val nx = (pt.x - cx) / rx
                val ny = (pt.y - cy) / ry
                val rad = hypot(nx, ny)
                ellipseErr += abs(rad - 1f)
                val boxDist = min(abs(abs(nx) - 1f), abs(abs(ny) - 1f))
                rectErr += boxDist
            }
            ellipseErr /= rawPoints.size
            rectErr /= rawPoints.size

            return if (ellipseErr < rectErr) {
                val isCircle = abs(rx - ry) / max(rx, ry) < 0.18f
                val r = (rx + ry) * 0.5f
                val erx = if (isCircle) r else rx
                val ery = if (isCircle) r else ry
                val out = (0..72).map { step ->
                    val a = step.toFloat() / 72f * (2f * PI.toFloat())
                    StrokePoint(cx + cos(a) * erx, cy + sin(a) * ery, 0.9f)
                }
                RecognizedShape(if (isCircle) "Circle" else "Ellipse", out)
            } else {
                val out = mutableListOf<StrokePoint>()
                val corners = listOf(
                    StrokePoint(minX, minY, 0.9f),
                    StrokePoint(maxX, minY, 0.9f),
                    StrokePoint(maxX, maxY, 0.9f),
                    StrokePoint(minX, maxY, 0.9f),
                    StrokePoint(minX, minY, 0.9f),
                )
                for (c in 0 until 4) {
                    val a = corners[c]
                    val b = corners[c + 1]
                    for (k in 0 until 16) {
                        val t = k / 16f
                        out.add(StrokePoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, 0.9f))
                    }
                }
                out.add(corners.last())
                RecognizedShape("Rectangle", out)
            }
        }
        return null
    }

    fun redrawQuickShape(shape: RecognizedShape, onRestoreBase: (() -> Unit)? = null) {
        if (onRestoreBase != null) {
            onRestoreBase()
        } else if (baseLayerSnapshot != null) {
            val c = Canvas(targetBitmap)
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            c.drawBitmap(baseLayerSnapshot, 0f, 0f, null)
        }
        val pts = shape.points
        if (pts.isEmpty()) return
        rawPoints.clear()
        lastDrawnPoint = null
        start(pts.first())
        for (i in 1 until pts.size) {
            move(pts[i])
        }
    }

    private fun applyAssistSnap(p: StrokePoint, isStart: Boolean): StrokePoint {
        if (!guide.enabled || !guide.assisted) return p
        val start = rawPoints.firstOrNull() ?: return p
        return when (guide.kind) {
            GuideKind.GRID_2D -> {
                if (isStart || rawPoints.size < 2) p
                else {
                    val dx = abs(p.x - start.x)
                    val dy = abs(p.y - start.y)
                    if (dx >= dy) p.copy(y = start.y) else p.copy(x = start.x)
                }
            }
            GuideKind.ISOMETRIC -> {
                if (isStart || rawPoints.size < 2) p
                else {
                    val dx = p.x - start.x
                    val dy = p.y - start.y
                    val len = hypot(dx, dy)
                    val angles = listOf(0.0, PI / 6, PI / 2, 5 * PI / 6, PI, -5 * PI / 6, -PI / 2, -PI / 6)
                    val ang = atan2(dy.toDouble(), dx.toDouble())
                    val best = angles.minByOrNull { abs(atan2(sin(ang - it), cos(ang - it))) } ?: 0.0
                    p.copy(x = start.x + (cos(best) * len).toFloat(), y = start.y + (sin(best) * len).toFloat())
                }
            }
            else -> p
        }
    }

    private fun taperForIndex(count: Int): Float {
        if (count < 4 && brush.taperStart > 0f) {
            val k = count / 4f
            return (1f - brush.taperStart * (1f - k)).coerceIn(0.15f, 1f)
        }
        return 1f
    }

    private fun stampWithSymmetry(p: StrokePoint, taper: Float) {
        drawStamp(p.x, p.y, p.pressure, taper)
        if (!guide.enabled) return
        val cx = canvasWidth * 0.5f
        val cy = canvasHeight * 0.5f
        when (guide.kind) {
            GuideKind.SYMMETRY_VERTICAL -> {
                drawStamp(canvasWidth - p.x, p.y, p.pressure, taper)
            }
            GuideKind.SYMMETRY_HORIZONTAL -> {
                drawStamp(p.x, canvasHeight - p.y, p.pressure, taper)
            }
            GuideKind.SYMMETRY_QUADRANT -> {
                drawStamp(canvasWidth - p.x, p.y, p.pressure, taper)
                drawStamp(p.x, canvasHeight - p.y, p.pressure, taper)
                drawStamp(canvasWidth - p.x, canvasHeight - p.y, p.pressure, taper)
            }
            GuideKind.SYMMETRY_RADIAL -> {
                val segs = guide.radialSegments.coerceIn(2, 16)
                val rx = p.x - cx
                val ry = p.y - cy
                for (k in 1 until segs) {
                    val a = (2.0 * PI * k / segs).toFloat()
                    val ca = cos(a)
                    val sa = sin(a)
                    drawStamp(cx + rx * ca - ry * sa, cy + rx * sa + ry * ca, p.pressure, taper)
                }
            }
            else -> Unit
        }
    }

    private fun computeDynamicPaintColor(): Int {
        if (tool == ToolKind.ERASE) return Color.TRANSPARENT
        if (tool == ToolKind.SMUDGE) return smudgeSampleColor
        var baseCol = if (brush.wetMix > 0.02f) blendArgb(colorRgb, wetSampleColor, brush.wetMix * 0.55f) else colorRgb
        if (brush.hueJitter > 0.01f || brush.saturationJitter > 0.01f) {
            val hsv = floatArrayOf(baseHsv[0], baseHsv[1], baseHsv[2])
            if (brush.wetMix > 0.02f) Color.colorToHSV(baseCol, hsv)
            if (brush.hueJitter > 0.01f) {
                hsv[0] = (hsv[0] + (rng.nextFloat() - 0.5f) * 90f * brush.hueJitter + 360f) % 360f
            }
            if (brush.saturationJitter > 0.01f) {
                hsv[1] = (hsv[1] + (rng.nextFloat() - 0.5f) * brush.saturationJitter).coerceIn(0f, 1f)
            }
            baseCol = Color.HSVToColor(hsv)
        }
        return baseCol
    }

    private fun drawStamp(x: Float, y: Float, pressure: Float, taper: Float) {
        val pFactor = pressure.coerceIn(0.1f, 1f)
        val jitterScale = if (brush.jitter > 0f) 1f + (rng.nextFloat() - 0.5f) * brush.jitter else 1f
        val r = (maxRadiusPx * (1f - brush.pressureSize * (1f - pFactor)) * taper * jitterScale).coerceAtLeast(1f)
        val scatterOffset = if (brush.scatter > 0f) maxRadiusPx * 1.4f * brush.scatter else 0f
        val sx = x + (rng.nextFloat() - 0.5f) * 2f * scatterOffset
        val sy = y + (rng.nextFloat() - 0.5f) * 2f * scatterOffset

        val margin = r * 1.85f + 4f
        val dl = sx - margin
        val dt = sy - margin
        val dr = sx + margin
        val db = sy + margin
        onBeforeModifyRect?.invoke(dl, dt, dr, db)
        recordDirty(dl, dt, dr, db)

        val grainMod = if (brush.grainAmount > 0.02f) {
            val gx = if (brush.grainMoving) sx + rng.nextFloat() * 4f else sx
            val gy = if (brush.grainMoving) sy + rng.nextFloat() * 4f else sy
            val wave = 0.5f + 0.5f * sin(gx * 0.25f / brush.grainScale.coerceAtLeast(0.3f)) * cos(gy * 0.25f / brush.grainScale.coerceAtLeast(0.3f))
            1f - brush.grainAmount * 0.45f * wave
        } else 1f

        val alphaNorm = brush.opacity * (1f - brush.pressureOpacity * (1f - pFactor)) * grainMod
        val alphaInt = (alphaNorm * 255f).toInt().coerceIn(2, 255)
        val effectiveAlpha = if (tool == ToolKind.SMUDGE) (alphaInt * 0.45f).toInt().coerceIn(8, 180) else alphaInt

        val effectiveColor = computeDynamicPaintColor()

        val sprite = stampSprite
        if (sprite != null && !sprite.isRecycled && (hasCustomTip || brush.shapeKind == BrushShapeKind.SOFT_AIR || brush.shapeKind == BrushShapeKind.WATERCOLOR_BLOOM || brush.shapeKind == BrushShapeKind.ROUND || brush.shapeKind == BrushShapeKind.INK_NIB)) {
            stampPaint.alpha = effectiveAlpha
            if (hasCustomTip || tool == ToolKind.SMUDGE || (tool == ToolKind.PAINT && effectiveColor != colorRgb)) {
                val targetTint = if (tool == ToolKind.ERASE) Color.BLACK else effectiveColor
                if (targetTint != lastTintColor || lastTintFilter == null) {
                    lastTintColor = targetTint
                    lastTintFilter = PorterDuffColorFilter(targetTint, PorterDuff.Mode.SRC_IN)
                }
                stampPaint.colorFilter = lastTintFilter
            } else {
                stampPaint.colorFilter = null
            }
            if (hasCustomTip) {
                val aspect = sprite.width.toFloat() / sprite.height.coerceAtLeast(1).toFloat()
                val hw = r * (if (aspect >= 1f) 1f else aspect)
                val hh = r * (if (aspect >= 1f) 1f / aspect else 1f) * brush.roundness
                workCanvas.save()
                workCanvas.translate(sx, sy)
                if (abs(brush.angleDeg) > 0.5f) workCanvas.rotate(brush.angleDeg)
                dstRectF.set(-hw, -hh, hw, hh)
                workCanvas.drawBitmap(sprite, null, dstRectF, stampPaint)
                workCanvas.restore()
            } else {
                val scaleR = if (brush.shapeKind == BrushShapeKind.WATERCOLOR_BLOOM) r * 1.1f else r
                dstRectF.set(sx - scaleR, sy - scaleR, sx + scaleR, sy + scaleR)
                workCanvas.drawBitmap(sprite, null, dstRectF, stampPaint)
            }
            return
        }

        stampPaint.colorFilter = null
        if (tool != ToolKind.ERASE) {
            stampPaint.color = effectiveColor
        }
        stampPaint.alpha = effectiveAlpha

        when (brush.shapeKind) {
            BrushShapeKind.CALLIGRAPHY_FLAT, BrushShapeKind.FLAT_BRISTLE -> {
                workCanvas.save()
                workCanvas.translate(sx, sy)
                workCanvas.rotate(brush.angleDeg)
                if (brush.shapeKind == BrushShapeKind.FLAT_BRISTLE) {
                    val bristles = 4
                    for (b in 0 until bristles) {
                        val frac = (b.toFloat() / (bristles - 1)) - 0.5f
                        val br = r * 0.38f
                        workCanvas.drawOval(-r * 0.9f, frac * r * brush.roundness - br, r * 0.9f, frac * r * brush.roundness + br, stampPaint)
                    }
                } else {
                    workCanvas.drawOval(-r, -r * brush.roundness, r, r * brush.roundness, stampPaint)
                }
                workCanvas.restore()
            }
            BrushShapeKind.GRAINY_CHALK -> {
                val dots = 5
                for (d in 0 until dots) {
                    val gx = sx + (rng.nextFloat() - 0.5f) * r * 1.1f
                    val gy = sy + (rng.nextFloat() - 0.5f) * r * 1.1f
                    val gr = (r * (0.35f + rng.nextFloat() * 0.45f)).coerceAtLeast(1f)
                    if (rng.nextFloat() >= brush.grainAmount * 0.35f) {
                        workCanvas.drawCircle(gx, gy, gr, stampPaint)
                    }
                }
            }
            BrushShapeKind.SPECKLE_SPRAY -> {
                val specks = 7
                for (d in 0 until specks) {
                    val angle = rng.nextFloat() * 2f * PI.toFloat()
                    val dist = rng.nextFloat() * r * 1.6f
                    val sr = (r * (0.08f + rng.nextFloat() * 0.22f)).coerceAtLeast(1f)
                    workCanvas.drawCircle(sx + cos(angle) * dist, sy + sin(angle) * dist, sr, stampPaint)
                }
            }
            BrushShapeKind.WATERCOLOR_BLOOM -> {
                stampPaint.alpha = (effectiveAlpha * 0.35f).toInt().coerceIn(4, 255)
                workCanvas.drawCircle(sx, sy, r * 1.1f, stampPaint)
                ringPaint.color = effectiveColor
                ringPaint.strokeWidth = (r * 0.14f).coerceAtLeast(1f)
                ringPaint.alpha = (effectiveAlpha * 0.28f).toInt().coerceIn(4, 255)
                workCanvas.drawCircle(sx, sy, r * 1.05f, ringPaint)
            }
            else -> {
                workCanvas.drawCircle(sx, sy, r, stampPaint)
            }
        }
    }

    private fun samplePixel(x: Float, y: Float): Int {
        val ix = x.roundToInt().coerceIn(0, canvasWidth - 1)
        val iy = y.roundToInt().coerceIn(0, canvasHeight - 1)
        val px = targetBitmap.getPixel(ix, iy)
        return if (Color.alpha(px) < 16) colorRgb else px
    }

    private fun blendArgb(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        val r = (Color.red(a) * (1f - k) + Color.red(b) * k).roundToInt()
        val g = (Color.green(a) * (1f - k) + Color.green(b) * k).roundToInt()
        val bl = (Color.blue(a) * (1f - k) + Color.blue(b) * k).roundToInt()
        return Color.rgb(r, g, bl)
    }
}
