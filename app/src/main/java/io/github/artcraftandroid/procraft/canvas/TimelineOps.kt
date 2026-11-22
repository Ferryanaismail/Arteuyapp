package io.github.artcraftandroid.procraft.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.procraft.engine.LayerCompositor
import io.github.artcraftandroid.procraft.engine.MediaTimelineEngine
import io.github.artcraftandroid.procraft.engine.OnionSkinFrame
import io.github.artcraftandroid.procraft.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val TRACK_LABEL_PALETTE = TrackColorLabel.entries

internal fun CanvasViewModel.ensureTimelineInitialized() {
    val total = animationState.totalFrames.coerceAtLeast(1)
    if (timelineTracks.isEmpty()) {
        val created = layers.asReversed().mapIndexed { idx, layer ->
            val clip = TimelineClip(
                id = UUID.randomUUID().toString(),
                startFrame = 0,
                durationFrames = total,
                label = "Key 1",
                bitmap = layer.bitmap,
                thumbnail = layer.thumbnail ?: if (!layer.bitmap.isRecycled) {
                    LayerCompositor.makeThumbnail(layer.bitmap, 72).asImageBitmap()
                } else null,
            )
            TimelineTrack(
                id = UUID.randomUUID().toString(),
                name = layer.name,
                kind = TrackKind.PAINT,
                colorLabel = TRACK_LABEL_PALETTE[idx % TRACK_LABEL_PALETTE.size],
                visible = layer.visible,
                locked = layer.locked,
                opacity = layer.opacity,
                layerId = layer.id,
                clips = listOf(clip),
            )
        }
        timelineTracks = created
    } else {
        syncPaintTracksWithLayers()
    }
}

internal fun CanvasViewModel.syncPaintTracksWithLayers() {
    val total = animationState.totalFrames.coerceAtLeast(1)
    val existingByLayerId = timelineTracks
        .filter { it.kind == TrackKind.PAINT && it.layerId != null }
        .associateBy { it.layerId!! }

    val layerIds = layers.map { it.id }.toSet()
    val keptNonPaint = timelineTracks.filter { it.kind != TrackKind.PAINT || it.layerId in layerIds }
    val updatedTracks = mutableListOf<TimelineTrack>()

    // Keep existing track positions for non-paint and existing paint tracks
    keptNonPaint.forEach { tr ->
        if (tr.kind == TrackKind.PAINT && tr.layerId != null) {
            val l = layers.firstOrNull { it.id == tr.layerId }
            if (l != null) {
                updatedTracks.add(tr.copy(name = l.name, visible = l.visible, locked = l.locked, opacity = l.opacity))
            }
        } else {
            updatedTracks.add(tr)
        }
    }

    // Add any newly created LayerState that doesn't yet have a track
    var colorOffset = updatedTracks.size
    layers.asReversed().forEach { l ->
        if (!existingByLayerId.containsKey(l.id)) {
            val clip = TimelineClip(
                id = UUID.randomUUID().toString(),
                startFrame = 0,
                durationFrames = total,
                label = "Key 1",
                bitmap = l.bitmap,
                thumbnail = l.thumbnail ?: if (!l.bitmap.isRecycled) {
                    LayerCompositor.makeThumbnail(l.bitmap, 72).asImageBitmap()
                } else null,
            )
            val newTrack = TimelineTrack(
                id = UUID.randomUUID().toString(),
                name = l.name,
                kind = TrackKind.PAINT,
                colorLabel = TRACK_LABEL_PALETTE[colorOffset++ % TRACK_LABEL_PALETTE.size],
                visible = l.visible,
                locked = l.locked,
                opacity = l.opacity,
                layerId = l.id,
                clips = listOf(clip),
            )
            updatedTracks.add(0, newTrack)
        }
    }

    timelineTracks = updatedTracks
}

internal fun CanvasViewModel.isBitmapOwnedByTimeline(bmp: Bitmap): Boolean {
    return timelineTracks.any { tr ->
        tr.clips.any { cl -> cl.bitmap === bmp || cl.videoFrames.any { it === bmp } }
    }
}

internal fun CanvasViewModel.findClipBitmapById(clipId: String): Bitmap? {
    for (tr in timelineTracks) {
        val clip = tr.clips.firstOrNull { it.id == clipId }
        if (clip?.bitmap != null && !clip.bitmap.isRecycled) return clip.bitmap
    }
    return null
}

internal fun CanvasViewModel.recycleAllTimelineAndLayerBitmaps() {
    val recycled = HashSet<Int>()
    layers.forEach { l ->
        val id = System.identityHashCode(l.bitmap)
        if (recycled.add(id) && !l.bitmap.isRecycled) l.bitmap.recycle()
    }
    timelineTracks.forEach { tr ->
        tr.clips.forEach { cl ->
            cl.bitmap?.let { b ->
                val id = System.identityHashCode(b)
                if (recycled.add(id) && !b.isRecycled) b.recycle()
            }
            cl.videoFrames.forEach { vb ->
                val id = System.identityHashCode(vb)
                if (recycled.add(id) && !vb.isRecycled) vb.recycle()
            }
        }
    }
}

internal fun CanvasViewModel.refreshTimelineClipThumbnails() {
    timelineTracks = timelineTracks.map { tr ->
        if (tr.kind != TrackKind.PAINT) tr
        else tr.copy(
            clips = tr.clips.map { cl ->
                val bmp = cl.bitmap
                if (bmp != null && !bmp.isRecycled) {
                    cl.copy(thumbnail = LayerCompositor.makeThumbnail(bmp, 72).asImageBitmap())
                } else cl
            },
        )
    }
}

internal fun CanvasViewModel.refreshSingleClipThumbnailAsync(clipId: String) {
    viewModelScope.launch {
        delay(120)
        val bmp = findClipBitmapById(clipId) ?: return@launch
        val thumb = withContext(Dispatchers.Default) {
            LayerCompositor.makeThumbnail(bmp, 72).asImageBitmap()
        }
        timelineTracks = timelineTracks.map { tr ->
            if (tr.clips.none { it.id == clipId }) tr
            else tr.copy(
                clips = tr.clips.map { cl -> if (cl.id == clipId) cl.copy(thumbnail = thumb) else cl },
            )
        }
    }
}

internal fun CanvasViewModel.buildRenderLayersForFrame(frame: Int): List<LayerState> {
    if (timelineTracks.isEmpty()) return layers
    val layerById = layers.associateBy { it.id }
    val groups = timelineTracks.filter { it.kind == TrackKind.GROUP }.associateBy { it.id }
    val out = ArrayList<LayerState>(timelineTracks.size)

    // timelineTracks is top-to-bottom; compositing draws bottom-to-top
    for (i in timelineTracks.indices.reversed()) {
        val tr = timelineTracks[i]
        val parent = tr.groupId?.let { groups[it] }
        val groupActive = parent == null || (parent.visible && !parent.muted)
        val groupOpacity = parent?.opacity ?: 1f
        val orderClip = if (parent != null && parent.isLayerOrderGroup) parent.clipAt(frame) else null
        val blockedByOrder = parent != null && parent.isLayerOrderGroup &&
            (orderClip == null || orderClip.orderTargetTrackId != tr.id)
        val trackActive = tr.visible && !tr.muted && groupActive && !blockedByOrder
        val effOpacity = (tr.opacity * groupOpacity).coerceIn(0f, 1f)

        when (tr.kind) {
            TrackKind.GROUP, TrackKind.AUDIO -> Unit
            TrackKind.PAINT -> {
                if (!trackActive) continue
                val baseLayer = tr.layerId?.let { layerById[it] } ?: continue
                val clip = tr.clipAt(frame)
                    ?: if (parent?.isLayerOrderGroup == true) tr.clips.firstOrNull() else null
                val bmp = clip?.bitmap
                    ?: if (parent?.isLayerOrderGroup == true) baseLayer.bitmap else null
                if (bmp != null && !bmp.isRecycled) {
                    out.add(baseLayer.copy(bitmap = bmp, visible = true, opacity = effOpacity))
                }
            }
            TrackKind.VIDEO -> {
                if (!trackActive) continue
                val clip = tr.clipAt(frame)
                    ?: if (parent?.isLayerOrderGroup == true) tr.clips.firstOrNull() else null
                if (clip != null && clip.videoFrames.isNotEmpty()) {
                    val vIdx = ((frame - clip.startFrame + clip.sourceFrameOffset).coerceAtLeast(0)) % clip.videoFrames.size
                    val vb = clip.videoFrames[vIdx]
                    if (!vb.isRecycled) {
                        out.add(
                            LayerState(
                                id = "video_${tr.id}",
                                name = tr.name,
                                visible = true,
                                opacity = effOpacity,
                                bitmap = vb,
                            ),
                        )
                    }
                }
            }
        }
    }
    return out
}

