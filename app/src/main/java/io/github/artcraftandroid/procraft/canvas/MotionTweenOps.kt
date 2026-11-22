package io.github.artcraftandroid.procraft.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import io.github.artcraftandroid.procraft.engine.LayerCompositor
import io.github.artcraftandroid.procraft.engine.LayerTransformState
import io.github.artcraftandroid.procraft.engine.TransformEngine
import io.github.artcraftandroid.procraft.model.*
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

fun CanvasViewModel.recomputeDefaultSelectionAnchor() {
    val art = currentArtwork ?: return
    val mask = selectionMask
    val bounds = if (mask != null && !mask.isEmpty()) {
        mask.computeBounds()
    } else {
        activeLayer()?.bitmap?.let { TransformEngine.computeBitmapBounds(it) }
    }
    selectionAnchor = if (bounds != null) {
        Offset(bounds.centerX(), bounds.centerY())
    } else {
        Offset(art.width * 0.5f, art.height * 0.5f)
    }
}

fun CanvasViewModel.startSelectionOrLayerTransformSession() {
    val art = currentArtwork ?: return
    val active = activeLayer() ?: return
    if (active.locked) {
        showNotice("Layer is locked")
        return
    }
    val fullBackup = active.bitmap.copy(Bitmap.Config.ARGB_8888, false)
    val mask = selectionMask
    if (mask != null && !mask.isEmpty()) {
        val staticBase = active.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        mask.clearBitmap(staticBase)
        val scoped = mask.extractCopy(active.bitmap)
        val bounds = mask.computeBounds() ?: RectF(0f, 0f, art.width.toFloat(), art.height.toFloat())
        val anchor = selectionAnchor ?: Offset(bounds.centerX(), bounds.centerY())
        selectionAnchor = anchor
        initTransformBackups(
            fullBackup = fullBackup,
            staticBase = staticBase,
            selectionScoped = scoped,
            initialState = LayerTransformState(
                anchorX = anchor.x,
                anchorY = anchor.y,
                boundsLeft = bounds.left,
                boundsTop = bounds.top,
                boundsRight = bounds.right,
                boundsBottom = bounds.bottom,
            ),
        )
    } else {
        val bounds = TransformEngine.computeBitmapBounds(active.bitmap)
            ?: RectF(0f, 0f, art.width.toFloat(), art.height.toFloat())
        val anchor = selectionAnchor ?: Offset(bounds.centerX(), bounds.centerY())
        selectionAnchor = anchor
        initTransformBackups(
            fullBackup = fullBackup,
            staticBase = null,
            selectionScoped = null,
            initialState = LayerTransformState(
                anchorX = anchor.x,
                anchorY = anchor.y,
                boundsLeft = bounds.left,
                boundsTop = bounds.top,
                boundsRight = bounds.right,
                boundsBottom = bounds.bottom,
            ),
        )
    }
}

fun CanvasViewModel.enterSelectionTransform() {
    selecting = false
    anchorMoveMode = false
    if (!transforming) {
        startSelectionOrLayerTransformSession()
    }
}

fun CanvasViewModel.moveSelectionAnchor(canvasPos: Offset) {
    val art = currentArtwork ?: return
    val clamped = Offset(
        canvasPos.x.coerceIn(0f, art.width.toFloat()),
        canvasPos.y.coerceIn(0f, art.height.toFloat()),
    )
    if (transforming) {
        repositionTransformAnchor(clamped)
    } else {
        selectionAnchor = clamped
    }
}

fun CanvasViewModel.repositionTransformAnchor(newWorldPos: Offset) {
    val art = currentArtwork ?: return
    val cur = transformState
    val bounds = cur.effectiveBounds(art.width.toFloat(), art.height.toFloat())
    val m = cur.buildMatrix(bounds.centerX(), bounds.centerY())
    val inv = Matrix()
    if (m.invert(inv)) {
        val pts = floatArrayOf(newWorldPos.x, newWorldPos.y)
        inv.mapPoints(pts)
        val newAx = pts[0]
        val newAy = pts[1]
        val newTx = newWorldPos.x - newAx
        val newTy = newWorldPos.y - newAy
        transformState = cur.copy(
            anchorX = newAx,
            anchorY = newAy,
            translateX = newTx,
            translateY = newTy,
        )
        selectionAnchor = newWorldPos
    }
}

