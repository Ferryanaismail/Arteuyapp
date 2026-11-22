package io.github.artcraftandroid.procraft.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import io.github.artcraftandroid.procraft.canvas.*
import io.github.artcraftandroid.procraft.model.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun AnimationTimelineDock(
    vm: CanvasViewModel,
    onImportVideo: () -> Unit,
    onImportAudio: () -> Unit,
    onImportImage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val anim = vm.animationState
    val tracks = vm.timelineTracks
    val density = LocalDensity.current

    var timelineHeightDp by remember { mutableStateOf(268.dp) }
    var settingsDialogOpen by remember { mutableStateOf(false) }
    var inspectorTrackId by remember { mutableStateOf<String?>(null) }
    var renameTrackId by remember { mutableStateOf<String?>(null) }
    var contextMenuTrackId by remember { mutableStateOf<String?>(null) }
    var trackDragState by remember { mutableStateOf(TrackDragUiState()) }

    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()

    val collapsedGroupIds = remember(tracks) {
        tracks.filter { it.kind == TrackKind.GROUP && !it.expanded }.map { it.id }.toSet()
    }
    val visibleTracks = remember(tracks, collapsedGroupIds) {
        tracks.filter { it.groupId == null || it.groupId !in collapsedGroupIds }
    }

    val rowHeight = 48.dp
    val rowPx = with(density) { rowHeight.toPx() }.coerceAtLeast(24f)

    val selectedTrack = tracks.firstOrNull { it.id == anim.selectedTrackId }
    val selectedClip = selectedTrack?.clips?.firstOrNull { it.id == anim.selectedClipId }
        ?: selectedTrack?.clipAt(anim.currentFrameIndex)

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val isCompactPortrait = maxWidth < 600.dp
        val headerWidth = if (isCompactPortrait) 124.dp else 152.dp
        val baseColDp = if (isCompactPortrait) 26.dp else 30.dp
        val colWidth = (baseColDp * anim.timelineZoom.coerceIn(0.4f, 3.0f))
        val colPx = with(density) { colWidth.toPx() }.coerceAtLeast(10f)

        val minH = if (isCompactPortrait) 224.dp else 198.dp
        val maxH = (maxHeight * 0.68f).coerceIn(290.dp, 540.dp)
        val clampedH = timelineHeightDp.coerceIn(minH, maxH)

        Column(
            Modifier
                .fillMaxWidth()
                .height(clampedH)
                .shadow(18.dp, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(Pc.PanelBg)
                .border(0.8.dp, Pc.Divider, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .testTag("animation_timeline_bar"),
        ) {
            // Flexible "^" Hold & Drag Height Resizer Bar
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .background(Pc.PanelHeader)
                    .pointerInput(minH, maxH) {
                        detectVerticalDragGestures { change, dragAmount ->
                            change.consume()
                            val deltaDp = with(density) { dragAmount.toDp() }
                            timelineHeightDp = (timelineHeightDp - deltaDp).coerceIn(minH, maxH)
                        }
                    }
                    .clickable {
                        timelineHeightDp = if (timelineHeightDp < (minH + maxH) * 0.5f) {
                            maxH.coerceAtMost(390.dp)
                        } else {
                            252.dp
                        }
                    }
                    .testTag("timeline_resize_handle"),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Pc.PanelSurface)
                        .border(0.6.dp, Pc.Divider, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = if (clampedH > (minH + maxH) * 0.55f) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowUp,
                        contentDescription = "Drag to resize timeline height",
                        tint = Pc.Accent,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        "Timeline",
                        color = Pc.TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // Top Transport, Keyframe, Audio Scrub & Track Action Toolbar (Adaptive Portrait vs Wide)
            val secText = String.format(Locale.US, "%.1fs", anim.totalFrames / anim.fps.coerceAtLeast(1).toFloat())
            if (isCompactPortrait) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Pc.PanelHeader)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Row 1: Playback + Settings + Scrub + Onion + Close pinned right
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        IconButton(
                            onClick = { vm.toggleAnimationPlayback() },
                            modifier = Modifier.size(32.dp).testTag("anim_play_button"),
                        ) {
                            Icon(
                                imageVector = if (anim.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (anim.playing) "Pause" else "Play",
                                tint = Pc.Accent,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        IconButton(
                            onClick = { vm.stepFrame(-1) },
                            modifier = Modifier.size(28.dp).testTag("anim_prev_frame"),
                        ) {
                            Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous Frame", tint = Pc.TextPrimary, modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = { vm.stepFrame(1) },
                            modifier = Modifier.size(28.dp).testTag("anim_next_frame"),
                        ) {
                            Icon(Icons.Rounded.SkipNext, contentDescription = "Next Frame", tint = Pc.TextPrimary, modifier = Modifier.size(18.dp))
                        }
                        TimelinePillButton(
                            icon = Icons.Rounded.Tune,
                            label = "F${anim.currentFrameIndex + 1}/${anim.totalFrames} • ${anim.fps}fps",
                            active = false,
                            compact = true,
                            testTag = "timeline_settings_button",
                            onClick = { settingsDialogOpen = true },
                        )
                        TimelinePillButton(
                            icon = if (anim.audioScrubEnabled) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                            label = if (anim.audioScrubEnabled) "Scrub" else "Mute",
                            active = anim.audioScrubEnabled,
                            activeColor = Pc.Success,
                            compact = true,
                            testTag = "audio_scrub_toggle",
                            onClick = { vm.toggleAudioScrubbing() },
                        )
                        TimelinePillButton(
                            icon = Icons.Rounded.Layers,
                            label = "Onion",
                            active = anim.onionSkinEnabled,
                            compact = true,
                            testTag = "onion_skin_toggle",
                            onClick = { vm.toggleOnionSkin() },
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = { vm.toggleAnimationAssist() },
                            modifier = Modifier.size(30.dp).testTag("close_animation_button"),
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close Timeline", tint = Pc.TextSecondary, modifier = Modifier.size(17.dp))
                        }
                    }

                    // Row 2: Keyframes + Tracks + Unified +Media upward menu
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TimelinePillButton(
                            icon = Icons.Rounded.AddBox,
                            label = "+ Key",
                            active = false,
                            compact = true,
                            testTag = "insert_keyframe_button",
                            onClick = { vm.insertBlankKeyframe() },
                        )
                        TimelinePillButton(
                            icon = Icons.Rounded.ContentCopy,
                            label = "Dup",
                            active = false,
                            compact = true,
                            testTag = "duplicate_keyframe_button",
                            onClick = { vm.insertDuplicateKeyframe() },
                        )
                        TimelinePillButton(
                            icon = Icons.Rounded.ContentCut,
                            label = "Split",
                            active = false,
                            compact = true,
                            testTag = "split_clip_button",
                            onClick = { vm.splitSelectedOrCurrentClip() },
                        )
                        TimelinePillButton(
                            icon = Icons.Rounded.Brush,
                            label = "+ Track",
                            active = false,
                            compact = true,
                            testTag = "add_paint_track_button",
                            onClick = { vm.addPaintTrack() },
                        )
                        TimelinePillButton(
                            icon = Icons.Rounded.CreateNewFolder,
                            label = "+ Group",
                            active = false,
                            compact = true,
                            testTag = "add_group_track_button",
                            onClick = { vm.createGroupTrack() },
                        )
                        AddMediaMenuButton(
                            compact = true,
                            onImportVideo = onImportVideo,
                            onImportAudio = onImportAudio,
                            onImportImage = onImportImage,
                        )
                    }
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Pc.PanelHeader)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    IconButton(
                        onClick = { vm.toggleAnimationPlayback() },
                        modifier = Modifier.size(38.dp).testTag("anim_play_button"),
                    ) {
                        Icon(
                            imageVector = if (anim.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (anim.playing) "Pause" else "Play",
                            tint = Pc.Accent,
                        )
                    }
                    IconButton(
                        onClick = { vm.stepFrame(-1) },
                        modifier = Modifier.size(34.dp).testTag("anim_prev_frame"),
                    ) {
                        Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous Frame", tint = Pc.TextPrimary, modifier = Modifier.size(20.dp))
                    }
                    IconButton(
                        onClick = { vm.stepFrame(1) },
                        modifier = Modifier.size(34.dp).testTag("anim_next_frame"),
                    ) {
                        Icon(Icons.Rounded.SkipNext, contentDescription = "Next Frame", tint = Pc.TextPrimary, modifier = Modifier.size(20.dp))
                    }

                    TimelinePillButton(
                        icon = Icons.Rounded.Tune,
                        label = "F${anim.currentFrameIndex + 1}/${anim.totalFrames} • ${anim.fps}fps ($secText)",
                        active = false,
                        testTag = "timeline_settings_button",
                        onClick = { settingsDialogOpen = true },
                    )
                    TimelinePillButton(
                        icon = if (anim.audioScrubEnabled) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                        label = if (anim.audioScrubEnabled) "Scrub ON" else "Scrub OFF",
                        active = anim.audioScrubEnabled,
                        activeColor = Pc.Success,
                        testTag = "audio_scrub_toggle",
                        onClick = { vm.toggleAudioScrubbing() },
                    )
                    TimelinePillButton(
                        icon = Icons.Rounded.AddBox,
                        label = "+ Key",
                        active = false,
                        testTag = "insert_keyframe_button",
                        onClick = { vm.insertBlankKeyframe() },
                    )
                    TimelinePillButton(
                        icon = Icons.Rounded.ContentCopy,
                        label = "Dup Key",
                        active = false,
                        testTag = "duplicate_keyframe_button",
                        onClick = { vm.insertDuplicateKeyframe() },
                    )
                    TimelinePillButton(
                        icon = Icons.Rounded.ContentCut,
                        label = "Split",
                        active = false,
                        testTag = "split_clip_button",
                        onClick = { vm.splitSelectedOrCurrentClip() },
                    )
                    TimelinePillButton(
                        icon = Icons.Rounded.Layers,
                        label = "Onion",
                        active = anim.onionSkinEnabled,
                        testTag = "onion_skin_toggle",
                        onClick = { vm.toggleOnionSkin() },
                    )
                    TimelinePillButton(
                        icon = Icons.Rounded.Brush,
                        label = "+ Track",
                        active = false,
                        testTag = "add_paint_track_button",
                        onClick = { vm.addPaintTrack() },
                    )
                    TimelinePillButton(
                        icon = Icons.Rounded.CreateNewFolder,
                        label = "+ Group",
                        active = false,
                        testTag = "add_group_track_button",
                        onClick = { vm.createGroupTrack() },
                    )
                    AddMediaMenuButton(
                        compact = false,
                        onImportVideo = onImportVideo,
                        onImportAudio = onImportAudio,
                        onImportImage = onImportImage,
                    )
                    IconButton(
                        onClick = { vm.toggleAnimationAssist() },
                        modifier = Modifier.size(36.dp).testTag("close_animation_button"),
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close Timeline", tint = Pc.TextSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            }

            if (vm.importingMedia) {
                LinearProgressIndicator(
                    color = Pc.Accent,
                    trackColor = Pc.PanelSurface,
                    modifier = Modifier.fillMaxWidth().height(2.5.dp),
                )
            }

            // Selected Clip Precision Edit Strip
            if (selectedTrack != null && selectedClip != null && selectedTrack.kind != TrackKind.GROUP) {
                SelectedClipActionBar(
                    vm = vm,
                    track = selectedTrack,
                    clip = selectedClip,
                    visibleTracks = visibleTracks,
                )
            }

            // Split Multi-Track Timeline Area
            Row(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                // Left Fixed Track Header Column
                TrackHeaderColumn(
                    vm = vm,
                    allTracks = tracks,
                    visibleTracks = visibleTracks,
                    selectedTrackId = anim.selectedTrackId,
                    zoomPct = (anim.timelineZoom * 100f).roundToInt(),
                    headerWidth = headerWidth,
                    rowHeight = rowHeight,
                    rowPx = rowPx,
                    vScroll = vScroll,
                    dragState = trackDragState,
                    contextMenuTrackId = contextMenuTrackId,
                    onContextMenuTrackChange = { contextMenuTrackId = it },
                    onDragStateChange = { trackDragState = it },
                    onRenameTrack = { renameTrackId = it },
                    onInspectTrack = { inspectorTrackId = it },
                )

                // Right Scrollable & Pinch-Zoomable Frame Grid + Clips
                TimelineGridPane(
                    vm = vm,
                    visibleTracks = visibleTracks,
                    totalFrames = anim.totalFrames,
                    currentFrame = anim.currentFrameIndex,
                    selectedTrackId = anim.selectedTrackId,
                    selectedClipId = anim.selectedClipId,
                    dragState = trackDragState,
                    colWidth = colWidth,
                    colPx = colPx,
                    rowHeight = rowHeight,
                    rowPx = rowPx,
                    hScroll = hScroll,
                    vScroll = vScroll,
                    onOpenTrackMenu = { contextMenuTrackId = it },
                )
            }
        }
    }

    if (settingsDialogOpen) {
        TimelineSettingsDialog(vm = vm, onDismiss = { settingsDialogOpen = false })
    }

    renameTrackId?.let { tid ->
        tracks.firstOrNull { it.id == tid }?.let { tr ->
            RenameTrackDialog(vm = vm, track = tr, onDismiss = { renameTrackId = null })
        }
    }

    inspectorTrackId?.let { tid ->
        tracks.firstOrNull { it.id == tid }?.let { tr ->
            TrackInspectorDialog(vm = vm, track = tr, onDismiss = { inspectorTrackId = null })
        }
    }
}

@Composable
private fun TimelinePillButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    activeColor: Color = Pc.Accent,
    compact: Boolean = false,
    testTag: String = "",
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .heightIn(min = if (compact) 30.dp else 34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) activeColor else Pc.PanelSurface)
            .border(0.6.dp, if (active) activeColor else Pc.Divider, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 6.dp else 8.dp, vertical = if (compact) 4.dp else 5.dp)
            .then(if (testTag.isNotEmpty()) Modifier.testTag(testTag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) Color.White else Pc.TextPrimary,
            modifier = Modifier.size(if (compact) 14.dp else 15.dp),
        )
        Text(
            label,
            color = if (active) Color.White else Pc.TextPrimary,
            fontSize = if (compact) 10.sp else 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

private class UpwardPopupPositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width)
            .coerceIn(8, (windowSize.width - popupContentSize.width - 8).coerceAtLeast(8))
        val y = (anchorBounds.top - popupContentSize.height - gapPx)
            .coerceIn(8, (windowSize.height - popupContentSize.height - 8).coerceAtLeast(8))
        return IntOffset(x, y)
    }
}