internal fun CanvasViewModel.rebuildTimelineRenderState() {
    if (!animationState.isAnimation || timelineTracks.isEmpty()) {
        renderLayersCache = layers
        onionSkinCache = emptyList()
        return
    }
    val frame = animationState.currentFrameIndex.coerceIn(0, (animationState.totalFrames - 1).coerceAtLeast(0))
    val trackByLayerId = timelineTracks.filter { it.kind == TrackKind.PAINT && it.layerId != null }
        .associateBy { it.layerId!! }

    // Bind each Paint LayerState's active bitmap to the keyframe bitmap covering `frame`
    val updatedLayers = layers.map { l ->
        val tr = trackByLayerId[l.id]
        val clip = tr?.clipAt(frame)
        val bmp = clip?.bitmap
        if (bmp != null && !bmp.isRecycled && bmp !== l.bitmap) {
            l.copy(bitmap = bmp, thumbnail = clip.thumbnail ?: l.thumbnail)
        } else {
            l
        }
    }
    if (updatedLayers != layers) {
        setLayersDirect(updatedLayers)
    }

    renderLayersCache = buildRenderLayersForFrame(frame)

    // Compute Krita-style Onion Skinning for the active/selected Paint Track across adjacent keyframes
    if (!animationState.onionSkinEnabled) {
        onionSkinCache = emptyList()
        return
    }
    val activeTrack = timelineTracks.firstOrNull { it.id == animationState.selectedTrackId && it.kind == TrackKind.PAINT }
        ?: activeLayer()?.let { al -> timelineTracks.firstOrNull { it.kind == TrackKind.PAINT && it.layerId == al.id } }
    if (activeTrack == null || activeTrack.clips.isEmpty()) {
        onionSkinCache = emptyList()
        return
    }

    val sortedClips = activeTrack.clips.sortedBy { it.startFrame }
    val curIdx = sortedClips.indexOfFirst { it.coversFrame(frame) }
    val range = animationState.onionSkinFrames.coerceIn(1, 4)
    val skins = ArrayList<OnionSkinFrame>(range * 2)

    if (curIdx >= 0) {
        for (step in 1..range) {
            val pi = curIdx - step
            if (pi >= 0) {
                sortedClips[pi].bitmap?.takeIf { !it.isRecycled }?.let {
                    skins.add(OnionSkinFrame(it, -step))
                }
            }
            val ni = curIdx + step
            if (ni < sortedClips.size) {
                sortedClips[ni].bitmap?.takeIf { !it.isRecycled }?.let {
                    skins.add(OnionSkinFrame(it, step))
                }
            }
        }
    } else {
        // Inside an empty gap: show nearest previous and next keyframes
        val prevClips = sortedClips.filter { it.endFrameExclusive <= frame }.takeLast(range)
        prevClips.asReversed().forEachIndexed { i, cl ->
            cl.bitmap?.takeIf { !it.isRecycled }?.let { skins.add(OnionSkinFrame(it, -(i + 1))) }
        }
        val nextClips = sortedClips.filter { it.startFrame > frame }.take(range)
        nextClips.forEachIndexed { i, cl ->
            cl.bitmap?.takeIf { !it.isRecycled }?.let { skins.add(OnionSkinFrame(it, i + 1)) }
        }
    }
    onionSkinCache = skins
}

fun CanvasViewModel.scrubToFrame(frame: Int, fromUserDrag: Boolean = true) {
    val maxFrame = (animationState.totalFrames - 1).coerceAtLeast(0)
    val clamped = frame.coerceIn(0, maxFrame)
    if (clamped == animationState.currentFrameIndex && !fromUserDrag) return
    animationState = animationState.copy(currentFrameIndex = clamped)
    if (fromUserDrag && animationState.audioScrubEnabled) {
        audioEngine.scrubFrame(clamped, animationState.fps, timelineTracks)
    }
    rebuildTimelineRenderState()
    recomposite()
}

fun CanvasViewModel.stepFrame(delta: Int) {
    if (animationState.playing) toggleAnimationPlayback()
    val maxF = (animationState.totalFrames - 1).coerceAtLeast(0)
    val next = (animationState.currentFrameIndex + delta).coerceIn(0, maxF)
    scrubToFrame(next, fromUserDrag = animationState.audioScrubEnabled)
}

fun CanvasViewModel.toggleAudioScrubbing() {
    val next = !animationState.audioScrubEnabled
    animationState = animationState.copy(audioScrubEnabled = next)
    showNotice(if (next) "Audio Scrubbing ON" else "Audio Scrubbing OFF")
    persistCurrentArtwork()
}

fun CanvasViewModel.toggleOnionSkin() {
    val next = !animationState.onionSkinEnabled
    animationState = animationState.copy(onionSkinEnabled = next)
    rebuildTimelineRenderState()
    recomposite()
    showNotice(if (next) "Onion Skin ON" else "Onion Skin OFF")
}

fun CanvasViewModel.setOnionSkinFrames(count: Int) {
    animationState = animationState.copy(onionSkinFrames = count.coerceIn(1, 4))
    rebuildTimelineRenderState()
    recomposite()
}

fun CanvasViewModel.setTimelineFps(fps: Int) {
    val safe = fps.coerceIn(1, 60)
    animationState = animationState.copy(fps = safe)
    persistCurrentArtwork()
    showNotice("Timeline FPS: $safe")
}

fun CanvasViewModel.setTimelineDuration(totalFrames: Int) {
    val safe = totalFrames.coerceIn(2, 1200)
    val oldTotal = animationState.totalFrames
    // Extend trailing paint keyframes cleanly if user extends duration
    if (safe > oldTotal) {
        timelineTracks = timelineTracks.map { tr ->
            if (tr.kind != TrackKind.PAINT || tr.clips.isEmpty()) tr
            else {
                val sorted = tr.clips.sortedBy { it.startFrame }.toMutableList()
                val last = sorted.last()
                if (last.endFrameExclusive >= oldTotal) {
                    sorted[sorted.lastIndex] = last.copy(durationFrames = (safe - last.startFrame).coerceAtLeast(1))
                }
                tr.copy(clips = sorted)
            }
        }
    }
    val clampedFrame = animationState.currentFrameIndex.coerceIn(0, safe - 1)
    animationState = animationState.copy(totalFrames = safe, currentFrameIndex = clampedFrame)
    rebuildTimelineRenderState()
    recomposite()
    persistCurrentArtwork()
}

private fun CanvasViewModel.resolveTargetPaintTrackId(requestedTrackId: String?): String? {
    val explicit = requestedTrackId?.let { id ->
        timelineTracks.firstOrNull { it.id == id && it.kind == TrackKind.PAINT }
    }
    if (explicit != null) return explicit.id
    val selected = animationState.selectedTrackId?.let { id ->
        timelineTracks.firstOrNull { it.id == id && it.kind == TrackKind.PAINT }
    }
    if (selected != null) return selected.id
    val activeL = activeLayer()
    if (activeL != null) {
        val byLayer = timelineTracks.firstOrNull { it.kind == TrackKind.PAINT && it.layerId == activeL.id }
        if (byLayer != null) return byLayer.id
    }
    return timelineTracks.firstOrNull { it.kind == TrackKind.PAINT }?.id
}

fun CanvasViewModel.ensurePaintKeyframeAtCurrentFrame() {
    val trackId = resolveTargetPaintTrackId(animationState.selectedTrackId) ?: return
    val tr = timelineTracks.firstOrNull { it.id == trackId } ?: return
    val frame = animationState.currentFrameIndex
    if (tr.clipAt(frame) == null) {
        insertKeyframeInternal(trackId = trackId, frame = frame, duplicateSource = false, silent = true)
    }
}

fun CanvasViewModel.insertBlankKeyframe(trackId: String? = null, frame: Int = animationState.currentFrameIndex) {
    val targetId = resolveTargetPaintTrackId(trackId) ?: run {
        showNotice("Select a Paint track first")
        return
    }
    insertKeyframeInternal(targetId, frame, duplicateSource = false, silent = false)
}

fun CanvasViewModel.toggleTimelineSnapping() {
    val next = !animationState.timelineSnapping
    animationState = animationState.copy(timelineSnapping = next)
    showNotice(if (next) "Timeline Snapping ON" else "Timeline Snapping OFF")
    persistCurrentArtwork()
}

