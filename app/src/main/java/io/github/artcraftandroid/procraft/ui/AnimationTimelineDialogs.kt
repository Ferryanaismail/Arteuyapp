package io.github.artcraftandroid.procraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.procraft.canvas.*
import io.github.artcraftandroid.procraft.model.TrackColorLabel
import io.github.artcraftandroid.procraft.model.TrackKind
import io.github.artcraftandroid.procraft.model.TimelineTrack
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun TimelineSettingsDialog(
    vm: CanvasViewModel,
    onDismiss: () -> Unit,
) {
    val anim = vm.animationState
    var fps by remember { mutableIntStateOf(anim.fps.coerceIn(1, 60)) }
    var totalFramesText by remember { mutableStateOf(anim.totalFrames.toString()) }
    var onionEnabled by remember { mutableStateOf(anim.onionSkinEnabled) }
    var onionFrames by remember { mutableIntStateOf(anim.onionSkinFrames.coerceIn(1, 4)) }
    var audioScrub by remember { mutableStateOf(anim.audioScrubEnabled) }

    val parsedFrames = (totalFramesText.toIntOrNull() ?: anim.totalFrames).coerceIn(2, 1200)
    val durationSec = parsedFrames.toFloat() / fps.coerceAtLeast(1)

    PcDialogShell(
        title = "Timeline Settings",
        onDismiss = onDismiss,
        leftLabel = "Cancel",
        onLeftClick = onDismiss,
        rightLabel = "Apply",
        onRightClick = {
            vm.setTimelineFps(fps)
            vm.setTimelineDuration(parsedFrames)
            if (onionEnabled != vm.animationState.onionSkinEnabled) {
                vm.toggleOnionSkin()
            }
            vm.setOnionSkinFrames(onionFrames)
            if (audioScrub != vm.animationState.audioScrubEnabled) {
                vm.toggleAudioScrubbing()
            }
            onDismiss()
        },
        modifier = Modifier
            .widthIn(max = 440.dp)
            .fillMaxWidth(0.92f)
            .heightIn(max = 560.dp)
            .testTag("timeline_settings_dialog"),
    ) {
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PcSectionCard("Frame Rate (FPS)") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Frames Per Second", color = Pc.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("$fps FPS", color = Pc.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    PcSlider(
                        value = (fps - 1) / 59f,
                        onValueChange = { f -> fps = 1 + (f * 59f).roundToInt().coerceIn(0, 59) },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(12, 24, 30, 60).forEach { presetFps ->
                            val sel = fps == presetFps
                            Box(
                                Modifier
                                    .weight(1f)
                                    .heightIn(min = 38.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (sel) Pc.Accent else Pc.PanelSurface)
                                    .clickable { fps = presetFps },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "${presetFps}fps",
                                    color = if (sel) Color.White else Pc.TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }

            PcSectionCard("Animation Duration") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Total Frames", color = Pc.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(
                            String.format(Locale.US, "%df • %.2fs", parsedFrames, durationSec),
                            color = Pc.Accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    PcInlineTextField(
                        value = totalFramesText,
                        onValueChange = { raw -> totalFramesText = raw.filter { it.isDigit() }.take(4) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        placeholder = "24",
                        inputTag = "timeline_duration_input",
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(24, 48, 96, 180, 300).forEach { presetDur ->
                            val sel = parsedFrames == presetDur
                            Box(
                                Modifier
                                    .weight(1f)
                                    .heightIn(min = 36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (sel) Pc.Accent else Pc.PanelSurface)
                                    .clickable { totalFramesText = presetDur.toString() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "${presetDur}f",
                                    color = if (sel) Color.White else Pc.TextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }

            PcSectionCard("Audio & Onion Skin") {
                PcToggleRow(
                    label = "Audio Track Scrubbing",
                    subtitle = "Play brief audio snippets when dragging playhead",
                    checked = audioScrub,
                    icon = if (audioScrub) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                ) { audioScrub = it }
                PcRowDivider()
                PcToggleRow(
                    label = "Onion Skinning",
                    subtitle = "Ghost previous & next keyframes on active track",
                    checked = onionEnabled,
                    icon = Icons.Rounded.Layers,
                ) { onionEnabled = it }
                if (onionEnabled) {
                    PcRowDivider()
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Onion Skin Range", color = Pc.TextPrimary, fontSize = 13.sp)
                            Text("±$onionFrames keyframes", color = Pc.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        PcSlider(
                            value = (onionFrames - 1) / 3f,
                            onValueChange = { f -> onionFrames = 1 + (f * 3f).roundToInt().coerceIn(0, 3) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TrackInspectorDialog(
    vm: CanvasViewModel,
    track: TimelineTrack,
    onDismiss: () -> Unit,
) {
    var nameText by remember(track.id) { mutableStateOf(track.name) }
    val groupTracks = remember(vm.timelineTracks) {
        vm.timelineTracks.filter { it.kind == TrackKind.GROUP && it.id != track.id }
    }
    val liveTrack = vm.timelineTracks.firstOrNull { it.id == track.id } ?: track

    PcDialogShell(
        title = "${liveTrack.kind.label} Settings",
        onDismiss = onDismiss,
        leftLabel = "Close",
        onLeftClick = onDismiss,
        rightLabel = "Save",
        onRightClick = {
            if (nameText.isNotBlank() && nameText.trim() != liveTrack.name) {
                vm.renameTrack(liveTrack.id, nameText)
            }
            onDismiss()
        },
        modifier = Modifier
            .widthIn(max = 440.dp)
            .fillMaxWidth(0.92f)
            .heightIn(max = 580.dp)
            .testTag("track_inspector_dialog"),
    ) {
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PcSectionCard("Track Label Name") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PcInlineTextField(
                        value = nameText,
                        onValueChange = { nameText = it },
                        placeholder = "Track Name",
                        inputTag = "track_name_input",
                    )
                }
            }

            PcSectionCard("Color Label") {
                LazyRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(TrackColorLabel.entries) { lbl ->
                        val selected = liveTrack.colorLabel == lbl
                        val c = Color(lbl.argb)
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) Color.White else Color(0x44FFFFFF),
                                    shape = CircleShape,
                                )
                                .clickable { vm.setTrackColorLabel(liveTrack.id, lbl) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = lbl.label,
                                    tint = CanvasSpace.contrastOn(lbl.argb),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }

            PcSectionCard("Track State") {
                PcToggleRow(
                    label = if (liveTrack.kind == TrackKind.AUDIO) "Audio Enabled (Not Muted)" else "Track Visible",
                    checked = if (liveTrack.kind == TrackKind.AUDIO) !liveTrack.muted else liveTrack.visible,
                    icon = if (liveTrack.kind == TrackKind.AUDIO) Icons.AutoMirrored.Rounded.VolumeUp else Icons.Rounded.Visibility,
                ) {
                    if (liveTrack.kind == TrackKind.AUDIO) {
                        vm.toggleTrackMute(liveTrack.id)
                    } else {
                        vm.toggleTrackVisibility(liveTrack.id)
                    }
                }
                if (liveTrack.kind == TrackKind.VIDEO || liveTrack.kind == TrackKind.GROUP) {
                    PcRowDivider()
                    PcToggleRow(
                        label = "Muted",
                        checked = liveTrack.muted,
                        icon = Icons.AutoMirrored.Rounded.VolumeOff,
                    ) { vm.toggleTrackMute(liveTrack.id) }
                }
                PcRowDivider()
                PcToggleRow(
                    label = "Lock Track",
                    checked = liveTrack.locked,
                    icon = Icons.Rounded.Lock,
                ) { vm.toggleTrackLock(liveTrack.id) }
                if (liveTrack.kind == TrackKind.GROUP) {
                    PcRowDivider()
                    PcToggleRow(
                        label = "Group Layer Order",
                        subtitle = "Hide children until selected via Layer Order frame (3x tap)",
                        checked = liveTrack.isLayerOrderGroup,
                        icon = Icons.Rounded.DynamicFeed,
                    ) { vm.toggleGroupLayerOrder(liveTrack.id) }
                }
                PcRowDivider()
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Track Opacity", color = Pc.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("${(liveTrack.opacity * 100f).roundToInt()}%", color = Pc.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    PcSlider(
                        value = liveTrack.opacity,
                        onValueChange = { v -> vm.setTrackOpacity(liveTrack.id, v, persist = false) },
                        onValueChangeFinished = { vm.persistCurrentArtwork() },
                    )
                }
            }

            PcSectionCard("Track Order & Grouping") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(Pc.PanelSurface)
                            .clickable { vm.moveTrackOrder(liveTrack.id, -1) }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move Track Up", tint = Pc.Accent, modifier = Modifier.size(16.dp))
                            Text("Move Up", color = Pc.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(Pc.PanelSurface)
                            .clickable { vm.moveTrackOrder(liveTrack.id, 1) }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move Track Down", tint = Pc.Accent, modifier = Modifier.size(16.dp))
                            Text("Move Down", color = Pc.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                if (liveTrack.kind != TrackKind.GROUP) {
                    PcRowDivider()
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Assign to Group Track", color = Pc.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            item {
                                val sel = liveTrack.groupId == null
                                GroupChip(
                                    label = "No Group",
                                    selected = sel,
                                    accent = Pc.Accent,
                                ) { vm.assignTrackToGroup(liveTrack.id, null) }
                            }
                            items(groupTracks, key = { it.id }) { grp ->
                                val sel = liveTrack.groupId == grp.id
                                GroupChip(
                                    label = grp.name,
                                    selected = sel,
                                    accent = Color(grp.colorLabel.argb),
                                ) { vm.assignTrackToGroup(liveTrack.id, grp.id) }
                            }
                            item {
                                GroupChip(
                                    label = "+ New Group",
                                    selected = false,
                                    accent = Pc.Accent,
                                ) { vm.createGroupTrack(liveTrack.id) }
                            }
                        }
                    }
                }
            }

            Button(
                onClick = {
                    vm.deleteTrack(liveTrack.id)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Pc.Destructive),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("delete_track_button"),
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = "Delete Track", tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Delete Track", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun GroupChip(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .heightIn(min = 38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) accent else Pc.PanelSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Color.White else Pc.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun RenameTrackDialog(
    vm: CanvasViewModel,
    track: TimelineTrack,
    onDismiss: () -> Unit,
) {
    var nameText by remember(track.id) { mutableStateOf(track.name) }

    PcDialogShell(
        title = "Rename Track",
        onDismiss = onDismiss,
        leftLabel = "Cancel",
        onLeftClick = onDismiss,
        rightLabel = "Rename",
        rightTag = "rename_track_confirm",
        onRightClick = {
            if (nameText.isNotBlank()) {
                vm.renameTrack(track.id, nameText)
            }
            onDismiss()
        },
        modifier = Modifier
            .widthIn(max = 380.dp)
            .fillMaxWidth(0.88f)
            .testTag("rename_track_dialog"),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PcInlineTextField(
                value = nameText,
                onValueChange = { nameText = it },
                label = "${track.kind.label} Name",
                placeholder = "Enter track name",
                inputTag = "rename_track_input",
            )
        }
    }
}