fun CanvasViewModel.centerAnchorOnSelectionOrLayer() {
    val art = currentArtwork ?: return
    if (transforming) {
        val cur = transformState
        val b = cur.effectiveBounds(art.width.toFloat(), art.height.toFloat())
        val m = cur.buildMatrix(b.centerX(), b.centerY())
        val pts = floatArrayOf(b.centerX(), b.centerY())
        m.mapPoints(pts)
        repositionTransformAnchor(Offset(pts[0], pts[1]))
    } else {
        recomputeDefaultSelectionAnchor()
    }
    showNotice("Anchor centered")
}

fun CanvasViewModel.isSelectionInGroupTrack(): Boolean {
    if (!animationState.isAnimation) return false
    val selId = animationState.selectedTrackId ?: return false
    val sel = timelineTracks.firstOrNull { it.id == selId } ?: return false
    if (sel.kind == TrackKind.GROUP) return true
    return sel.groupId != null && timelineTracks.any { it.id == sel.groupId && it.kind == TrackKind.GROUP }
}

internal fun CanvasViewModel.resolveGroupedPaintTrack(): TimelineTrack? {
    if (!animationState.isAnimation) return null
    val selId = animationState.selectedTrackId ?: return null
    val sel = timelineTracks.firstOrNull { it.id == selId } ?: return null
    if (sel.kind == TrackKind.GROUP) {
        val activeLId = activeLayer()?.id
        return timelineTracks.firstOrNull { it.groupId == sel.id && it.kind == TrackKind.PAINT && it.layerId == activeLId }
            ?: timelineTracks.firstOrNull { it.groupId == sel.id && it.kind == TrackKind.PAINT }
    }
    val gid = sel.groupId ?: return null
    if (timelineTracks.none { it.id == gid && it.kind == TrackKind.GROUP }) return null
    if (sel.kind == TrackKind.PAINT) return sel
    val activeLId = activeLayer()?.id
    return timelineTracks.firstOrNull { it.groupId == gid && it.kind == TrackKind.PAINT && it.layerId == activeLId }
        ?: timelineTracks.firstOrNull { it.groupId == gid && it.kind == TrackKind.PAINT }
}

fun CanvasViewModel.canInsertSelectionKeyframe(): Boolean =
    animationState.isAnimation && resolveGroupedPaintTrack() != null

fun CanvasViewModel.hasActiveSelectionKeyframe(): Boolean {
    if (!animationState.isAnimation) return false
    val track = resolveGroupedPaintTrack() ?: return false
    return motionKeyframes.any { it.trackId == track.id }
}

fun CanvasViewModel.updateSelectionCurvePreference(
    curve: TweenCurveKind,
    bezier: CubicBezierControl = selectionCubicBezier,
) {
    selectionTweenCurve = curve
    selectionCubicBezier = bezier
    val track = resolveGroupedPaintTrack() ?: return
    val curF = animationState.currentFrameIndex
    val hasExact = motionKeyframes.any { it.trackId == track.id && it.frameIndex == curF }
    val targetIdx = if (hasExact) curF else motionKeyframes.lastOrNull { it.trackId == track.id }?.frameIndex
    if (targetIdx != null) {
        motionKeyframes = motionKeyframes.map { mk ->
            if (mk.trackId == track.id && mk.frameIndex == targetIdx) {
                mk.copy(curve = curve, bezier = bezier)
            } else mk
        }
    }
}

internal fun CanvasViewModel.activePaintTrack(): TimelineTrack? {
    resolveGroupedPaintTrack()?.let { return it }
    val sel = animationState.selectedTrackId?.let { id ->
        timelineTracks.firstOrNull { it.id == id && it.kind == TrackKind.PAINT }
    }
    if (sel != null) return sel
    val al = activeLayer()
    if (al != null) {
        val byLayer = timelineTracks.firstOrNull { it.kind == TrackKind.PAINT && it.layerId == al.id }
        if (byLayer != null) return byLayer
    }
    return timelineTracks.firstOrNull { it.kind == TrackKind.PAINT }
}