fun CanvasViewModel.insertDuplicateKeyframe(trackId: String? = null, frame: Int = animationState.currentFrameIndex) {
    val targetId = resolveTargetPaintTrackId(trackId) ?: run {
        showNotice("Select a Paint track first")
        return
    }
    val art = currentArtwork ?: return
    val tr = timelineTracks.firstOrNull { it.id == targetId && it.kind == TrackKind.PAINT } ?: run {
        insertKeyframeInternal(targetId, frame, duplicateSource = true, silent = false)
        return
    }
    if (tr.locked) {
        showNotice("${tr.name} is locked")
        return
    }
    val safeFrame = frame.coerceIn(0, (animationState.totalFrames - 1).coerceAtLeast(0))
    val sorted = tr.clips.sortedBy { it.startFrame }
    val selectedOnTrack = animationState.selectedClipId?.let { cid -> sorted.firstOrNull { it.id == cid } }
    val sourceClip = selectedOnTrack
        ?: tr.clipAt(safeFrame)
        ?: sorted.lastOrNull { it.endFrameExclusive <= safeFrame }

    if (sourceClip == null) {
        insertKeyframeInternal(targetId, frame, duplicateSource = true, silent = false)
        return
    }

    val dur = sourceClip.durationFrames.coerceAtLeast(1)
    val targetStart = sourceClip.endFrameExclusive
    val targetEnd = targetStart + dur
    val hasFrameAfter = sorted.any { it.id != sourceClip.id && it.startFrame >= sourceClip.endFrameExclusive }

    val clonedBmp = if (sourceClip.bitmap != null && !sourceClip.bitmap.isRecycled) {
        sourceClip.bitmap.copy(Bitmap.Config.ARGB_8888, true)
    } else {
        Bitmap.createBitmap(art.width, art.height, Bitmap.Config.ARGB_8888)
    }
    val newThumb = LayerCompositor.makeThumbnail(clonedBmp, 72).asImageBitmap()
    val newClipId = UUID.randomUUID().toString()

    if (hasFrameAfter || targetStart >= 1200) {
        // A keyframe already exists after it on this track: create a new Paint Track directly ABOVE it
        val trackIdx = timelineTracks.indexOfFirst { it.id == tr.id }.coerceAtLeast(0)
        val srcLayer = tr.layerId?.let { lid -> layers.firstOrNull { it.id == lid } }
        val newLayerId = UUID.randomUUID().toString()
        val newLayerName = "${tr.name} Dup"
        val newLayer = LayerState(
            id = newLayerId,
            name = newLayerName,
            visible = srcLayer?.visible ?: true,
            opacity = srcLayer?.opacity ?: 1f,
            blendMode = srcLayer?.blendMode ?: LayerBlendMode.NORMAL,
            bitmap = clonedBmp,
            thumbnail = newThumb,
        )
        setLayersDirect(layers + newLayer)

        val placeStart = targetStart.coerceIn(0, 1199)
        val newClip = TimelineClip(
            id = newClipId,
            startFrame = placeStart,
            durationFrames = dur,
            label = sourceClip.label.ifBlank { "Key" },
            bitmap = clonedBmp,
            thumbnail = newThumb,
        )
        val neededTotal = max(animationState.totalFrames, min(1200, newClip.endFrameExclusive))
        val newTrack = TimelineTrack(
            id = UUID.randomUUID().toString(),
            name = newLayerName,
            kind = TrackKind.PAINT,
            colorLabel = TRACK_LABEL_PALETTE[timelineTracks.size % TRACK_LABEL_PALETTE.size],
            visible = tr.visible,
            locked = false,
            layerId = newLayerId,
            groupId = tr.groupId,
            clips = listOf(newClip),
        )
        val mutableTracks = timelineTracks.toMutableList()
        mutableTracks.add(trackIdx, newTrack)
        timelineTracks = mutableTracks
        syncPaintLayersZOrder(mutableTracks)
        animationState = animationState.copy(
            totalFrames = neededTotal,
            currentFrameIndex = placeStart.coerceIn(0, (neededTotal - 1).coerceAtLeast(0)),
            selectedTrackId = newTrack.id,
            selectedClipId = newClipId,
        )
        rebuildTimelineRenderState()
        recomposite()
        persistCurrentArtwork()
        showNotice("Duplicated to new track above (${newTrack.name})")
    } else {
        // No frame after it: place duplicate immediately after sourceClip on the same track
        val newClip = TimelineClip(
            id = newClipId,
            startFrame = targetStart,
            durationFrames = dur,
            label = "Key ${sorted.size + 1}",
            bitmap = clonedBmp,
            thumbnail = newThumb,
        )
        val neededTotal = max(animationState.totalFrames, min(1200, targetEnd))
        val finalClips = (sorted + newClip).sortedBy { it.startFrame }
        timelineTracks = timelineTracks.map { if (it.id == tr.id) it.copy(clips = finalClips) else it }
        val layerIdx = layers.indexOfFirst { it.id == tr.layerId }
        if (layerIdx >= 0) {
            setActiveLayerIndexDirect(layerIdx)
        }
        animationState = animationState.copy(
            totalFrames = neededTotal,
            currentFrameIndex = targetStart.coerceIn(0, (neededTotal - 1).coerceAtLeast(0)),
            selectedTrackId = tr.id,
            selectedClipId = newClipId,
        )
        rebuildTimelineRenderState()
        recomposite()
        if (layerIdx >= 0) refreshLayerThumbnail(layerIdx)
        persistCurrentArtwork()
        showNotice("Duplicated Keyframe → F${targetStart + 1}")
    }
}

private fun CanvasViewModel.insertKeyframeInternal(
    trackId: String,
    frame: Int,
    duplicateSource: Boolean,
    silent: Boolean,
) {
    val art = currentArtwork ?: return
    val tr = timelineTracks.firstOrNull { it.id == trackId && it.kind == TrackKind.PAINT } ?: return
    if (tr.locked) {
        showNotice("${tr.name} is locked")
        return
    }
    val safeFrame = frame.coerceIn(0, (animationState.totalFrames - 1).coerceAtLeast(0))
    val sorted = tr.clips.sortedBy { it.startFrame }.toMutableList()
    val coveringIdx = sorted.indexOfFirst { it.coversFrame(safeFrame) }

    val newClipId = UUID.randomUUID().toString()
    val newBmp: Bitmap
    val newClips = mutableListOf<TimelineClip>()

    if (coveringIdx >= 0) {
        val covering = sorted[coveringIdx]
        newBmp = if (duplicateSource && covering.bitmap != null && !covering.bitmap.isRecycled) {
            covering.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            Bitmap.createBitmap(art.width, art.height, Bitmap.Config.ARGB_8888)
        }
        val origEnd = covering.endFrameExclusive
        sorted.forEachIndexed { idx, cl ->
            if (idx != coveringIdx) {
                newClips.add(cl)
            } else {
                if (safeFrame > cl.startFrame) {
                    // Trim earlier keyframe to end cleanly right before safeFrame
                    newClips.add(cl.copy(durationFrames = safeFrame - cl.startFrame))
                    newClips.add(
                        TimelineClip(
                            id = newClipId,
                            startFrame = safeFrame,
                            durationFrames = (origEnd - safeFrame).coerceAtLeast(1),
                            label = "Key ${sorted.size + 1}",
                            bitmap = newBmp,
                            thumbnail = LayerCompositor.makeThumbnail(newBmp, 72).asImageBitmap(),
                        ),
                    )
                } else {
                    // safeFrame == cl.startFrame: split 1-frame head OR replace clip bitmap with fresh keyframe
                    val tailDur = (origEnd - (safeFrame + 1)).coerceAtLeast(0)
                    newClips.add(
                        TimelineClip(
                            id = newClipId,
                            startFrame = safeFrame,
                            durationFrames = if (duplicateSource) 1 else cl.durationFrames,
                            label = "Key ${sorted.size + 1}",
                            bitmap = newBmp,
                            thumbnail = LayerCompositor.makeThumbnail(newBmp, 72).asImageBitmap(),
                        ),
                    )
                    if (duplicateSource && tailDur > 0) {
                        newClips.add(cl.copy(startFrame = safeFrame + 1, durationFrames = tailDur))
                    }
                }
            }
        }
    } else {
        // Empty gap: insert keyframe spanning until next clip or timeline end
        val nextStart = sorted.firstOrNull { it.startFrame > safeFrame }?.startFrame ?: animationState.totalFrames
        val dur = (nextStart - safeFrame).coerceAtLeast(1)
        val fallbackPrev = sorted.lastOrNull { it.endFrameExclusive <= safeFrame }
        newBmp = if (duplicateSource && fallbackPrev?.bitmap != null && !fallbackPrev.bitmap.isRecycled) {
            fallbackPrev.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            Bitmap.createBitmap(art.width, art.height, Bitmap.Config.ARGB_8888)
        }
        newClips.addAll(sorted)
        newClips.add(
            TimelineClip(
                id = newClipId,
                startFrame = safeFrame,
                durationFrames = dur,
                label = "Key ${sorted.size + 1}",
                bitmap = newBmp,
                thumbnail = LayerCompositor.makeThumbnail(newBmp, 72).asImageBitmap(),
            ),
        )
    }

    val finalClips = newClips.sortedBy { it.startFrame }
    timelineTracks = timelineTracks.map { if (it.id == trackId) it.copy(clips = finalClips) else it }

    // Focus the corresponding layer & clip
    val layerIdx = layers.indexOfFirst { it.id == tr.layerId }
    if (layerIdx >= 0) {
        setActiveLayerIndexDirect(layerIdx)
    }
    animationState = animationState.copy(
        currentFrameIndex = safeFrame,
        selectedTrackId = trackId,
        selectedClipId = newClipId,
    )
    rebuildTimelineRenderState()
    recomposite()
    if (layerIdx >= 0) refreshLayerThumbnail(layerIdx)
    persistCurrentArtwork()
    if (!silent) {
        showNotice(if (duplicateSource) "Duplicate Keyframe F${safeFrame + 1}" else "Keyframe inserted F${safeFrame + 1}")
    }
}

