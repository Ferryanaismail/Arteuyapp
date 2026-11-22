package io.github.artcraftandroid.procraft.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.artcraftandroid.procraft.canvas.*
import io.github.artcraftandroid.procraft.model.*
import kotlin.math.abs

@Immutable
data class TrackDragUiState(
    val draggingTrackId: String? = null,
    val dragOffsetY: Float = 0f,
    val dropSlotVisibleIdx: Int? = null,
    val dropIntoGroupId: String? = null,
)

@Composable
fun TrackHeaderColumn(
    vm: CanvasViewModel,
    allTracks: List<TimelineTrack>,
    visibleTracks: List<TimelineTrack>,
    selectedTrackId: String?,
    zoomPct: Int,
    headerWidth: Dp = 148.dp,
    rowHeight: Dp,
    rowPx: Float,
    vScroll: ScrollState,
    dragState: TrackDragUiState,
    contextMenuTrackId: String?,
    onContextMenuTrackChange: (String?) -> Unit,
    onDragStateChange: (TrackDragUiState) -> Unit,
    onRenameTrack: (String) -> Unit,
    onInspectTrack: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val compactHeader = headerWidth < 136.dp

    val latestVisibleTracks by rememberUpdatedState(visibleTracks)
    val latestRowPx by rememberUpdatedState(rowPx)
    val latestDragState by rememberUpdatedState(dragState)

    val existingGroups = remember(allTracks) {
        allTracks.filter { it.kind == TrackKind.GROUP }
    }

    Column(
        Modifier
            .width(headerWidth)
            .fillMaxHeight()
            .background(Pc.PanelSurface)
            .border(0.6.dp, Pc.Divider),
    ) {
        // Ruler corner header
        Row(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .background(Pc.PanelHeader)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "TRACKS (${visibleTracks.size})",
                color = Pc.TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "${zoomPct}%",
                color = Pc.Accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(vScroll),
        ) {
            Column(Modifier.fillMaxWidth()) {
                visibleTracks.forEach { tr ->
                    val isSelected = tr.id == selectedTrackId
                    val isDragging = dragState.draggingTrackId == tr.id
                    val isGroupDropTarget = dragState.dropIntoGroupId == tr.id
                    val labelColor = Color(tr.colorLabel.argb)
                    val isGroupedChild = tr.groupId != null

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(rowHeight)
                            .zIndex(if (isDragging) 30f else if (isGroupDropTarget) 10f else 1f)
                            .graphicsLayer {
                                if (isDragging) {
                                    translationY = dragState.dragOffsetY
                                    scaleX = 1.02f
                                    scaleY = 1.02f
                                    alpha = 0.94f
                                }
                            },
                    ) {
                        Row(
                            Modifier
                                .fillMaxSize()
                                .then(
                                    if (isDragging) {
                                        Modifier
                                            .shadow(10.dp, RoundedCornerShape(8.dp))
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Pc.PanelCard)
                                            .border(1.5.dp, Pc.Accent, RoundedCornerShape(8.dp))
                                    } else if (isGroupDropTarget) {
                                        Modifier
                                            .background(Pc.Accent.copy(alpha = 0.24f))
                                            .border(1.5.dp, Pc.Accent)
                                    } else {
                                        Modifier
                                            .background(if (isSelected) Pc.RowSelected else Color.Transparent)
                                            .border(0.4.dp, Pc.Divider)
                                    },
                                )
                                .pointerInput(tr.id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            vm.selectTrack(tr.id)
                                            onDragStateChange(
                                                TrackDragUiState(
                                                    draggingTrackId = tr.id,
                                                    dragOffsetY = 0f,
                                                    dropSlotVisibleIdx = null,
                                                    dropIntoGroupId = null,
                                                ),
                                            )
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            val curTracks = latestVisibleTracks
                                            val curRowPx = latestRowPx.coerceAtLeast(1f)
                                            val srcIdx = curTracks.indexOfFirst { it.id == tr.id }
                                            if (srcIdx < 0 || curTracks.isEmpty()) return@detectDragGesturesAfterLongPress

                                            val nextOffsetY = latestDragState.dragOffsetY + dragAmount.y
                                            val centerY = srcIdx * curRowPx + curRowPx * 0.5f + nextOffsetY
                                            val hoveredIdx = (centerY / curRowPx).toInt().coerceIn(0, curTracks.lastIndex)
                                            val frac = ((centerY - hoveredIdx * curRowPx) / curRowPx).coerceIn(0f, 1f)
                                            val hoveredTrack = curTracks[hoveredIdx]

                                            val canDropIntoGroup = tr.kind != TrackKind.GROUP &&
                                                hoveredTrack.kind == TrackKind.GROUP &&
                                                hoveredTrack.id != tr.id &&
                                                frac in 0.2f..0.8f

                                            onDragStateChange(
                                                TrackDragUiState(
                                                    draggingTrackId = tr.id,
                                                    dragOffsetY = nextOffsetY,
                                                    dropSlotVisibleIdx = if (canDropIntoGroup) null else {
                                                        if (frac < 0.5f) hoveredIdx else hoveredIdx + 1
                                                    },
                                                    dropIntoGroupId = if (canDropIntoGroup) hoveredTrack.id else null,
                                                ),
                                            )
                                        },
                                        onDragEnd = {
                                            val curDrag = latestDragState
                                            val curTracks = latestVisibleTracks
                                            val srcIdx = curTracks.indexOfFirst { it.id == tr.id }
                                            onDragStateChange(TrackDragUiState())

                                            if (abs(curDrag.dragOffsetY) <= 10f && curDrag.dropIntoGroupId == null) {
                                                onContextMenuTrackChange(tr.id)
                                            } else if (srcIdx >= 0) {
                                                vm.reorderOrGroupTrack(
                                                    draggedTrackId = tr.id,
                                                    visibleTracksSnapshot = curTracks,
                                                    targetSlotVisibleIdx = curDrag.dropSlotVisibleIdx ?: srcIdx,
                                                    dropIntoGroupId = curDrag.dropIntoGroupId,
                                                )
                                            }
                                        },
                                        onDragCancel = {
                                            onDragStateChange(TrackDragUiState())
                                        },
                                    )
                                }
                                .clickable {
                                    vm.selectTrack(tr.id)
                                    onContextMenuTrackChange(tr.id)
                                }
                                .padding(end = 2.dp)
                                .testTag("track_header_${tr.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Color Label Bar (indented accent strip for grouped child tracks)
                            Box(
                                Modifier
                                    .width(if (isGroupedChild) 9.dp else 4.dp)
                                    .fillMaxHeight()
                                    .background(if (isGroupedChild) labelColor.copy(alpha = 0.62f) else labelColor),
                            )
                            Spacer(Modifier.width(if (compactHeader) 3.dp else 4.dp))

                            if (tr.kind == TrackKind.GROUP) {
                                IconButton(
                                    onClick = { vm.toggleGroupExpanded(tr.id) },
                                    modifier = Modifier.size(if (compactHeader) 24.dp else 28.dp),
                                ) {
                                    Icon(
                                        imageVector = if (tr.expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
                                        contentDescription = if (tr.expanded) "Collapse Group" else "Expand Group",
                                        tint = labelColor,
                                        modifier = Modifier.size(17.dp),
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = when (tr.kind) {
                                        TrackKind.PAINT -> Icons.Rounded.Brush
                                        TrackKind.VIDEO -> Icons.Rounded.Movie
                                        TrackKind.AUDIO -> Icons.Rounded.GraphicEq
                                        TrackKind.GROUP -> Icons.Rounded.Folder
                                    },
                                    contentDescription = tr.kind.label,
                                    tint = labelColor,
                                    modifier = Modifier.size(if (compactHeader) 12.dp else 14.dp),
                                )
                                Spacer(Modifier.width(3.dp))
                            }

                            Column(Modifier.weight(1f)) {
                                Text(
                                    tr.name,
                                    color = if (tr.visible && !tr.muted) Pc.TextPrimary else Pc.TextSecondary,
                                    fontSize = if (compactHeader) 10.sp else 11.sp,
                                    fontWeight = if (isSelected || tr.kind == TrackKind.GROUP) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (isGroupDropTarget) {
                                    Text(
                                        "+ Group",
                                        color = Pc.Accent,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                } else if (tr.locked) {
                                    Text("Locked", color = Pc.Warning, fontSize = 8.sp)
                                }
                            }

                            IconButton(
                                onClick = {
                                    if (tr.kind == TrackKind.AUDIO) vm.toggleTrackMute(tr.id)
                                    else vm.toggleTrackVisibility(tr.id)
                                },
                                modifier = Modifier.size(if (compactHeader) 24.dp else 28.dp),
                            ) {
                                val active = if (tr.kind == TrackKind.AUDIO) !tr.muted else tr.visible
                                Icon(
                                    imageVector = if (tr.kind == TrackKind.AUDIO) {
                                        if (active) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff
                                    } else {
                                        if (active) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff
                                    },
                                    contentDescription = "Toggle Track Visibility/Mute",
                                    tint = if (active) Pc.TextPrimary else Pc.TextSecondary.copy(alpha = 0.5f),
                                    modifier = Modifier.size(14.dp),
                                )
                            }

                            Box {
                                IconButton(
                                    onClick = { onContextMenuTrackChange(tr.id) },
                                    modifier = Modifier
                                        .size(if (compactHeader) 24.dp else 28.dp)
                                        .testTag("track_menu_btn_${tr.id}"),
                                ) {
                                    Icon(
                                        Icons.Rounded.MoreVert,
                                        contentDescription = "Track Options",
                                        tint = Pc.TextSecondary,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }

                                TrackContextMenu(
                                    expanded = contextMenuTrackId == tr.id,
                                    track = tr,
                                    existingGroups = existingGroups,
                                    canDelete = !(tr.kind == TrackKind.PAINT && allTracks.count { it.kind == TrackKind.PAINT } <= 1),
                                    onDismiss = { onContextMenuTrackChange(null) },
                                    onRename = {
                                        onContextMenuTrackChange(null)
                                        onRenameTrack(tr.id)
                                    },
                                    onColorSelect = { lbl ->
                                        vm.setTrackColorLabel(tr.id, lbl)
                                    },
                                    onCreateGroup = {
                                        onContextMenuTrackChange(null)
                                        vm.createGroupTrack(tr.id)
                                    },
                                    onAssignGroup = { gid ->
                                        onContextMenuTrackChange(null)
                                        vm.assignTrackToGroup(tr.id, gid)
                                    },
                                    onToggleGroupExpanded = {
                                        onContextMenuTrackChange(null)
                                        vm.toggleGroupExpanded(tr.id)
                                    },
                                    onMoveOrder = { delta ->
                                        onContextMenuTrackChange(null)
                                        vm.moveTrackOrder(tr.id, delta)
                                    },
                                    onToggleVisibilityOrMute = {
                                        onContextMenuTrackChange(null)
                                        if (tr.kind == TrackKind.AUDIO) vm.toggleTrackMute(tr.id)
                                        else vm.toggleTrackVisibility(tr.id)
                                    },
                                    onToggleLock = {
                                        onContextMenuTrackChange(null)
                                        vm.toggleTrackLock(tr.id)
                                    },
                                    onInspect = {
                                        onContextMenuTrackChange(null)
                                        onInspectTrack(tr.id)
                                    },
                                    onDelete = {
                                        onContextMenuTrackChange(null)
                                        vm.deleteTrack(tr.id)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // Drop Insertion Line across Track Header Column
            val dropSlot = dragState.dropSlotVisibleIdx
            if (dragState.draggingTrackId != null && dropSlot != null) {
                val clampedSlot = dropSlot.coerceIn(0, visibleTracks.size)
                val indicatorY = (rowHeight * clampedSlot) - 2.dp
                Box(
                    Modifier
                        .offset(y = indicatorY.coerceAtLeast(0.dp))
                        .fillMaxWidth()
                        .height(4.dp)
                        .zIndex(40f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.5.dp)
                            .background(Pc.Accent),
                    )
                    Box(
                        Modifier
                            .padding(start = 2.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Pc.Accent)
                            .border(1.dp, Color.White, CircleShape),
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackContextMenu(
    expanded: Boolean,
    track: TimelineTrack,
    existingGroups: List<TimelineTrack>,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onColorSelect: (TrackColorLabel) -> Unit,
    onCreateGroup: () -> Unit,
    onAssignGroup: (String?) -> Unit,
    onToggleGroupExpanded: () -> Unit,
    onMoveOrder: (Int) -> Unit,
    onToggleVisibilityOrMute: () -> Unit,
    onToggleLock: () -> Unit,
    onInspect: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = Pc.PanelCard,
        modifier = Modifier
            .widthIn(min = 216.dp, max = 248.dp)
            .testTag("track_context_menu_${track.id}"),
    ) {
        // Track Header Summary + Inline Color Swatches
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color(track.colorLabel.argb)),
                )
                Text(
                    text = track.name,
                    color = Pc.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = track.kind.label,
                    color = Pc.TextSecondary,
                    fontSize = 10.sp,
                )
            }

            // 9-Color Swatch Grid (5 + 4 rows for compact 1-tap color-coding)
            val rows = remember { TrackColorLabel.entries.chunked(5) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowItems.forEach { lbl ->
                            val isChosen = track.colorLabel == lbl
                            Box(
                                Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(Color(lbl.argb))
                                    .border(
                                        width = if (isChosen) 2.5.dp else 1.dp,
                                        color = if (isChosen) Color.White else Color(0x44FFFFFF),
                                        shape = CircleShape,
                                    )
                                    .clickable { onColorSelect(lbl) }
                                    .testTag("track_color_${lbl.name.lowercase()}"),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isChosen) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = lbl.label,
                                        tint = CanvasSpace.contrastOn(lbl.argb),
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = Pc.Divider, modifier = Modifier.padding(vertical = 4.dp))

        DropdownMenuItem(
            text = { Text("Rename Track...", color = Pc.TextPrimary, fontSize = 13.sp) },
            leadingIcon = {
                Icon(Icons.Rounded.Edit, contentDescription = null, tint = Pc.Accent, modifier = Modifier.size(18.dp))
            },
            onClick = onRename,
            modifier = Modifier.testTag("track_menu_rename_${track.id}"),
        )

        if (track.kind == TrackKind.GROUP) {
            DropdownMenuItem(
                text = {
                    Text(
                        if (track.expanded) "Collapse Group" else "Expand Group",
                        color = Pc.TextPrimary,
                        fontSize = 13.sp,
                    )
                },
                leadingIcon = {
                    Icon(
                        if (track.expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = Pc.TextPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                },
                onClick = onToggleGroupExpanded,
            )
        } else {
            DropdownMenuItem(
                text = { Text("Create New Group", color = Pc.TextPrimary, fontSize = 13.sp) },
                leadingIcon = {
                    Icon(Icons.Rounded.CreateNewFolder, contentDescription = null, tint = Pc.Accent, modifier = Modifier.size(18.dp))
                },
                onClick = onCreateGroup,
                modifier = Modifier.testTag("track_menu_create_group_${track.id}"),
            )
            existingGroups.forEach { grp ->
                if (track.groupId != grp.id) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Add to ${grp.name}",
                                color = Pc.TextPrimary,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Folder,
                                contentDescription = null,
                                tint = Color(grp.colorLabel.argb),
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = { onAssignGroup(grp.id) },
                    )
                }
            }
            if (track.groupId != null) {
                DropdownMenuItem(
                    text = { Text("Remove from Group", color = Pc.TextPrimary, fontSize = 13.sp) },
                    leadingIcon = {
                        Icon(Icons.Rounded.FolderOff, contentDescription = null, tint = Pc.Warning, modifier = Modifier.size(18.dp))
                    },
                    onClick = { onAssignGroup(null) },
                )
            }
        }

        HorizontalDivider(color = Pc.Divider, modifier = Modifier.padding(vertical = 2.dp))

        DropdownMenuItem(
            text = { Text("Move Up", color = Pc.TextPrimary, fontSize = 13.sp) },
            leadingIcon = {
                Icon(Icons.Rounded.ArrowUpward, contentDescription = null, tint = Pc.TextPrimary, modifier = Modifier.size(18.dp))
            },
            onClick = { onMoveOrder(-1) },
        )
        DropdownMenuItem(
            text = { Text("Move Down", color = Pc.TextPrimary, fontSize = 13.sp) },
            leadingIcon = {
                Icon(Icons.Rounded.ArrowDownward, contentDescription = null, tint = Pc.TextPrimary, modifier = Modifier.size(18.dp))
            },
            onClick = { onMoveOrder(1) },
        )

        DropdownMenuItem(
            text = {
                val label = when {
                    track.kind == TrackKind.AUDIO && track.muted -> "Unmute Audio"
                    track.kind == TrackKind.AUDIO -> "Mute Audio"
                    track.visible -> "Hide Track"
                    else -> "Show Track"
                }
                Text(label, color = Pc.TextPrimary, fontSize = 13.sp)
            },
            leadingIcon = {
                val icon = when {
                    track.kind == TrackKind.AUDIO && track.muted -> Icons.AutoMirrored.Rounded.VolumeUp
                    track.kind == TrackKind.AUDIO -> Icons.AutoMirrored.Rounded.VolumeOff
                    track.visible -> Icons.Rounded.VisibilityOff
                    else -> Icons.Rounded.Visibility
                }
                Icon(icon, contentDescription = null, tint = Pc.TextPrimary, modifier = Modifier.size(18.dp))
            },
            onClick = onToggleVisibilityOrMute,
        )

        DropdownMenuItem(
            text = {
                Text(
                    if (track.locked) "Unlock Track" else "Lock Track",
                    color = Pc.TextPrimary,
                    fontSize = 13.sp,
                )
            },
            leadingIcon = {
                Icon(
                    if (track.locked) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = if (track.locked) Pc.Warning else Pc.TextPrimary,
                    modifier = Modifier.size(18.dp),
                )
            },
            onClick = onToggleLock,
        )

        DropdownMenuItem(
            text = { Text("Track Settings...", color = Pc.TextPrimary, fontSize = 13.sp) },
            leadingIcon = {
                Icon(Icons.Rounded.Tune, contentDescription = null, tint = Pc.TextSecondary, modifier = Modifier.size(18.dp))
            },
            onClick = onInspect,
        )

        HorizontalDivider(color = Pc.Divider, modifier = Modifier.padding(vertical = 2.dp))

        DropdownMenuItem(
            text = {
                Text(
                    "Delete Track",
                    color = if (canDelete) Pc.Destructive else Pc.TextSecondary,
                    fontSize = 13.sp,
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = null,
                    tint = if (canDelete) Pc.Destructive else Pc.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            },
            enabled = canDelete,
            onClick = onDelete,
        )
    }
}