private fun renderCompositeFrame(
    width: Int,
    height: Int,
    sourceBmp: Bitmap,
    baseBmp: Bitmap?,
    state: LayerTransformState,
): Bitmap {
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    if (baseBmp != null && !baseBmp.isRecycled) {
        c.drawBitmap(baseBmp, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) })
    }
    val transformed = TransformEngine.applyTransform(sourceBmp, state)
    c.drawBitmap(transformed, 0f, 0f, null)
    transformed.recycle()
    return out
}

fun CanvasViewModel.insertSelectionMotionKeyframe(frame: Int = animationState.currentFrameIndex) {
    val art = currentArtwork ?: return
    if (!animationState.isAnimation) {
        showNotice("Enable Animation Assist first")
        return
    }
    ensureTimelineInitialized()
    if (!isSelectionInGroupTrack()) {
        showNotice("Select a Group Track to insert Selection Keyframe")
        return
    }
    val track = resolveGroupedPaintTrack() ?: run {
        showNotice("Add a Paint track into the Group Track first")
        return
    }
    if (track.locked) {
        showNotice("${track.name} is locked")
        return
    }
    val trackLayerIdx = layers.indexOfFirst { it.id == track.layerId }
    if (trackLayerIdx >= 0 && trackLayerIdx != activeLayerIndex) {
        setActiveLayerIndexDirect(trackLayerIdx)
    }
    val safeFrame = frame.coerceIn(0, (animationState.totalFrames - 1).coerceAtLeast(0))
    val layer = activeLayer() ?: return

    val capturedSource: Bitmap
    val capturedBase: Bitmap?
    val state: LayerTransformState

    if (transforming) {
        val src = (transformSelectionScopedBitmap ?: transformBackupRef)?.takeIf { !it.isRecycled } ?: return
        capturedSource = src.copy(Bitmap.Config.ARGB_8888, false)
        capturedBase = transformStaticBaseBitmap?.takeIf { !it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, false)
        state = transformState
    } else {
        val mask = selectionMask
        if (mask != null && !mask.isEmpty()) {
            capturedSource = mask.extractCopy(layer.bitmap)
            capturedBase = layer.bitmap.copy(Bitmap.Config.ARGB_8888, true).also { mask.clearBitmap(it) }
            val bounds = mask.computeBounds() ?: RectF(0f, 0f, art.width.toFloat(), art.height.toFloat())
            val anchor = selectionAnchor ?: Offset(bounds.centerX(), bounds.centerY())
            state = LayerTransformState(
                anchorX = anchor.x,
                anchorY = anchor.y,
                boundsLeft = bounds.left,
                boundsTop = bounds.top,
                boundsRight = bounds.right,
                boundsBottom = bounds.bottom,
            )
        } else {
            capturedSource = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
            capturedBase = null
            val bounds = TransformEngine.computeBitmapBounds(layer.bitmap)
                ?: RectF(0f, 0f, art.width.toFloat(), art.height.toFloat())
            val anchor = selectionAnchor ?: Offset(bounds.centerX(), bounds.centerY())
            state = LayerTransformState(
                anchorX = anchor.x,
                anchorY = anchor.y,
                boundsLeft = bounds.left,
                boundsTop = bounds.top,
                boundsRight = bounds.right,
                boundsBottom = bounds.bottom,
            )
        }
    }

    val rendered = renderCompositeFrame(art.width, art.height, capturedSource, capturedBase, state)
    val mk = MotionKeyframe(
        id = UUID.randomUUID().toString(),
        trackId = track.id,
        frameIndex = safeFrame,
        translateX = state.translateX,
        translateY = state.translateY,
        scaleX = state.scaleX,
        scaleY = state.scaleY,
        rotationDeg = state.rotationDeg,
        anchorX = state.anchorX,
        anchorY = state.anchorY,
        boundsLeft = state.boundsLeft,
        boundsTop = state.boundsTop,
        boundsRight = state.boundsRight,
        boundsBottom = state.boundsBottom,
        curve = selectionTweenCurve,
        bezier = selectionCubicBezier,
        sourceBitmap = capturedSource,
        baseBitmap = capturedBase,
    )

    // Replace any previous motion keyframe at exact same track & frame
    val oldSame = motionKeyframes.filter { it.trackId == track.id && it.frameIndex == safeFrame }
    oldSame.forEach { old ->
        old.sourceBitmap?.takeIf { !it.isRecycled }?.recycle()
        old.baseBitmap?.takeIf { !it.isRecycled }?.recycle()
    }
    motionKeyframes = (motionKeyframes.filterNot { it.trackId == track.id && it.frameIndex == safeFrame } + mk)
        .sortedBy { it.frameIndex }

    placeSingleKeyframeClipOnTrack(track.id, safeFrame, rendered, label = "Pose F${safeFrame + 1}")
    animationState = animationState.copy(currentFrameIndex = safeFrame)
    selectionCurveOverlayOpen = true
    rebuildTimelineRenderState()
    recomposite()
    persistCurrentArtwork()
    showNotice("Keyframe inserted at F${safeFrame + 1}")
}