fun CanvasViewModel.selectTrack(trackId: String) {
    val tr = timelineTracks.firstOrNull { it.id == trackId } ?: return
    val clipAtPlayhead = tr.clipAt(animationState.currentFrameIndex)
    animationState = animationState.copy(
        selectedTrackId = trackId,
        selectedClipId = clipAtPlayhead?.id ?: tr.clips.firstOrNull()?.id,
    )
    if (tr.kind == TrackKind.PAINT && tr.layerId != null) {
        val lIdx = layers.indexOfFirst { it.id == tr.layerId }
        if (lIdx >= 0) setActiveLayerIndexDirect(lIdx)
    }
    rebuildTimelineRenderState()
    recomposite()
}

fun CanvasViewModel.selectClip(trackId: String, clipId: String?) {
    animationState = animationState.copy(selectedTrackId = trackId, selectedClipId = clipId)
    val tr = timelineTracks.firstOrNull { it.id == trackId }
    if (tr?.kind == TrackKind.PAINT && tr.layerId != null) {
        val lIdx = layers.indexOfFirst { it.id == tr.layerId }
        if (lIdx >= 0) setActiveLayerIndexDirect(lIdx)
    }
    rebuildTimelineRenderState()
    recomposite()
}

fun CanvasViewModel.addPaintTrack() {
    val art = currentArtwork ?: return
    ensureTimelineInitialized()
    val newLayerId = UUID.randomUUID().toString()
    val bmp = Bitmap.createBitmap(art.width, art.height, Bitmap.Config.ARGB_8888)
    val count = layers.size + 1
    val newLayer = LayerState(id = newLayerId, name = "Layer $count", bitmap = bmp)
    setLayersDirect(layers + newLayer)
    setActiveLayerIndexDirect(layers.lastIndex)

    val newClip = TimelineClip(
        id = UUID.randomUUID().toString(),
        startFrame = 0,
        durationFrames = animationState.totalFrames.coerceAtLeast(1),
        label = "Key 1",
        bitmap = bmp,
        thumbnail = LayerCompositor.makeThumbnail(bmp, 72).asImageBitmap(),
    )
    val color = TRACK_LABEL_PALETTE[timelineTracks.size % TRACK_LABEL_PALETTE.size]
    val newTrack = TimelineTrack(
        id = UUID.randomUUID().toString(),
        name = newLayer.name,
        kind = TrackKind.PAINT,
        colorLabel = color,
        layerId = newLayerId,
        clips = listOf(newClip),
    )
    timelineTracks = listOf(newTrack) + timelineTracks
    animationState = animationState.copy(selectedTrackId = newTrack.id, selectedClipId = newClip.id)
    rebuildTimelineRenderState()
    recomposite()
    refreshLayerThumbnail(activeLayerIndex)
    persistCurrentArtwork()
    showNotice("Added ${newTrack.name}")
}

fun CanvasViewModel.duplicatePaintTrack(trackId: String) {
    val art = currentArtwork ?: return
    val srcIdx = timelineTracks.indexOfFirst { it.id == trackId && it.kind == TrackKind.PAINT }
    if (srcIdx < 0) return
    val srcTrack = timelineTracks[srcIdx]
    val srcLayer = srcTrack.layerId?.let { lid -> layers.firstOrNull { it.id == lid } }
    val newLayerId = UUID.randomUUID().toString()
    val newTrackId = UUID.randomUUID().toString()
    val clonedClips = srcTrack.clips.map { cl ->
        val bmpCopy = cl.bitmap?.takeIf { !it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, true)
        val thumb = bmpCopy?.let { LayerCompositor.makeThumbnail(it, 72).asImageBitmap() } ?: cl.thumbnail
        cl.copy(
            id = UUID.randomUUID().toString(),
            bitmap = bmpCopy,
            thumbnail = thumb,
        )
    }
    val initialBmp = clonedClips.firstOrNull { it.coversFrame(animationState.currentFrameIndex) }?.bitmap
        ?: clonedClips.firstOrNull()?.bitmap
        ?: Bitmap.createBitmap(art.width, art.height, Bitmap.Config.ARGB_8888)
    val newLayerName = "${srcTrack.name} Copy"
    val newLayer = LayerState(
        id = newLayerId,
        name = newLayerName,
        visible = srcLayer?.visible ?: srcTrack.visible,
        opacity = srcLayer?.opacity ?: 1f,
        blendMode = srcLayer?.blendMode ?: LayerBlendMode.NORMAL,
        locked = srcLayer?.locked ?: srcTrack.locked,
        alphaLock = srcLayer?.alphaLock ?: false,
        clippingMask = srcLayer?.clippingMask ?: false,
        bitmap = initialBmp,
        thumbnail = if (!initialBmp.isRecycled) LayerCompositor.makeThumbnail(initialBmp, 96).asImageBitmap() else null,
    )
    setLayersDirect(layers + newLayer)

    val newTrack = srcTrack.copy(
        id = newTrackId,
        name = newLayerName,
        layerId = newLayerId,
        clips = clonedClips,
    )
    val mutableTracks = timelineTracks.toMutableList()
    mutableTracks.add(srcIdx, newTrack)
    timelineTracks = mutableTracks
    syncPaintLayersZOrder(mutableTracks)

    val clipAtPlayhead = newTrack.clipAt(animationState.currentFrameIndex) ?: newTrack.clips.firstOrNull()
    animationState = animationState.copy(
        selectedTrackId = newTrackId,
        selectedClipId = clipAtPlayhead?.id,
    )
    rebuildTimelineRenderState()
    recomposite()
    persistCurrentArtwork()
    showNotice("Duplicated ${srcTrack.name}")
}

fun CanvasViewModel.renameTrack(trackId: String, name: String) {
    val clean = name.trim().ifBlank { return }
    val target = timelineTracks.firstOrNull { it.id == trackId } ?: return
    timelineTracks = timelineTracks.map { if (it.id == trackId) it.copy(name = clean) else it }
    if (target.kind == TrackKind.PAINT && target.layerId != null) {
        setLayersDirect(layers.map { if (it.id == target.layerId) it.copy(name = clean) else it })
    }
    rebuildTimelineRenderState()
    persistCurrentArtwork()
}

fun CanvasViewModel.setTrackColorLabel(trackId: String, label: TrackColorLabel) {
    timelineTracks = timelineTracks.map { if (it.id == trackId) it.copy(colorLabel = label) else it }
    persistCurrentArtwork()
}

fun CanvasViewModel.toggleTrackVisibility(trackId: String) {
    val target = timelineTracks.firstOrNull { it.id == trackId } ?: return
    val nextVis = !target.visible
    timelineTracks = timelineTracks.map { if (it.id == trackId) it.copy(visible = nextVis) else it }
    if (target.kind == TrackKind.PAINT && target.layerId != null) {
        setLayersDirect(layers.map { if (it.id == target.layerId) it.copy(visible = nextVis) else it })
    }
    rebuildTimelineRenderState()
    recomposite()
    persistCurrentArtwork()
}

fun CanvasViewModel.toggleTrackMute(trackId: String) {
    timelineTracks = timelineTracks.map { if (it.id == trackId) it.copy(muted = !it.muted) else it }
    rebuildTimelineRenderState()
    recomposite()
    persistCurrentArtwork()
}

fun CanvasViewModel.toggleTrackLock(trackId: String) {
    val target = timelineTracks.firstOrNull { it.id == trackId } ?: return
    val nextLock = !target.locked
    timelineTracks = timelineTracks.map { if (it.id == trackId) it.copy(locked = nextLock) else it }
    if (target.kind == TrackKind.PAINT && target.layerId != null) {
        setLayersDirect(layers.map { if (it.id == target.layerId) it.copy(locked = nextLock) else it })
    }
    persistCurrentArtwork()
}

fun CanvasViewModel.moveTrackOrder(trackId: String, delta: Int) {
    val idx = timelineTracks.indexOfFirst { it.id == trackId }
    if (idx < 0) return
    val targetIdx = (idx + delta).coerceIn(0, timelineTracks.lastIndex)
    if (targetIdx == idx) return

    val mutable = timelineTracks.toMutableList()
    val item = mutable.removeAt(idx)
    mutable.add(targetIdx, item)
    timelineTracks = mutable

    // Sync Paint Layer z-order (layers is bottom-to-top, timelineTracks is top-to-bottom)
    syncPaintLayersZOrder(mutable)
    rebuildTimelineRenderState()
    recomposite()
    persistCurrentArtwork()
}

private fun CanvasViewModel.syncPaintLayersZOrder(orderedTracks: List<TimelineTrack>) {
    val layerMap = layers.associateBy { it.id }
    val orderedPaintLayers = orderedTracks.asReversed()
        .filter { it.kind == TrackKind.PAINT && it.layerId != null }
        .mapNotNull { layerMap[it.layerId!!] }
    if (orderedPaintLayers.size == layers.size) {
        val activeId = activeLayer()?.id
        setLayersDirect(orderedPaintLayers)
        val newActive = orderedPaintLayers.indexOfFirst { it.id == activeId }.takeIf { it >= 0 } ?: 0
        setActiveLayerIndexDirect(newActive)
    }
}