@Composable
private fun AddMediaMenuButton(
    compact: Boolean,
    onImportVideo: () -> Unit,
    onImportAudio: () -> Unit,
    onImportImage: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val gapPx = with(LocalDensity.current) { 6.dp.roundToPx() }
    val provider = remember(gapPx) { UpwardPopupPositionProvider(gapPx) }

    Box {
        Row(
            Modifier
                .heightIn(min = if (compact) 30.dp else 34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (menuOpen) Pc.Accent else Pc.PanelSurface)
                .border(0.6.dp, if (menuOpen) Pc.Accent else Pc.Divider, RoundedCornerShape(8.dp))
                .clickable { menuOpen = !menuOpen }
                .padding(horizontal = if (compact) 7.dp else 9.dp, vertical = if (compact) 4.dp else 5.dp)
                .testTag("timeline_add_media_button"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Icons.Rounded.PermMedia,
                contentDescription = "Add Media",
                tint = if (menuOpen) Color.White else Pc.Accent,
                modifier = Modifier.size(if (compact) 14.dp else 15.dp),
            )
            Text(
                "+ Media",
                color = if (menuOpen) Color.White else Pc.TextPrimary,
                fontSize = if (compact) 10.sp else 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Icon(
                Icons.Rounded.KeyboardArrowUp,
                contentDescription = null,
                tint = if (menuOpen) Color.White else Pc.TextSecondary,
                modifier = Modifier.size(14.dp),
            )
        }

        if (menuOpen) {
            Popup(
                popupPositionProvider = provider,
                onDismissRequest = { menuOpen = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier
                        .width(178.dp)
                        .shadow(14.dp, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .background(Pc.PanelCard)
                        .border(0.8.dp, Pc.Divider, RoundedCornerShape(12.dp))
                        .padding(vertical = 4.dp)
                        .testTag("add_media_upward_menu"),
                ) {
                    MediaMenuRow(
                        icon = Icons.Rounded.VideoCall,
                        label = "Import Video",
                        tint = Pc.Accent,
                        testTag = "media_menu_video",
                    ) {
                        menuOpen = false
                        onImportVideo()
                    }
                    MediaMenuRow(
                        icon = Icons.Rounded.AudioFile,
                        label = "Import Audio",
                        tint = Pc.Success,
                        testTag = "media_menu_audio",
                    ) {
                        menuOpen = false
                        onImportAudio()
                    }
                    MediaMenuRow(
                        icon = Icons.Rounded.Image,
                        label = "Import Image",
                        tint = Pc.Warning,
                        testTag = "media_menu_image",
                    ) {
                        menuOpen = false
                        onImportImage()
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaMenuRow(
    icon: ImageVector,
    label: String,
    tint: Color,
    testTag: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(18.dp))
        Text(label, color = Pc.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SelectedClipActionBar(
    vm: CanvasViewModel,
    track: TimelineTrack,
    clip: TimelineClip,
    visibleTracks: List<TimelineTrack>,
) {
    val compatible = visibleTracks.filter { it.kind == track.kind }
    val compatIdx = compatible.indexOfFirst { it.id == track.id }
    val trackColor = Color(track.colorLabel.argb)

    Row(
        Modifier
            .fillMaxWidth()
            .background(Pc.PanelSurface.copy(alpha = 0.7f))
            .border(0.5.dp, Pc.Divider)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(trackColor))
        Text(
            "${track.name}: F${clip.startFrame + 1}..${clip.endFrameExclusive} (${clip.durationFrames}f)",
            color = Pc.TextPrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
        MiniChip("< 1f") {
            vm.moveClip(track.id, clip.id, track.id, (clip.startFrame - 1).coerceAtLeast(0))
        }
        MiniChip("1f >") {
            vm.moveClip(track.id, clip.id, track.id, clip.startFrame + 1)
        }
        MiniChip("-Dur") {
            if (clip.durationFrames > 1) {
                vm.resizeClipDuration(track.id, clip.id, clip.startFrame, clip.durationFrames - 1)
            }
        }
        MiniChip("+Dur") {
            vm.resizeClipDuration(track.id, clip.id, clip.startFrame, clip.durationFrames + 1)
        }
        if (compatIdx > 0) {
            MiniChip("▲ Track") {
                vm.moveClip(track.id, clip.id, compatible[compatIdx - 1].id, clip.startFrame)
            }
        }
        if (compatIdx in 0 until compatible.lastIndex) {
            MiniChip("▼ Track") {
                vm.moveClip(track.id, clip.id, compatible[compatIdx + 1].id, clip.startFrame)
            }
        }
        MiniChip("Delete", tint = Pc.Destructive) {
            vm.deleteClip(track.id, clip.id)
        }
    }
}

@Composable
private fun MiniChip(label: String, tint: Color = Pc.TextPrimary, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Pc.PanelCard)
            .border(0.6.dp, Pc.Divider, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RowScope.TimelineGridPane(
    vm: CanvasViewModel,
    visibleTracks: List<TimelineTrack>,
    totalFrames: Int,
    currentFrame: Int,
    selectedTrackId: String?,
    selectedClipId: String?,
    dragState: TrackDragUiState,
    colWidth: Dp,
    colPx: Float,
    rowHeight: Dp,
    rowPx: Float,
    hScroll: ScrollState,
    vScroll: ScrollState,
    onOpenTrackMenu: (String) -> Unit,
) {
    val totalGridWidth = colWidth * totalFrames.coerceAtLeast(1)
    val accent = Pc.Accent
    val divider = Pc.Divider
    val gridAlt = Pc.PanelSurface.copy(alpha = 0.28f)

    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clipToBounds()
            .pointerInput(Unit) {
                // 2-finger pinch zoom gesture (leaves 1-finger drag & scroll free)
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var prevDist = 0f
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            val d = (pressed[0].position - pressed[1].position).getDistance()
                            if (prevDist > 1f && d > 1f) {
                                val nextZoom = (vm.animationState.timelineZoom * (d / prevDist)).coerceIn(0.4f, 3.0f)
                                vm.animationState = vm.animationState.copy(timelineZoom = nextZoom)
                            }
                            prevDist = d
                            pressed.forEach { it.consume() }
                        } else {
                            prevDist = 0f
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .horizontalScroll(hScroll),
        ) {
            // Interactive Time Ruler (Scrubbable Playhead)
            Box(
                Modifier
                    .width(totalGridWidth)
                    .height(26.dp)
                    .background(Pc.PanelHeader)
                    .pointerInput(colPx, totalFrames) {
                        detectTapGestures { pos ->
                            val f = (pos.x / colPx).toInt().coerceIn(0, (totalFrames - 1).coerceAtLeast(0))
                            vm.scrubToFrame(f, fromUserDrag = true)
                        }
                    }
                    .pointerInput(colPx, totalFrames) {
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            val f = (change.position.x / colPx).toInt().coerceIn(0, (totalFrames - 1).coerceAtLeast(0))
                            vm.scrubToFrame(f, fromUserDrag = true)
                        }
                    }
                    .testTag("timeline_ruler"),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    for (f in 0 until totalFrames) {
                        val x = f * colPx
                        val major = f % 5 == 0
                        drawLine(
                            color = if (major) divider.copy(alpha = 0.7f) else divider,
                            start = Offset(x, if (major) 6f else 14f),
                            end = Offset(x, size.height),
                            strokeWidth = if (major) 1.5f else 1f,
                        )
                    }
                    // Highlight active frame column pill & playhead chevron
                    val px = currentFrame * colPx + colPx * 0.5f
                    drawRect(
                        color = accent.copy(alpha = 0.28f),
                        topLeft = Offset(currentFrame * colPx, 0f),
                        size = Size(colPx, size.height),
                    )
                    val head = Path().apply {
                        moveTo(px - 7.dp.toPx(), 2.dp.toPx())
                        lineTo(px + 7.dp.toPx(), 2.dp.toPx())
                        lineTo(px, size.height - 2.dp.toPx())
                        close()
                    }
                    drawPath(head, accent)
                }

                val labelStep = when {
                    colPx < 18f -> 5
                    colPx < 28f -> 2
                    else -> 1
                }
                for (f in 0 until totalFrames step labelStep) {
                    Box(
                        Modifier
                            .offset(x = colWidth * f, y = 2.dp)
                            .width(colWidth)
                            .height(22.dp),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Text(
                            "${f + 1}",
                            color = if (f == currentFrame) Color.White else Pc.TextSecondary,
                            fontSize = 9.sp,
                            fontWeight = if (f == currentFrame) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }

            // Tracks & Clips Lanes
            Box(
                Modifier
                    .width(totalGridWidth)
                    .weight(1f)
                    .verticalScroll(vScroll),
            ) {
                // Background column grid
                val gridHeight = rowHeight * visibleTracks.size.coerceAtLeast(1)
                Canvas(
                    Modifier
                        .width(totalGridWidth)
                        .height(gridHeight),
                ) {
                    for (f in 0 until totalFrames) {
                        val x = f * colPx
                        if (f % 2 == 1) {
                            drawRect(gridAlt, topLeft = Offset(x, 0f), size = Size(colPx, size.height))
                        }
                        drawLine(
                            color = if (f % 5 == 0) divider.copy(alpha = 0.65f) else divider,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1f,
                        )
                    }
                }

                Column(Modifier.width(totalGridWidth)) {
                    visibleTracks.forEachIndexed { visIdx, tr ->
                        val isTrackSelected = tr.id == selectedTrackId
                        val isGroupDropTarget = dragState.dropIntoGroupId == tr.id
                        val isBeingDragged = dragState.draggingTrackId == tr.id
                        val labelColor = Color(tr.colorLabel.argb)

                        Box(
                            Modifier
                                .width(totalGridWidth)
                                .height(rowHeight)
                                .background(
                                    when {
                                        isGroupDropTarget -> accent.copy(alpha = 0.22f)
                                        isBeingDragged -> accent.copy(alpha = 0.14f)
                                        isTrackSelected -> accent.copy(alpha = 0.08f)
                                        else -> Color.Transparent
                                    },
                                )
                                .border(
                                    width = if (isGroupDropTarget) 1.dp else 0.4.dp,
                                    color = if (isGroupDropTarget) accent else divider,
                                )
                                .pointerInput(tr.id, colPx, totalFrames) {
                                    detectTapGestures(
                                        onTap = { pos ->
                                            vm.selectTrack(tr.id)
                                            if (tr.kind == TrackKind.GROUP) {
                                                vm.toggleGroupExpanded(tr.id)
                                            } else {
                                                val f = (pos.x / colPx).toInt().coerceIn(0, (totalFrames - 1).coerceAtLeast(0))
                                                vm.scrubToFrame(f, fromUserDrag = false)
                                            }
                                        },
                                        onLongPress = {
                                            vm.selectTrack(tr.id)
                                            onOpenTrackMenu(tr.id)
                                        },
                                    )
                                },
                        ) {
                            if (tr.kind == TrackKind.GROUP) {
                                Row(
                                    Modifier
                                        .fillMaxSize()
                                        .background(labelColor.copy(alpha = 0.14f))
                                        .padding(horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        "${tr.name} (${vm.timelineTracks.count { it.groupId == tr.id }} tracks)",
                                        color = labelColor,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            } else {
                                tr.clips.forEach { clip ->
                                    TimelineClipBlock(
                                        vm = vm,
                                        track = tr,
                                        trackVisIndex = visIdx,
                                        visibleTracks = visibleTracks,
                                        clip = clip,
                                        isSelected = tr.id == selectedTrackId && clip.id == selectedClipId,
                                        colWidth = colWidth,
                                        colPx = colPx,
                                        rowHeight = rowHeight,
                                        rowPx = rowPx,
                                    )
                                }
                            }
                        }
                    }
                }

                // Horizontal Drop Insertion Line across Grid Lanes while Dragging Track
                val dropSlot = dragState.dropSlotVisibleIdx
                if (dragState.draggingTrackId != null && dropSlot != null) {
                    val clampedSlot = dropSlot.coerceIn(0, visibleTracks.size)
                    Box(
                        Modifier
                            .offset(y = ((rowHeight * clampedSlot) - 1.dp).coerceAtLeast(0.dp))
                            .width(totalGridWidth)
                            .height(2.dp)
                            .background(accent.copy(alpha = 0.85f))
                            .zIndex(25f),
                    )
                }

                // Vertical Playhead Cursor Line across all tracks
                Box(
                    Modifier
                        .offset(x = colWidth * currentFrame + colWidth * 0.5f - 1.dp)
                        .width(2.dp)
                        .height(gridHeight)
                        .background(accent.copy(alpha = 0.9f))
                        .zIndex(20f),
                )
            }
        }
    }
}

@Composable
private fun TimelineClipBlock(
    vm: CanvasViewModel,
    track: TimelineTrack,
    trackVisIndex: Int,
    visibleTracks: List<TimelineTrack>,
    clip: TimelineClip,
    isSelected: Boolean,
    colWidth: Dp,
    colPx: Float,
    rowHeight: Dp,
    rowPx: Float,
) {
    val labelColor = Color(track.colorLabel.argb)
    var dragX by remember(clip.id, clip.startFrame, track.id) { mutableFloatStateOf(0f) }
    var dragY by remember(clip.id, clip.startFrame, track.id) { mutableFloatStateOf(0f) }
    var dragging by remember(clip.id) { mutableStateOf(false) }

    val clipWidth = (colWidth * clip.durationFrames.coerceAtLeast(1)).coerceAtLeast(18.dp)
    val leftOffset = colWidth * clip.startFrame

    Box(
        Modifier
            .offset(x = leftOffset, y = 4.dp)
            .width(clipWidth)
            .height(rowHeight - 8.dp)
            .zIndex(if (dragging) 15f else if (isSelected) 5f else 1f)
            .graphicsLayer {
                translationX = dragX
                translationY = dragY
                alpha = if (dragging) 0.86f else 1f
            }
            .clip(RoundedCornerShape(7.dp))
            .background(labelColor.copy(alpha = if (isSelected) 0.36f else 0.22f))
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) Color.White else labelColor,
                shape = RoundedCornerShape(7.dp),
            )
            .clickable {
                vm.selectClip(track.id, clip.id)
                vm.scrubToFrame(clip.startFrame, fromUserDrag = false)
            }
            .pointerInput(clip.id, track.id, colPx, rowPx, visibleTracks) {
                detectDragGestures(
                    onDragStart = {
                        dragging = true
                        vm.selectClip(track.id, clip.id)
                    },
                    onDragEnd = {
                        val frameDelta = (dragX / colPx).roundToInt()
                        val trackDelta = (dragY / rowPx).roundToInt()
                        val desiredIdx = (trackVisIndex + trackDelta).coerceIn(0, visibleTracks.lastIndex)
                        val targetTrack = if (trackDelta == 0) {
                            track
                        } else {
                            visibleTracks.getOrNull(desiredIdx)?.takeIf { it.kind == track.kind }
                                ?: visibleTracks
                                    .filter { it.kind == track.kind }
                                    .minByOrNull { abs(visibleTracks.indexOf(it) - desiredIdx) }
                                ?: track
                        }
                        val nextStart = (clip.startFrame + frameDelta).coerceAtLeast(0)
                        dragX = 0f
                        dragY = 0f
                        dragging = false
                        if (frameDelta != 0 || targetTrack.id != track.id) {
                            vm.moveClip(track.id, clip.id, targetTrack.id, nextStart)
                        }
                    },
                    onDragCancel = {
                        dragX = 0f
                        dragY = 0f
                        dragging = false
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragX += dragAmount.x
                        dragY += dragAmount.y
                    },
                )
            },
    ) {
        when (track.kind) {
            TrackKind.AUDIO -> {
                AudioWaveformClipContent(clip = clip, color = labelColor)
            }
            TrackKind.VIDEO, TrackKind.PAINT -> {
                VisualClipContent(track = track, clip = clip, labelColor = labelColor)
            }
            TrackKind.GROUP -> Unit
        }

        if (isSelected) {
            // Left trim edge handle
            var trimLeftAcc by remember(clip.id) { mutableFloatStateOf(0f) }
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(9.dp)
                    .fillMaxHeight()
                    .background(Color.White.copy(alpha = 0.85f))
                    .pointerInput(clip.id, colPx) {
                        detectHorizontalDragGestures(
                            onDragStart = { trimLeftAcc = 0f },
                            onDragEnd = {
                                val delta = (trimLeftAcc / colPx).roundToInt()
                                if (delta != 0) {
                                    val newStart = (clip.startFrame + delta).coerceIn(0, clip.endFrameExclusive - 1)
                                    val newDur = (clip.endFrameExclusive - newStart).coerceAtLeast(1)
                                    vm.resizeClipDuration(track.id, clip.id, newStart, newDur)
                                }
                                trimLeftAcc = 0f
                            },
                            onHorizontalDrag = { c, dx ->
                                c.consume()
                                trimLeftAcc += dx
                            },
                        )
                    },
            )
            // Right trim edge handle
            var trimRightAcc by remember(clip.id) { mutableFloatStateOf(0f) }
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(9.dp)
                    .fillMaxHeight()
                    .background(Color.White.copy(alpha = 0.85f))
                    .pointerInput(clip.id, colPx) {
                        detectHorizontalDragGestures(
                            onDragStart = { trimRightAcc = 0f },
                            onDragEnd = {
                                val delta = (trimRightAcc / colPx).roundToInt()
                                if (delta != 0) {
                                    val newDur = (clip.durationFrames + delta).coerceAtLeast(1)
                                    vm.resizeClipDuration(track.id, clip.id, clip.startFrame, newDur)
                                }
                                trimRightAcc = 0f
                            },
                            onHorizontalDrag = { c, dx ->
                                c.consume()
                                trimRightAcc += dx
                            },
                        )
                    },
            )
        }
    }
}

@Composable
private fun VisualClipContent(
    track: TimelineTrack,
    clip: TimelineClip,
    labelColor: Color,
) {
    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (track.kind == TrackKind.PAINT) {
            Box(
                Modifier
                    .size(8.dp)
                    .graphicsLayer { rotationZ = 45f }
                    .background(labelColor),
            )
        }
        clip.thumbnail?.let { bmp ->
            Image(
                bitmap = bmp,
                contentDescription = clip.label.ifEmpty { "Keyframe" },
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White),
            )
        }
        Text(
            text = clip.label.ifEmpty { "Key" },
            color = Pc.TextPrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AudioWaveformClipContent(
    clip: TimelineClip,
    color: Color,
) {
    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            val peaks = clip.waveformPeaks
            if (peaks.isEmpty()) return@Canvas
            val barCount = min(peaks.size, max(8, (size.width / 3.dp.toPx()).toInt()))
            val stepW = size.width / barCount
            val midY = size.height * 0.5f
            for (i in 0 until barCount) {
                val sampleIdx = (i * peaks.size / barCount).coerceIn(0, peaks.lastIndex)
                val amp = max(0.08f, peaks[sampleIdx].coerceIn(0f, 1f))
                val halfH = amp * (size.height * 0.46f)
                drawLine(
                    color = color,
                    start = Offset(i * stepW + stepW * 0.5f, midY - halfH),
                    end = Offset(i * stepW + stepW * 0.5f, midY + halfH),
                    strokeWidth = max(1.5f, stepW * 0.55f),
                )
            }
        }
        Text(
            text = clip.label.ifEmpty { "Audio" },
            color = Pc.TextPrimary,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}