private fun CanvasViewModel.placeSingleKeyframeClipOnTrack(
    trackId: String,
    frame: Int,
    renderedBitmap: Bitmap,
    label: String,
) {
    val tr = timelineTracks.firstOrNull { it.id == trackId } ?: return
    val sorted = tr.clips.sortedBy { it.startFrame }
    val nextBoundary = sorted.firstOrNull { it.startFrame > frame }?.startFrame ?: animationState.totalFrames
    val duration = (nextBoundary - frame).coerceAtLeast(1)
    val newClipId = UUID.randomUUID().toString()
    val placed = TimelineClip(
        id = newClipId,
        startFrame = frame,
        durationFrames = duration,
        label = label,
        bitmap = renderedBitmap,
        thumbnail = LayerCompositor.makeThumbnail(renderedBitmap, 72).asImageBitmap(),
    )
    val merged = replaceTrackRangeWithClips(sorted, frame, frame + duration, listOf(placed))
    timelineTracks = timelineTracks.map { if (it.id == trackId) it.copy(clips = merged) else it }
    animationState = animationState.copy(selectedClipId = newClipId)
}

private fun replaceTrackRangeWithClips(
    existing: List<TimelineClip>,
    rangeStart: Int,
    rangeEndExclusive: Int,
    inserted: List<TimelineClip>,
): List<TimelineClip> {
    val out = ArrayList<TimelineClip>(existing.size + inserted.size)
    for (c in existing.sortedBy { it.startFrame }) {
        val overlaps = c.startFrame < rangeEndExclusive && c.endFrameExclusive > rangeStart
        if (!overlaps) {
            out.add(c)
        } else {
            if (c.startFrame < rangeStart) {
                out.add(c.copy(durationFrames = rangeStart - c.startFrame))
            }
            if (c.endFrameExclusive > rangeEndExclusive) {
                val tailDelta = rangeEndExclusive - c.startFrame
                out.add(
                    c.copy(
                        id = if (c.startFrame < rangeStart) UUID.randomUUID().toString() else c.id,
                        startFrame = rangeEndExclusive,
                        durationFrames = c.endFrameExclusive - rangeEndExclusive,
                        sourceFrameOffset = c.sourceFrameOffset + tailDelta,
                        bitmap = if (c.startFrame < rangeStart) c.bitmap?.copy(Bitmap.Config.ARGB_8888, true) else c.bitmap,
                    ),
                )
            }
        }
    }
    out.addAll(inserted)
    return out.sortedBy { it.startFrame }
}