fun CanvasViewModel.reorderOrGroupTrack(
    draggedTrackId: String,
    visibleTracksSnapshot: List<TimelineTrack>,
    targetSlotVisibleIdx: Int,
    dropIntoGroupId: String?,
) {
    val dragged = timelineTracks.firstOrNull { it.id == draggedTrackId } ?: return
    if (dropIntoGroupId != null && dragged.kind != TrackKind.GROUP && dropIntoGroupId != draggedTrackId) {
        val expanded = timelineTracks.map {
            if (it.id == dropIntoGroupId && it.kind == TrackKind.GROUP) it.copy(expanded = true) else it
        }
        timelineTracks = expanded
        assignTrackToGroup(draggedTrackId, dropIntoGroupId)
        val grpName = timelineTracks.firstOrNull { it.id == dropIntoGroupId }?.name ?: "Group"
        showNotice("${dragged.name} → $grpName")
        return
    }

    if (dragged.kind == TrackKind.GROUP) {
        val children = timelineTracks.filter { it.groupId == dragged.id }
        val blockIds = (listOf(dragged.id) + children.map { it.id }).toSet()
        val block = listOf(dragged) + children
        val remaining = timelineTracks.filterNot { it.id in blockIds }.toMutableList()
        val beforeTrack = visibleTracksSnapshot
            .drop(targetSlotVisibleIdx.coerceIn(0, visibleTracksSnapshot.size))
            .firstOrNull { it.id !in blockIds }
        val insertIdx = beforeTrack?.let { bt ->
            remaining.indexOfFirst { it.id == bt.id }.takeIf { it >= 0 }
        } ?: remaining.size
        remaining.addAll(insertIdx, block)
        timelineTracks = remaining
        syncPaintLayersZOrder(remaining)
        rebuildTimelineRenderState()
        recomposite()
        persistCurrentArtwork()
        return
    }

    val remaining = timelineTracks.filterNot { it.id == draggedTrackId }.toMutableList()
    val beforeTrack = visibleTracksSnapshot
        .drop(targetSlotVisibleIdx.coerceIn(0, visibleTracksSnapshot.size))
        .firstOrNull { it.id != draggedTrackId }
    val insertIdx = beforeTrack?.let { bt ->
        remaining.indexOfFirst { it.id == bt.id }.takeIf { it >= 0 }
    } ?: remaining.size

    val above = remaining.getOrNull(insertIdx - 1)
    val below = remaining.getOrNull(insertIdx)
    val nextGroupId = when {
        above?.kind == TrackKind.GROUP && above.expanded -> above.id
        above?.groupId != null && below?.groupId == above.groupId -> above.groupId
        else -> null
    }
    remaining.add(insertIdx, dragged.copy(groupId = nextGroupId))
    timelineTracks = remaining
    syncPaintLayersZOrder(remaining)
    rebuildTimelineRenderState()
    recomposite()
    persistCurrentArtwork()
}

fun CanvasViewModel.deleteTrack(trackId: String) {
    val target = timelineTracks.firstOrNull { it.id == trackId } ?: return
    if (target.kind == TrackKind.PAINT && timelineTracks.count { it.kind == TrackKind.PAINT } <= 1) {
        showNotice("Cannot delete the last Paint Track")
        return
    }
    val remaining = if (target.kind == TrackKind.GROUP) {
        timelineTracks.filter { it.id != trackId }.map {
            if (it.groupId == trackId) it.copy(groupId = null) else it
        }
    } else {
        timelineTracks.filter { it.id != trackId }
    }
    timelineTracks = remaining
    if (target.kind == TrackKind.PAINT && target.layerId != null) {
        setLayersDirect(layers.filter { it.id != target.layerId })
        setActiveLayerIndexDirect(activeLayerIndex.coerceIn(0, layers.lastIndex.coerceAtLeast(0)))
    }
    if (animationState.selectedTrackId == trackId) {
        animationState = animationState.copy(selectedTrackId = remaining.firstOrNull()?.id, selectedClipId = null)
    }
    if (trackId in animationState.selectedTrackIds) {
        animationState = animationState.copy(selectedTrackIds = animationState.selectedTrackIds - trackId)
    }
    if (layerOrderPickerGroupId == trackId) {
        layerOrderPickerGroupId = null
    }
    rebuildTimelineRenderState()
    recomposite()
    persistCurrentArtwork()
}

fun CanvasViewModel.setTrackOpacity(trackId: String, opacity: Float, persist: Boolean = false) {
    val clamped = opacity.coerceIn(0f, 1f)
    val target = timelineTracks.firstOrNull { it.id == trackId } ?: return
    timelineTracks = timelineTracks.map { if (it.id == trackId) it.copy(opacity = clamped) else it }
    if (target.kind == TrackKind.PAINT && target.layerId != null) {
        setLayersDirect(layers.map { if (it.id == target.layerId) it.copy(opacity = clamped) else it })
    }
    rebuildTimelineRenderState()
    recomposite()
    if (persist) persistCurrentArtwork()
}

fun CanvasViewModel.toggleTrackMultiSelect(trackId: String) {
    val cur = animationState.selectedTrackIds
    val next = if (trackId in cur) cur - trackId else cur + trackId
    animationState = animationState.copy(selectedTrackId = trackId, selectedTrackIds = next)
}

fun CanvasViewModel.clearMultiTrackSelection() {
    animationState = animationState.copy(selectedTrackIds = emptySet())
}

fun CanvasViewModel.groupSelectedTracks() {
    val selectedIds = animationState.selectedTrackIds
    val candidates = timelineTracks.filter { it.id in selectedIds && it.kind != TrackKind.GROUP }
    if (candidates.isEmpty()) {
        showNotice("Swipe right on tracks to multi-select")
        return
    }
    val groupId = UUID.randomUUID().toString()
    val groupCount = timelineTracks.count { it.kind == TrackKind.GROUP } + 1
    val groupTrack = TimelineTrack(
        id = groupId,
        name = "Group $groupCount",
        kind = TrackKind.GROUP,
        colorLabel = candidates.first().colorLabel,
        expanded = true,
    )
    val firstIdx = timelineTracks.indexOfFirst { it.id in selectedIds && it.kind != TrackKind.GROUP }.coerceAtLeast(0)
    val remaining = timelineTracks.filterNot { it.id in selectedIds && it.kind != TrackKind.GROUP }.toMutableList()
    val groupedChildren = candidates.map { it.copy(groupId = groupId) }
    val insertIdx = firstIdx.coerceIn(0, remaining.size)
    remaining.add(insertIdx, groupTrack)
    remaining.addAll(insertIdx + 1, groupedChildren)
    timelineTracks = remaining
    syncPaintLayersZOrder(remaining)
    animationState = animationState.copy(selectedTrackId = groupId, selectedTrackIds = emptySet())
    rebuildTimelineRenderState()
    recomposite()
    persistCurrentArtwork()
    showNotice("Grouped ${candidates.size} tracks")
}

fun CanvasViewModel.deleteSelectedTracks() {
    val selectedIds = animationState.selectedTrackIds
    if (selectedIds.isEmpty()) return
    val paintTracks = timelineTracks.filter { it.kind == TrackKind.PAINT }
    val remainingPaintAfterDelete = paintTracks.count { it.id !in selectedIds }
    val protectedPaintId = if (remainingPaintAfterDelete == 0) paintTracks.firstOrNull()?.id else null
    val effectiveDeleteIds = if (protectedPaintId != null) selectedIds - protectedPaintId else selectedIds

    val deletedLayerIds = timelineTracks
        .filter { it.id in effectiveDeleteIds && it.kind == TrackKind.PAINT && it.layerId != null }
        .mapNotNull { it.layerId }
        .toSet()

    val remaining = timelineTracks
        .filterNot { it.id in effectiveDeleteIds }
        .map { if (it.groupId != null && it.groupId in effectiveDeleteIds) it.copy(groupId = null) else it }

    timelineTracks = remaining
    if (deletedLayerIds.isNotEmpty()) {
        setLayersDirect(layers.filterNot { it.id in deletedLayerIds })
        setActiveLayerIndexDirect(activeLayerIndex.coerceIn(0, layers.lastIndex.coerceAtLeast(0)))
    }
    if (layerOrderPickerGroupId != null && layerOrderPickerGroupId in effectiveDeleteIds) {
        layerOrderPickerGroupId = null
    }
    animationState = animationState.copy(
        selectedTrackId = if (animationState.selectedTrackId in effectiveDeleteIds) remaining.firstOrNull()?.id else animationState.selectedTrackId,
        selectedTrackIds = emptySet(),
        selectedClipId = null,
    )
    rebuildTimelineRenderState()
    recomposite()
    persistCurrentArtwork()
    showNotice(if (protectedPaintId != null) "Deleted selected (kept 1 Paint track)" else "Deleted ${effectiveDeleteIds.size} tracks")
}

fun CanvasViewModel.toggleGroupLayerOrder(groupTrackId: String) {
    val tr = timelineTracks.firstOrNull { it.id == groupTrackId && it.kind == TrackKind.GROUP } ?: return
    val nextMode = !tr.isLayerOrderGroup
    timelineTracks = timelineTracks.map {
        if (it.id == groupTrackId) it.copy(isLayerOrderGroup = nextMode, expanded = true) else it
    }
    if (!nextMode && layerOrderPickerGroupId == groupTrackId) {
        layerOrderPickerGroupId = null
    }
    rebuildTimelineRenderState()
    recomposite()
    persistCurrentArtwork()
    showNotice(
        if (nextMode) "Group Layer Order ON — 3x tap group to pick frame"
        else "Standard Group restored",
    )
}

fun CanvasViewModel.insertLayerOrderKeyframe(
    groupTrackId: String,
    targetChildTrackId: String,
    frame: Int = animationState.currentFrameIndex,
) {
    val grp = timelineTracks.firstOrNull { it.id == groupTrackId && it.kind == TrackKind.GROUP && it.isLayerOrderGroup } ?: return
    val children = timelineTracks.filter { it.groupId == groupTrackId && it.kind != TrackKind.GROUP && it.kind != TrackKind.AUDIO }
    val childIdx = children.indexOfFirst { it.id == targetChildTrackId }
    val child = children.getOrNull(childIdx) ?: return

    val safeFrame = frame.coerceIn(0, (animationState.totalFrames - 1).coerceAtLeast(0))
    val sorted = grp.clips.sortedBy { it.startFrame }.toMutableList()
    val coveringIdx = sorted.indexOfFirst { it.coversFrame(safeFrame) }
    val thumb = child.clips.firstOrNull()?.thumbnail
        ?: layers.firstOrNull { it.id == child.layerId }?.thumbnail
    val newClipId = UUID.randomUUID().toString()
    val newClips = mutableListOf<TimelineClip>()

    if (coveringIdx >= 0) {
        val covering = sorted[coveringIdx]
        val origEnd = covering.endFrameExclusive
        sorted.forEachIndexed { idx, cl ->
            if (idx != coveringIdx) {
                newClips.add(cl)
            } else if (safeFrame > cl.startFrame) {
                newClips.add(cl.copy(durationFrames = safeFrame - cl.startFrame))
                newClips.add(
                    TimelineClip(
                        id = newClipId,
                        startFrame = safeFrame,
                        durationFrames = (origEnd - safeFrame).coerceAtLeast(1),
                        label = "${childIdx + 1}: ${child.name}",
                        thumbnail = thumb,
                        orderTargetTrackId = child.id,
                    ),
                )
            } else {
                newClips.add(
                    TimelineClip(
                        id = newClipId,
                        startFrame = safeFrame,
                        durationFrames = cl.durationFrames,
                        label = "${childIdx + 1}: ${child.name}",
                        thumbnail = thumb,
                        orderTargetTrackId = child.id,
                    ),
                )
            }
        }
    } else {
        val nextStart = sorted.firstOrNull { it.startFrame > safeFrame }?.startFrame ?: animationState.totalFrames
        val dur = (nextStart - safeFrame).coerceAtLeast(1)
        newClips.addAll(sorted)
        newClips.add(
            TimelineClip(
                id = newClipId,
                startFrame = safeFrame,
                durationFrames = dur,
                label = "${childIdx + 1}: ${child.name}",
                thumbnail = thumb,
                orderTargetTrackId = child.id,
            ),
        )
    }

    val finalClips = newClips.sortedBy { it.startFrame }
    timelineTracks = timelineTracks.map { if (it.id == groupTrackId) it.copy(clips = finalClips) else it }
    animationState = animationState.copy(
        currentFrameIndex = safeFrame,
        selectedTrackId = groupTrackId,
        selectedClipId = newClipId,
    )
    rebuildTimelineRenderState()
    recomposite()
    persistCurrentArtwork()
    showNotice("F${safeFrame + 1}: ${child.name}")
}

fun CanvasViewModel.createGroupTrack(targetTrackId: String? = animationState.selectedTrackId) {
    val selectedIds = animationState.selectedTrackIds
    if (selectedIds.isNotEmpty()) {
        val idsToGroup = if (targetTrackId != null) selectedIds + targetTrackId else selectedIds
        val candidates = timelineTracks.filter { it.id in idsToGroup && it.kind != TrackKind.GROUP }
        if (candidates.isNotEmpty()) {
            val groupId = UUID.randomUUID().toString()
            val groupCount = timelineTracks.count { it.kind == TrackKind.GROUP } + 1
            val groupTrack = TimelineTrack(
                id = groupId,
                name = "Group $groupCount",
                kind = TrackKind.GROUP,
                colorLabel = candidates.first().colorLabel,
                expanded = true,
            )
            val candidateIds = candidates.map { it.id }.toSet()
            val firstIdx = timelineTracks.indexOfFirst { it.id in candidateIds }.coerceAtLeast(0)
            val remaining = timelineTracks.filterNot { it.id in candidateIds }.toMutableList()
            val groupedChildren = candidates.map { it.copy(groupId = groupId) }
            val insertIdx = firstIdx.coerceIn(0, remaining.size)
            remaining.add(insertIdx, groupTrack)
            remaining.addAll(insertIdx + 1, groupedChildren)
            timelineTracks = remaining
            syncPaintLayersZOrder(remaining)
            animationState = animationState.copy(selectedTrackId = groupId, selectedTrackIds = emptySet())
            rebuildTimelineRenderState()
            recomposite()
            persistCurrentArtwork()
            showNotice("Grouped ${candidates.size} tracks")
            return
        }
    }

    val groupId = UUID.randomUUID().toString()
    val groupCount = timelineTracks.count { it.kind == TrackKind.GROUP } + 1
    val targetIdx = targetTrackId?.let { id -> timelineTracks.indexOfFirst { it.id == id } } ?: 0
    val targetTrack = timelineTracks.getOrNull(targetIdx)

    val groupTrack = TimelineTrack(
        id = groupId,
        name = "Group $groupCount",
        kind = TrackKind.GROUP,
        colorLabel = targetTrack?.colorLabel ?: TrackColorLabel.PURPLE,
        expanded = true,
    )

    val mutable = timelineTracks.toMutableList()
    if (targetTrack != null && targetTrack.kind != TrackKind.GROUP) {
        mutable[targetIdx] = targetTrack.copy(groupId = groupId)
        mutable.add(targetIdx, groupTrack)
    } else {
        mutable.add(0, groupTrack)
    }
    timelineTracks = mutable
    syncPaintLayersZOrder(mutable)
    animationState = animationState.copy(selectedTrackId = groupId, selectedTrackIds = emptySet())
    rebuildTimelineRenderState()
    recomposite()
    persistCurrentArtwork()
    showNotice("Created ${groupTrack.name}")
}

fun CanvasViewModel.assignTrackToGroup(trackId: String, groupId: String?) {
    val selectedIds = animationState.selectedTrackIds
    if (selectedIds.isNotEmpty() && (trackId in selectedIds || selectedIds.size >= 2)) {
        val idsToMove = (selectedIds + trackId)
        val candidates = timelineTracks.filter { it.id in idsToMove && it.kind != TrackKind.GROUP && it.id != groupId }
        if (candidates.isNotEmpty()) {
            val candidateIds = candidates.map { it.id }.toSet()
            val firstIdx = timelineTracks.indexOfFirst { it.id in candidateIds }.coerceAtLeast(0)
            val remaining = timelineTracks.filterNot { it.id in candidateIds }.toMutableList()
            val updatedChildren = candidates.map { it.copy(groupId = groupId) }
            val insertAt = if (groupId != null) {
                val gIdx = remaining.indexOfFirst { it.id == groupId }
                if (gIdx >= 0) gIdx + 1 else firstIdx.coerceAtMost(remaining.size)
            } else {
                firstIdx.coerceAtMost(remaining.size)
            }
            remaining.addAll(insertAt, updatedChildren)
            timelineTracks = remaining
            syncPaintLayersZOrder(remaining)
            animationState = animationState.copy(selectedTrackIds = emptySet())
            rebuildTimelineRenderState()
            recomposite()
            persistCurrentArtwork()
            showNotice(if (groupId != null) "Added ${candidates.size} tracks to group" else "Removed ${candidates.size} tracks from group")
            return
        }
    }

    val trIdx = timelineTracks.indexOfFirst { it.id == trackId }
    if (trIdx < 0) return
    val tr = timelineTracks[trIdx]
    if (tr.kind == TrackKind.GROUP || tr.id == groupId) return

    val updated = tr.copy(groupId = groupId)
    val mutable = timelineTracks.toMutableList()
    mutable.removeAt(trIdx)
    if (groupId != null) {
        val gIdx = mutable.indexOfFirst { it.id == groupId }
        if (gIdx >= 0) {
            mutable.add(gIdx + 1, updated)
        } else {
            mutable.add(trIdx.coerceAtMost(mutable.size), updated)
        }
    } else {
        mutable.add(trIdx.coerceAtMost(mutable.size), updated)
    }
    timelineTracks = mutable
    syncPaintLayersZOrder(mutable)
    rebuildTimelineRenderState()
    recomposite()
    persistCurrentArtwork()
}