fun CanvasViewModel.resolveTweenContext(): Pair<LayerTransformState, LayerTransformState> {
    val art = currentArtwork
    val wf = art?.width?.toFloat() ?: 1024f
    val hf = art?.height?.toFloat() ?: 1024f
    val track = activePaintTrack()
    val trackKeys = if (track != null) {
        motionKeyframes.filter { it.trackId == track.id }.sortedBy { it.frameIndex }
    } else emptyList()

    val curFrame = animationState.currentFrameIndex
    val curPose = if (transforming) {
        transformState
    } else {
        val anchor = selectionAnchor ?: Offset(wf * 0.5f, hf * 0.5f)
        val bounds = selectionMask?.takeIf { !it.isEmpty() }?.computeBounds()
        LayerTransformState(
            anchorX = anchor.x,
            anchorY = anchor.y,
            boundsLeft = bounds?.left,
            boundsTop = bounds?.top,
            boundsRight = bounds?.right,
            boundsBottom = bounds?.bottom,
        )
    }

    val prevKey = trackKeys.lastOrNull { it.frameIndex < curFrame } ?: trackKeys.firstOrNull()
    val startPose = if (prevKey != null) {
        LayerTransformState(
            translateX = prevKey.translateX,
            translateY = prevKey.translateY,
            scaleX = prevKey.scaleX,
            scaleY = prevKey.scaleY,
            rotationDeg = prevKey.rotationDeg,
            anchorX = prevKey.anchorX ?: curPose.anchorX,
            anchorY = prevKey.anchorY ?: curPose.anchorY,
            boundsLeft = prevKey.boundsLeft ?: curPose.boundsLeft,
            boundsTop = prevKey.boundsTop ?: curPose.boundsTop,
            boundsRight = prevKey.boundsRight ?: curPose.boundsRight,
            boundsBottom = prevKey.boundsBottom ?: curPose.boundsBottom,
        )
    } else {
        LayerTransformState(
            anchorX = curPose.anchorX,
            anchorY = curPose.anchorY,
            boundsLeft = curPose.boundsLeft,
            boundsTop = curPose.boundsTop,
            boundsRight = curPose.boundsRight,
            boundsBottom = curPose.boundsBottom,
        )
    }
    return startPose to curPose
}