fun CanvasViewModel.toggleGroupExpanded(groupId: String) {
    timelineTracks = timelineTracks.map {
        if (it.id == groupId && it.kind == TrackKind.GROUP) it.copy(expanded = !it.expanded) else it
    }
}

fun CanvasViewModel.splitSelectedOrCurrentClip(splitFrame: Int = animationState.currentFrameIndex) {
    val trackId = animationState.selectedTrackId ?: return
    val tr = timelineTracks.firstOrNull { it.id == trackId } ?: return
    val clip = animationState.selectedClipId?.let { cid -> tr.clips.firstOrNull { it.id == cid } }
        ?: tr.clipAt(splitFrame)
    if (clip == null) {
        showNotice("No clip under playhead")
        return
    }
    if (splitFrame <= clip.startFrame || splitFrame >= clip.endFrameExclusive) {
        showNotice("Move playhead inside the clip to split")
        return
    }
    val leftDur = splitFrame - clip.startFrame
    val rightDur = clip.endFrameExclusive - splitFrame
    val leftClip = clip.copy(durationFrames = leftDur)
    val rightBmp = clip.bitmap?.takeIf { !it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, true)
    val rightThumb = rightBmp?.let { LayerCompositor.makeThumbnail(it, 72).asImageBitmap() } ?: clip.thumbnail
    val rightClipId = UUID.randomUUID().toString()
    val rightClip = clip.copy(
        id = rightClipId,
        startFrame = splitFrame,
        durationFrames = rightDur,
        sourceFrameOffset = clip.sourceFrameOffset + leftDur,
        bitmap = rightBmp,
        thumbnail = rightThumb,
    )

    val nextClips = tr.clips.flatMap { if (it.id == clip.id) listOf(leftClip, rightClip) else listOf(it) }
    timelineTracks = timelineTracks.map { if (it.id == tr.id) it.copy(clips = nextClips) else it }
    animationState = animationState.copy(selectedClipId = rightClipId)
    rebuildTimelineRenderState()
    recomposite()
    persistCurrentArtwork()
    showNotice("Clip split at F${splitFrame + 1}")
}

fun CanvasViewModel.moveClip(
    fromTrackId: String,
    clipId: String,
    toTrackId: String,
    newStartFrame: Int,
) {
    val srcTrack = timelineTracks.firstOrNull { it.id == fromTrackId } ?: return
    val dstTrack = timelineTracks.firstOrNull { it.id == toTrackId } ?: return
    if (srcTrack.kind != dstTrack.kind) {
        showNotice("Incompatible track type")
        return
    }
    val clip = srcTrack.clips.firstOrNull { it.id == clipId } ?: return
    val maxStart = (animationState.totalFrames - 1).coerceAtLeast(0)
    val desiredStart = newStartFrame.coerceIn(0, maxStart)
    val snapping = animationState.timelineSnapping

    if (fromTrackId == toTrackId) {
        val others = srcTrack.clips.filter { it.id != clipId }
        val finalStart = if (snapping) {
            findNonOverlappingStart(desiredStart, clip.durationFrames, others)
        } else {
            desiredStart
        }
        val movedClip = clip.copy(startFrame = finalStart)
        if (movedClip.endFrameExclusive > animationState.totalFrames) {
            animationState = animationState.copy(totalFrames = movedClip.endFrameExclusive.coerceAtMost(1200))
        }
        val nonOverlap = if (snapping) {
            (others + movedClip).sortedBy { it.startFrame }
        } else {
            resolveClipPlacement(movedClip, others)
        }
        timelineTracks = timelineTracks.map {
            if (it.id == fromTrackId) it.copy(clips = nonOverlap) else it
        }
    } else {
        val srcRemaining = srcTrack.clips.filter { it.id != clipId }
        val dstOthers = dstTrack.clips
        val finalStart = if (snapping) {
            findNonOverlappingStart(desiredStart, clip.durationFrames, dstOthers)
        } else {
            desiredStart
        }
        val movedClip = clip.copy(startFrame = finalStart)
        if (movedClip.endFrameExclusive > animationState.totalFrames) {
            animationState = animationState.copy(totalFrames = movedClip.endFrameExclusive.coerceAtMost(1200))
        }
        val dstMerged = if (snapping) {
            (dstOthers + movedClip).sortedBy { it.startFrame }
        } else {
            resolveClipPlacement(movedClip, dstOthers)
        }
        timelineTracks = timelineTracks.map {
            when (it.id) {
                fromTrackId -> it.copy(clips = srcRemaining)
                toTrackId -> it.copy(clips = dstMerged)
                else -> it
            }
        }
        animationState = animationState.copy(selectedTrackId = toTrackId, selectedClipId = clipId)
        if (dstTrack.kind == TrackKind.PAINT && dstTrack.layerId != null) {
            val lIdx = layers.indexOfFirst { it.id == dstTrack.layerId }
            if (lIdx >= 0) setActiveLayerIndexDirect(lIdx)
        }
    }

    rebuildTimelineRenderState()
    recomposite()
    persistCurrentArtwork()
}

private fun findNonOverlappingStart(desiredStart: Int, duration: Int, others: List<TimelineClip>): Int {
    val dur = duration.coerceAtLeast(1)
    val sorted = others.sortedBy { it.startFrame }
    fun overlaps(s: Int): Boolean {
        val e = s + dur
        return sorted.any { s < it.endFrameExclusive && e > it.startFrame }
    }
    if (!overlaps(desiredStart)) return desiredStart.coerceAtLeast(0)

    val candidates = ArrayList<Int>(sorted.size * 2 + 2)
    candidates.add(0)
    for (o in sorted) {
        if (o.startFrame - dur >= 0) candidates.add(o.startFrame - dur)
        candidates.add(o.endFrameExclusive)
    }
    val maxEnd = sorted.maxOfOrNull { it.endFrameExclusive } ?: 0
    candidates.add(maxEnd)

    return candidates
        .distinct()
        .filter { it >= 0 && !overlaps(it) }
        .minByOrNull { abs(it - desiredStart) }
        ?: maxEnd
}

fun CanvasViewModel.resizeClipDuration(
    trackId: String,
    clipId: String,
    newStartFrame: Int,
    newDurationFrames: Int,
) {
    val tr = timelineTracks.firstOrNull { it.id == trackId } ?: return
    val clip = tr.clips.firstOrNull { it.id == clipId } ?: return
    val others = tr.clips.filter { it.id != clipId }.sortedBy { it.startFrame }
    val snapping = animationState.timelineSnapping

    var safeStart = newStartFrame.coerceIn(0, (animationState.totalFrames - 1).coerceAtLeast(0))
    var safeDur = newDurationFrames.coerceAtLeast(1)

    if (snapping) {
        val prevLimit = others.lastOrNull { it.endFrameExclusive <= clip.startFrame }?.endFrameExclusive ?: 0
        val nextLimit = others.firstOrNull { it.startFrame >= clip.endFrameExclusive }?.startFrame
        if (safeStart < prevLimit) {
            val origEnd = safeStart + safeDur
            safeStart = prevLimit
            safeDur = (origEnd - safeStart).coerceAtLeast(1)
        }
        if (nextLimit != null && safeStart + safeDur > nextLimit) {
            safeDur = (nextLimit - safeStart).coerceAtLeast(1)
        }
    }

    val startDelta = safeStart - clip.startFrame
    val updated = clip.copy(
        startFrame = safeStart,
        durationFrames = safeDur,
        sourceFrameOffset = (clip.sourceFrameOffset + startDelta).coerceAtLeast(0),
    )
    if (updated.endFrameExclusive > animationState.totalFrames) {
        animationState = animationState.copy(totalFrames = updated.endFrameExclusive.coerceAtMost(1200))
    }
    val resolved = if (snapping) {
        (others + updated).sortedBy { it.startFrame }
    } else {
        resolveClipPlacement(updated, others)
    }
    timelineTracks = timelineTracks.map { if (it.id == trackId) it.copy(clips = resolved) else it }
    rebuildTimelineRenderState()
    recomposite()
    persistCurrentArtwork()
}

private fun resolveClipPlacement(placed: TimelineClip, others: List<TimelineClip>): List<TimelineClip> {
    val adjusted = ArrayList<TimelineClip>(others.size + 1)
    for (other in others.sortedBy { it.startFrame }) {
        val overlap = placed.startFrame < other.endFrameExclusive && placed.endFrameExclusive > other.startFrame
        if (!overlap) {
            adjusted.add(other)
        } else {
            // Trim left portion if any, skip covered center, keep right tail if any
            if (other.startFrame < placed.startFrame) {
                adjusted.add(other.copy(durationFrames = placed.startFrame - other.startFrame))
            }
            if (other.endFrameExclusive > placed.endFrameExclusive) {
                val tailDelta = placed.endFrameExclusive - other.startFrame
                adjusted.add(
                    other.copy(
                        id = if (other.startFrame < placed.startFrame) UUID.randomUUID().toString() else other.id,
                        startFrame = placed.endFrameExclusive,
                        durationFrames = other.endFrameExclusive - placed.endFrameExclusive,
                        sourceFrameOffset = other.sourceFrameOffset + tailDelta,
                        bitmap = if (other.startFrame < placed.startFrame) other.bitmap?.copy(Bitmap.Config.ARGB_8888, true) else other.bitmap,
                    ),
                )
            }
        }
    }
    adjusted.add(placed)
    return adjusted.sortedBy { it.startFrame }
}

fun CanvasViewModel.deleteClip(trackId: String, clipId: String) {
    val tr = timelineTracks.firstOrNull { it.id == trackId } ?: return
    timelineTracks = timelineTracks.map {
        if (it.id == trackId) it.copy(clips = it.clips.filter { c -> c.id != clipId }) else it
    }
    if (animationState.selectedClipId == clipId) {
        animationState = animationState.copy(selectedClipId = null)
    }
    rebuildTimelineRenderState()
    recomposite()
    persistCurrentArtwork()
}

fun CanvasViewModel.importVideoTrack(uri: Uri) {
    val art = currentArtwork ?: return
    val app = getApplication<android.app.Application>()
    val fps = animationState.fps.coerceIn(1, 60)
    val startF = animationState.currentFrameIndex.coerceAtLeast(0)
    importingMedia = true

    viewModelScope.launch(Dispatchers.IO) {
        val videoRes = MediaTimelineEngine.importVideoFrames(app, uri, art.width, art.height, fps)
        val audioId = UUID.randomUUID().toString()
        val pcmOut = File(repoRef.artworkMediaDir(art.id), "audio_$audioId.pcm")
        val audioRes = MediaTimelineEngine.decodeAudioToPcmCache(app, uri, pcmOut, fallbackTitle = "${videoRes?.title ?: "Video"} Audio")

        withContext(Dispatchers.Main) {
            importingMedia = false
            if (videoRes == null || videoRes.frames.isEmpty()) {
                showNotice("Unable to decode video clip")
                return@withContext
            }
            ensureTimelineInitialized()
            val clipId = UUID.randomUUID().toString()
            val thumb = LayerCompositor.makeThumbnail(videoRes.frames.first(), 72).asImageBitmap()
            val vClip = TimelineClip(
                id = clipId,
                startFrame = startF,
                durationFrames = videoRes.frames.size,
                label = videoRes.title,
                thumbnail = thumb,
                videoFrames = videoRes.frames,
                mediaUri = uri.toString(),
            )
            val vColor = TRACK_LABEL_PALETTE[timelineTracks.size % TRACK_LABEL_PALETTE.size]
            val vTrack = TimelineTrack(
                id = UUID.randomUUID().toString(),
                name = videoRes.title.take(24),
                kind = TrackKind.VIDEO,
                colorLabel = vColor,
                clips = listOf(vClip),
            )
            val addedTracks = mutableListOf(vTrack)
            if (audioRes != null) {
                val audioFrames = max(1, ((audioRes.durationMs / 1000.0) * fps).roundToInt())
                val aClip = TimelineClip(
                    id = audioId,
                    startFrame = startF,
                    durationFrames = audioFrames,
                    label = "${videoRes.title} Audio",
                    mediaUri = uri.toString(),
                    audioFilePath = audioRes.pcmFile.absolutePath,
                    audioSampleRate = audioRes.sampleRate,
                    waveformPeaks = audioRes.peaks,
                )
                addedTracks.add(
                    TimelineTrack(
                        id = UUID.randomUUID().toString(),
                        name = "${videoRes.title.take(16)} Audio",
                        kind = TrackKind.AUDIO,
                        colorLabel = TrackColorLabel.GREEN,
                        clips = listOf(aClip),
                    ),
                )
            }
            val neededTotal = max(animationState.totalFrames, min(1200, startF + videoRes.frames.size))
            timelineTracks = addedTracks + timelineTracks
            animationState = animationState.copy(
                totalFrames = neededTotal,
                selectedTrackId = vTrack.id,
                selectedClipId = clipId,
            )
            rebuildTimelineRenderState()
            recomposite()
            persistCurrentArtwork()
            showNotice("Imported Video Track (${videoRes.frames.size} frames)")
        }
    }
}

fun CanvasViewModel.importAudioTrack(uri: Uri) {
    val art = currentArtwork ?: return
    val app = getApplication<android.app.Application>()
    val fps = animationState.fps.coerceIn(1, 60)
    val startF = animationState.currentFrameIndex.coerceAtLeast(0)
    importingMedia = true

    viewModelScope.launch(Dispatchers.IO) {
        val clipId = UUID.randomUUID().toString()
        val pcmOut = File(repoRef.artworkMediaDir(art.id), "audio_$clipId.pcm")
        val audioRes = MediaTimelineEngine.decodeAudioToPcmCache(app, uri, pcmOut, fallbackTitle = "Audio Track")

        withContext(Dispatchers.Main) {
            importingMedia = false
            if (audioRes == null) {
                showNotice("Unable to decode audio clip")
                return@withContext
            }
            ensureTimelineInitialized()
            val durFrames = max(1, ((audioRes.durationMs / 1000.0) * fps).roundToInt())
            val aClip = TimelineClip(
                id = clipId,
                startFrame = startF,
                durationFrames = durFrames,
                label = audioRes.title,
                mediaUri = uri.toString(),
                audioFilePath = audioRes.pcmFile.absolutePath,
                audioSampleRate = audioRes.sampleRate,
                waveformPeaks = audioRes.peaks,
            )
            val aTrack = TimelineTrack(
                id = UUID.randomUUID().toString(),
                name = audioRes.title.take(24),
                kind = TrackKind.AUDIO,
                colorLabel = TrackColorLabel.GREEN,
                clips = listOf(aClip),
            )
            val neededTotal = max(animationState.totalFrames, min(1200, startF + durFrames))
            timelineTracks = timelineTracks + aTrack
            animationState = animationState.copy(
                totalFrames = neededTotal,
                selectedTrackId = aTrack.id,
                selectedClipId = clipId,
            )
            persistCurrentArtwork()
            showNotice("Imported Audio Track (${durFrames}f)")
        }
    }
}

fun CanvasViewModel.importImageTrack(uri: Uri) {
    val art = currentArtwork ?: return
    val startF = animationState.currentFrameIndex.coerceAtLeast(0)
    importingMedia = true

    viewModelScope.launch(Dispatchers.IO) {
        val decoded = repoRef.decodeImageFromUri(uri, maxDim = max(art.width, art.height))
        if (decoded == null) {
            withContext(Dispatchers.Main) {
                importingMedia = false
                showNotice("Unable to decode image")
            }
            return@launch
        }
        val fitted = Bitmap.createBitmap(art.width, art.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(fitted)
        val scale = min(art.width.toFloat() / decoded.width, art.height.toFloat() / decoded.height)
        val dw = decoded.width * scale
        val dh = decoded.height * scale
        val left = (art.width - dw) * 0.5f
        val top = (art.height - dh) * 0.5f
        c.drawBitmap(decoded, null, RectF(left, top, left + dw, top + dh), Paint(Paint.FILTER_BITMAP_FLAG))
        decoded.recycle()
        val thumb = LayerCompositor.makeThumbnail(fitted, 72).asImageBitmap()

        withContext(Dispatchers.Main) {
            importingMedia = false
            ensureTimelineInitialized()
            val newLayerId = UUID.randomUUID().toString()
            val imgNum = layers.size + 1
            val newLayer = LayerState(
                id = newLayerId,
                name = "Image $imgNum",
                bitmap = fitted,
                thumbnail = thumb,
            )
            setLayersDirect(layers + newLayer)
            setActiveLayerIndexDirect(layers.lastIndex)

            val clipId = UUID.randomUUID().toString()
            val durFrames = (animationState.totalFrames - startF).coerceAtLeast(1)
            val clip = TimelineClip(
                id = clipId,
                startFrame = startF,
                durationFrames = durFrames,
                label = "Image",
                bitmap = fitted,
                thumbnail = thumb,
                mediaUri = uri.toString(),
            )
            val trackColor = TRACK_LABEL_PALETTE[timelineTracks.size % TRACK_LABEL_PALETTE.size]
            val newTrack = TimelineTrack(
                id = UUID.randomUUID().toString(),
                name = newLayer.name,
                kind = TrackKind.PAINT,
                colorLabel = trackColor,
                layerId = newLayerId,
                clips = listOf(clip),
            )
            timelineTracks = listOf(newTrack) + timelineTracks
            animationState = animationState.copy(
                selectedTrackId = newTrack.id,
                selectedClipId = clipId,
            )
            rebuildTimelineRenderState()
            recomposite()
            refreshLayerThumbnail(activeLayerIndex)
            persistCurrentArtwork()
            showNotice("Imported Image Track (${newTrack.name})")
        }
    }
}