fun CanvasViewModel.generateInBetweenKeyframes(
    startFrame: Int,
    endFrame: Int,
    startState: LayerTransformState,
    endState: LayerTransformState,
    curve: TweenCurveKind = selectionTweenCurve,
    bezier: CubicBezierControl = selectionCubicBezier,
    stepBy: Int = 1,
) {
    val art = currentArtwork ?: return
    if (!animationState.isAnimation) {
        showNotice("Enable Animation Assist first")
        return
    }
    ensureTimelineInitialized()
    if (!isSelectionInGroupTrack()) {
        showNotice("Select a Group Track to generate In-Between keyframes")
        return
    }
    val track = resolveGroupedPaintTrack() ?: run {
        showNotice("Add a Paint track into the Group Track first")
        return
    }
    if (track.locked) {
        showNotice("${track.name} is locked")
        return
    }
    val trackLayerIdx = layers.indexOfFirst { it.id == track.layerId }
    if (trackLayerIdx >= 0 && trackLayerIdx != activeLayerIndex) {
        setActiveLayerIndexDirect(trackLayerIdx)
    }
    val layer = activeLayer() ?: return

    selectionTweenCurve = curve
    selectionCubicBezier = bezier

    val fromF = min(startFrame, endFrame).coerceIn(0, 1198)
    val toF = max(startFrame, endFrame).coerceIn(fromF + 1, 1199)
    val step = stepBy.coerceIn(1, 6)

    // Pick crispest available source + base bitmaps
    val prevMk = motionKeyframes
        .filter { it.trackId == track.id && it.sourceBitmap != null && !it.sourceBitmap.isRecycled }
        .minByOrNull { kotlin.math.abs(it.frameIndex - fromF) }

    val sourceBmp: Bitmap
    val baseBmp: Bitmap?
    var recycleTempSource = false
    var recycleTempBase = false

    when {
        prevMk?.sourceBitmap != null -> {
            sourceBmp = prevMk.sourceBitmap
            baseBmp = prevMk.baseBitmap?.takeIf { !it.isRecycled }
        }
        transforming && (transformSelectionScopedBitmap != null || transformBackupRef != null) -> {
            sourceBmp = (transformSelectionScopedBitmap ?: transformBackupRef)!!
            baseBmp = transformStaticBaseBitmap?.takeIf { !it.isRecycled }
        }
        selectionMask != null && !selectionMask!!.isEmpty() -> {
            val mask = selectionMask!!
            sourceBmp = mask.extractCopy(layer.bitmap)
            baseBmp = layer.bitmap.copy(Bitmap.Config.ARGB_8888, true).also { mask.clearBitmap(it) }
            recycleTempSource = true
            recycleTempBase = true
        }
        else -> {
            sourceBmp = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
            baseBmp = null
            recycleTempSource = true
        }
    }

    if (sourceBmp.isRecycled) return

    val framePoints = mutableListOf<Int>()
    var cur = fromF
    while (cur < toF) {
        framePoints.add(cur)
        cur += step
    }
    if (framePoints.lastOrNull() != toF) {
        framePoints.add(toF)
    }

    val span = (toF - fromF).coerceAtLeast(1).toFloat()
    val newClips = ArrayList<TimelineClip>(framePoints.size)
    val newTotal = max(animationState.totalFrames, toF + 1).coerceAtMost(1200)

    for (idx in framePoints.indices) {
        val f = framePoints[idx]
        val nextF = if (idx < framePoints.lastIndex) framePoints[idx + 1] else (f + 1)
        val rawT = (f - fromF) / span
        val easedT = curve.evaluate(rawT, bezier)
        val interp = TransformEngine.interpolateTransform(startState, endState, easedT)
        val bmp = renderCompositeFrame(art.width, art.height, sourceBmp, baseBmp, interp)
        val label = when (idx) {
            0 -> "Key A"
            framePoints.lastIndex -> "Key B"
            else -> "Tween"
        }
        newClips.add(
            TimelineClip(
                id = UUID.randomUUID().toString(),
                startFrame = f,
                durationFrames = (nextF - f).coerceAtLeast(1),
                label = label,
                bitmap = bmp,
                thumbnail = LayerCompositor.makeThumbnail(bmp, 72).asImageBitmap(),
            ),
        )
    }

    // Store/update start & end MotionKeyframes with preserved crisp source/base copies
    val savedSrc = sourceBmp.copy(Bitmap.Config.ARGB_8888, false)
    val savedBase = baseBmp?.copy(Bitmap.Config.ARGB_8888, false)
    val endSrc = sourceBmp.copy(Bitmap.Config.ARGB_8888, false)
    val endBase = baseBmp?.copy(Bitmap.Config.ARGB_8888, false)

    if (recycleTempSource) sourceBmp.recycle()
    if (recycleTempBase && baseBmp != null) baseBmp.recycle()

    val startMk = MotionKeyframe(
        id = UUID.randomUUID().toString(),
        trackId = track.id,
        frameIndex = fromF,
        translateX = startState.translateX,
        translateY = startState.translateY,
        scaleX = startState.scaleX,
        scaleY = startState.scaleY,
        rotationDeg = startState.rotationDeg,
        anchorX = startState.anchorX,
        anchorY = startState.anchorY,
        boundsLeft = startState.boundsLeft,
        boundsTop = startState.boundsTop,
        boundsRight = startState.boundsRight,
        boundsBottom = startState.boundsBottom,
        curve = curve,
        bezier = bezier,
        sourceBitmap = savedSrc,
        baseBitmap = savedBase,
    )
    val endMk = MotionKeyframe(
        id = UUID.randomUUID().toString(),
        trackId = track.id,
        frameIndex = toF,
        translateX = endState.translateX,
        translateY = endState.translateY,
        scaleX = endState.scaleX,
        scaleY = endState.scaleY,
        rotationDeg = endState.rotationDeg,
        anchorX = endState.anchorX,
        anchorY = endState.anchorY,
        boundsLeft = endState.boundsLeft,
        boundsTop = endState.boundsTop,
        boundsRight = endState.boundsRight,
        boundsBottom = endState.boundsBottom,
        curve = curve,
        bezier = bezier,
        sourceBitmap = endSrc,
        baseBitmap = endBase,
    )

    motionKeyframes = (motionKeyframes.filterNot {
        it.trackId == track.id && (it.frameIndex == fromF || it.frameIndex == toF)
    } + listOf(startMk, endMk)).sortedBy { it.frameIndex }

    val mergedClips = replaceTrackRangeWithClips(track.clips, fromF, toF + 1, newClips)
    timelineTracks = timelineTracks.map { if (it.id == track.id) it.copy(clips = mergedClips) else it }

    // Exit floating transform session without pushing obsolete backup on the new keyframe bitmap
    if (transforming) {
        clearTransformBackupsWithoutUndo()
    }

    animationState = animationState.copy(
        totalFrames = newTotal,
        currentFrameIndex = toF,
        selectedClipId = newClips.lastOrNull()?.id,
    )
    rebuildTimelineRenderState()
    recomposite()
    refreshLayerThumbnail(activeLayerIndex)
    persistCurrentArtwork()
    showNotice("In-Between: ${newClips.size} frames (${curve.label})")
}
