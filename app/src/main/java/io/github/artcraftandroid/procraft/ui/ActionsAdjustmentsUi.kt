package io.github.artcraftandroid.procraft.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import io.github.artcraftandroid.procraft.engine.BrushLibraryCatalog
import io.github.artcraftandroid.procraft.model.*
import kotlin.math.roundToInt

private enum class ActionsTab(val label: String) {
    ADD("Add"),
    CANVAS("Canvas"),
    SHARE("Share"),
    VIDEO("Video"),
    PREFS("Prefs"),
    HELP("Help"),
}

@Composable
fun ActionsPopover(
    vm: CanvasViewModel,
    onInsertPhoto: () -> Unit,
    onPickReference: () -> Unit,
    caretFromStart: Dp = 80.dp,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(ActionsTab.ADD) }

    PcPopoverFrame(
        caretFromStart = caretFromStart,
        caretColor = Pc.PanelHeader,
        modifier = modifier
            .widthIn(max = 328.dp)
            .fillMaxWidth(0.9f)
            .heightIn(max = 480.dp),
        bodyModifier = Modifier
            .fillMaxWidth()
            .testTag("actions_popover"),
    ) {
        // Header
        Row(
            Modifier
                .fillMaxWidth()
                .background(Pc.PanelHeader)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Actions", color = Pc.TextPrimary, fontSize = Pc.TitleSize, fontWeight = FontWeight.SemiBold)
            Text(tab.label, color = Pc.Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        HorizontalDivider(color = Pc.Divider, thickness = 0.6.dp)

        // Icon Tab Strip
        Row(
            Modifier
                .fillMaxWidth()
                .background(Pc.PanelSurface)
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            val icons = mapOf(
                ActionsTab.ADD to LucideIcons.TabAdd,
                ActionsTab.CANVAS to LucideIcons.TabCanvas,
                ActionsTab.SHARE to LucideIcons.TabShare,
                ActionsTab.VIDEO to LucideIcons.TabVideo,
                ActionsTab.PREFS to LucideIcons.TabPrefs,
                ActionsTab.HELP to LucideIcons.TabHelp,
            )
            ActionsTab.entries.forEach { t ->
                val sel = tab == t
                Column(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (sel) Pc.RowSelected else Color.Transparent)
                        .clickable { tab = t }
                        .padding(horizontal = 7.dp, vertical = 5.dp)
                        .testTag("actions_tab_${t.name.lowercase()}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = icons[t] ?: LucideIcons.TabAdd,
                        contentDescription = t.label,
                        tint = if (sel) Pc.Accent else Pc.TextSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(t.label, color = if (sel) Pc.Accent else Pc.TextSecondary, fontSize = 10.sp)
                }
            }
        }
        HorizontalDivider(color = Pc.Divider, thickness = 0.6.dp)

        // Tab Body
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (tab) {
                ActionsTab.ADD -> {
                    PcSectionCard {
                        PcNavRow(
                            label = "Insert a photo",
                            icon = LucideIcons.TabAdd,
                            testTag = "actions_insert_photo",
                            onClick = onInsertPhoto,
                        )
                        PcRowDivider(indent = 42.dp)
                        PcNavRow(
                            label = "Add text",
                            icon = LucideIcons.TextOutline,
                            testTag = "actions_add_text",
                        ) { vm.startAddText() }
                        PcRowDivider(indent = 42.dp)
                        PcNavRow(
                            label = "Copy & Paste Menu",
                            icon = LucideIcons.GestureCopyPaste,
                            testTag = "actions_copy_paste_menu",
                        ) {
                            vm.panel = Panel.NONE
                            vm.copyPasteMenuOpen = true
                        }
                    }

                    PcSectionCard("Clipboard") {
                        PcNavRow(
                            label = "Cut",
                            icon = LucideIcons.CopyCut,
                            showChevron = false,
                            testTag = "actions_cut",
                        ) { vm.cutActiveOrSelection(); vm.panel = Panel.NONE }
                        PcRowDivider(indent = 42.dp)
                        PcNavRow(
                            label = "Copy layer",
                            icon = LucideIcons.CopyOne,
                            showChevron = false,
                            testTag = "actions_copy",
                        ) { vm.copyActiveOrSelection(); vm.panel = Panel.NONE }
                        PcRowDivider(indent = 42.dp)
                        PcNavRow(
                            label = "Copy canvas",
                            icon = LucideIcons.CopyAll,
                            showChevron = false,
                            testTag = "actions_copy_canvas",
                        ) { vm.copyCanvasAll(); vm.panel = Panel.NONE }
                        PcRowDivider(indent = 42.dp)
                        PcNavRow(
                            label = "Paste",
                            icon = LucideIcons.PasteDown,
                            showChevron = false,
                            testTag = "actions_paste",
                        ) { vm.pasteFromClipboard(); vm.panel = Panel.NONE }
                    }
                }

                ActionsTab.CANVAS -> {
                    PcSectionCard {
                        PcNavRow(
                            label = "Crop and Resize",
                            icon = LucideIcons.TabCanvas,
                            testTag = "actions_crop",
                        ) { vm.startCrop() }
                        PcRowDivider(indent = 42.dp)
                        PcToggleRow(
                            label = "Animation Assist",
                            icon = LucideIcons.InfoLayers,
                            checked = vm.animationState.isAnimation,
                            testTag = "actions_animation_switch",
                        ) {
                            vm.toggleAnimationAssist()
                            vm.panel = Panel.NONE
                        }
                        PcRowDivider(indent = 42.dp)
                        PcToggleRow(
                            label = "Page Assist",
                            icon = LucideIcons.CardCompact,
                            checked = vm.animationState.isPageAssist,
                            testTag = "actions_page_assist_switch",
                        ) {
                            vm.togglePageAssist()
                            vm.panel = Panel.NONE
                        }
                        PcRowDivider(indent = 42.dp)
                        PcToggleRow(
                            label = "Drawing Guide",
                            icon = LucideIcons.GuideGrid,
                            checked = vm.guideConfig.enabled,
                            testTag = "actions_guide_switch",
                        ) { enabled ->
                            vm.guideConfig = vm.guideConfig.copy(enabled = enabled)
                        }
                        PcRowDivider(indent = 42.dp)
                        PcNavRow(
                            label = "Edit Drawing Guide",
                            icon = LucideIcons.GestureAssisted,
                            testTag = "actions_edit_guide",
                        ) {
                            vm.guideConfig = vm.guideConfig.copy(enabled = true)
                            vm.editingGuide = true
                            vm.panel = Panel.NONE
                        }
                    }

                    PcSectionCard {
                        PcToggleRow(
                            label = "Reference",
                            icon = LucideIcons.Preview,
                            checked = vm.referenceVisible,
                            testTag = "actions_reference_switch",
                        ) { vis ->
                            vm.referenceVisible = vis
                            if (vis && vm.referenceBitmap == null) onPickReference()
                        }
                        PcRowDivider(indent = 42.dp)
                        PcToggleRow(
                            label = "Companion Preview",
                            icon = LucideIcons.StudioPreview,
                            checked = vm.companionVisible,
                            testTag = "actions_companion_switch",
                        ) { vis ->
                            vm.companionVisible = vis
                        }
                        PcRowDivider(indent = 42.dp)
                        PcNavRow(
                            label = "Flip Canvas Horizontally",
                            icon = LucideIcons.FlipHorizontal,
                            showChevron = false,
                            testTag = "actions_flip_canvas",
                        ) {
                            vm.runQuickAction(QuickAction.FLIP_HORIZONTAL)
                            vm.panel = Panel.NONE
                        }
                        PcRowDivider(indent = 42.dp)
                        PcNavRow(
                            label = "Canvas Information",
                            icon = LucideIcons.InfoAbout,
                            testTag = "actions_canvas_info",
                        ) {
                            vm.panel = Panel.NONE
                            vm.canvasInfoOpen = true
                        }
                    }
                }

                ActionsTab.SHARE -> {
                    PcSectionCard("Share Image") {
                        PcNavRow(
                            label = "PNG",
                            icon = LucideIcons.TabShare,
                            trailingText = "Lossless",
                            showChevron = false,
                            testTag = "actions_share_png",
                        ) {
                            vm.panel = Panel.NONE
                            vm.shareCurrentArtwork("PNG")
                        }
                        PcRowDivider(indent = 42.dp)
                        PcNavRow(
                            label = "JPEG",
                            icon = LucideIcons.TabShare,
                            trailingText = "Compact",
                            showChevron = false,
                            testTag = "actions_share_jpeg",
                        ) {
                            vm.panel = Panel.NONE
                            vm.shareCurrentArtwork("JPEG")
                        }
                        PcRowDivider(indent = 42.dp)
                        PcNavRow(
                            label = "PDF",
                            icon = LucideIcons.TabShare,
                            trailingText = "Document",
                            showChevron = false,
                            testTag = "actions_share_pdf",
                        ) {
                            vm.panel = Panel.NONE
                            vm.shareCurrentArtwork("PDF")
                        }
                    }
                    PcSectionCard("Share Layers") {
                        PcNavRow(
                            label = "Animated MP4 / GIF / Sheet",
                            icon = LucideIcons.InfoLayers,
                            testTag = "actions_animated_export",
                        ) {
                            vm.panel = Panel.NONE
                            vm.animatedExportOpen = true
                        }
                    }
                }

                ActionsTab.VIDEO -> {
                    PcSectionCard {
                        PcToggleRow(
                            label = "Time-lapse Recording",
                            icon = LucideIcons.InfoVideo,
                            checked = vm.currentArtwork?.timelapseEnabled == true,
                            testTag = "actions_timelapse_record_switch",
                        ) { _ ->
                            vm.toggleTimelapseEnabled()
                        }
                        PcRowDivider(indent = 42.dp)
                        PcNavRow(
                            label = "Time-lapse Replay",
                            icon = LucideIcons.InfoVideo,
                            testTag = "actions_timelapse_replay",
                        ) {
                            vm.openTimeLapseReplay()
                        }
                        PcRowDivider(indent = 42.dp)
                        PcNavRow(
                            label = "Export Time-lapse MP4",
                            icon = LucideIcons.TabShare,
                            showChevron = false,
                            testTag = "actions_timelapse_export_mp4",
                        ) {
                            vm.panel = Panel.NONE
                            vm.exportTimeLapseMp4()
                        }
                        PcRowDivider(indent = 42.dp)
                        PcNavRow(
                            label = "Export Time-lapse GIF",
                            icon = LucideIcons.TabShare,
                            showChevron = false,
                            testTag = "actions_timelapse_export",
                        ) {
                            vm.panel = Panel.NONE
                            vm.exportTimeLapseGif()
                        }
                        PcRowDivider(indent = 42.dp)
                        PcNavRow(
                            label = "Purge Time-lapse Video",
                            icon = Icons.Rounded.Delete,
                            destructive = true,
                            showChevron = false,
                            testTag = "actions_timelapse_clear",
                        ) {
                            vm.clearTimeLapse()
                            vm.panel = Panel.NONE
                        }
                    }
                    Text(
                        "Time-lapse snapshots exclude Private layers automatically.",
                        color = Pc.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }

                ActionsTab.PREFS -> {
                    PcSectionCard("Interface") {
                        PcToggleRow(
                            label = "Light interface",
                            icon = LucideIcons.AdjustHsb,
                            checked = vm.gestureSettings.lightInterface,
                            testTag = "prefs_light_switch",
                        ) { light ->
                            vm.updateGestureSettings { it.copy(lightInterface = light) }
                        }
                        PcRowDivider(indent = 42.dp)
                        PcToggleRow(
                            label = "Right-hand interface",
                            icon = LucideIcons.FlipHorizontal,
                            checked = vm.gestureSettings.rightHanded,
                            testTag = "prefs_right_handed_switch",
                        ) { right ->
                            vm.updateGestureSettings { it.copy(rightHanded = right) }
                        }
                        PcRowDivider(indent = 42.dp)
                        PcToggleRow(
                            label = "Brush cursor",
                            icon = LucideIcons.ModeDisc,
                            checked = vm.gestureSettings.brushCursor,
                            testTag = "prefs_cursor_switch",
                        ) { show ->
                            vm.updateGestureSettings { it.copy(brushCursor = show) }
                        }
                        PcRowDivider(indent = 42.dp)
                        PcToggleRow(
                            label = "Stroke feedback sound",
                            icon = LucideIcons.NoiseClouds,
                            checked = vm.gestureSettings.strokeSoundEnabled,
                            testTag = "prefs_sound_switch",
                        ) { on ->
                            vm.updateGestureSettings { it.copy(strokeSoundEnabled = on) }
                        }
                        PcRowDivider(indent = 42.dp)
                        PcToggleRow(
                            label = "Live zoom HUD",
                            icon = LucideIcons.FitToCanvas,
                            checked = vm.gestureSettings.zoomHudEnabled,
                            testTag = "prefs_zoom_hud_switch",
                        ) { show ->
                            vm.updateGestureSettings { it.copy(zoomHudEnabled = show) }
                        }
                    }
                    PcSectionCard("Brush & Opacity Sliders") {
                        val scalePct = (vm.gestureSettings.sideBarScale.coerceIn(0.45f, 1.0f) * 100f).roundToInt()
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Slider Size", color = Pc.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text("$scalePct%", color = Pc.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                            PcSlider(
                                value = ((vm.gestureSettings.sideBarScale - 0.45f) / 0.55f).coerceIn(0f, 1f),
                                onValueChange = { f ->
                                    val nextScale = (0.45f + f * 0.55f).coerceIn(0.45f, 1.0f)
                                    vm.updateGestureSettings { it.copy(sideBarScale = nextScale) }
                                },
                                modifier = Modifier.testTag("prefs_sidebar_size_slider"),
                            )
                            Text(
                                "Triple-tap sidebar to move up/down; tap canvas 1x when done.",
                                color = Pc.TextSecondary,
                                fontSize = 11.sp,
                            )
                        }
                    }
                    PcSectionCard("Controls") {
                        PcNavRow(
                            label = "Gesture Controls",
                            icon = LucideIcons.GestureGeneral,
                            testTag = "prefs_gesture_controls",
                        ) {
                            vm.panel = Panel.NONE
                            vm.gestureControlsOpen = true
                        }
                    }
                }

                ActionsTab.HELP -> {
                    PcSectionCard {
                        PcNavRow(
                            label = "Reset Zoom & Fit Canvas",
                            icon = LucideIcons.FitToCanvas,
                            showChevron = false,
                            testTag = "help_fit_canvas",
                        ) {
                            vm.fitCanvasToScreen()
                            vm.panel = Panel.NONE
                        }
                        PcRowDivider(indent = 42.dp)
                        PcNavRow(
                            label = "Keyboard & Touch Shortcuts",
                            icon = LucideIcons.Keyboard,
                            testTag = "help_shortcuts_dialog",
                        ) {
                            vm.panel = Panel.NONE
                            vm.shortcutsHelpOpen = true
                        }
                    }
                    PcSectionCard("Touch & Stylus Shortcuts") {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            HelpLine("Two-finger tap", "Undo last action")
                            HelpLine("Three-finger tap", "Redo last action")
                            HelpLine("Three-finger swipe down", "Copy & Paste Menu")
                            HelpLine("Three-finger scrub", "Clear active layer")
                            HelpLine("Four-finger tap", "Toggle full screen")
                            HelpLine("Hold stroke at end", "QuickShape snap")
                            HelpLine("Drag Color dot to canvas", "ColorDrop flood fill")
                            HelpLine("Hold canvas or tap Modify", "Eyedropper sample")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HelpLine(gesture: String, action: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(gesture, color = Pc.TextPrimary, fontSize = 12.sp)
        Text(action, color = Pc.TextSecondary, fontSize = 12.sp)
    }
}

@Composable
fun AdjustmentsPopover(
    vm: CanvasViewModel,
    caretFromStart: Dp = 120.dp,
    modifier: Modifier = Modifier,
) {
    val iconFor: (AdjustmentKind) -> ImageVector = { kind ->
        when (kind) {
            AdjustmentKind.HSB -> LucideIcons.AdjustHsb
            AdjustmentKind.COLOR_BALANCE -> LucideIcons.AdjustBalance
            AdjustmentKind.CURVES -> LucideIcons.AdjustCurves
            AdjustmentKind.GRADIENT_MAP -> LucideIcons.AdjustGradient
            AdjustmentKind.GAUSSIAN_BLUR -> LucideIcons.AdjustBlur
            AdjustmentKind.MOTION_BLUR -> LucideIcons.AdjustMotion
            AdjustmentKind.PERSPECTIVE_BLUR -> LucideIcons.AdjustPerspective
            AdjustmentKind.NOISE -> LucideIcons.AdjustNoise
            AdjustmentKind.SHARPEN -> LucideIcons.AdjustSharpen
            AdjustmentKind.BLOOM -> LucideIcons.AdjustBloom
            AdjustmentKind.GLITCH -> LucideIcons.AdjustGlitch
            AdjustmentKind.HALFTONE -> LucideIcons.AdjustHalftone
            AdjustmentKind.CHROMATIC_ABERRATION -> LucideIcons.AdjustChromatic
            AdjustmentKind.LIQUIFY -> LucideIcons.AdjustLiquify
            AdjustmentKind.CLONE -> LucideIcons.AdjustClone
        }
    }
    val groups = remember { AdjustmentKind.entries.groupBy { it.group } }

    PcPopoverFrame(
        caretFromStart = caretFromStart,
        caretColor = Pc.PanelHeader,
        modifier = modifier
            .widthIn(max = 296.dp)
            .fillMaxWidth(0.84f)
            .heightIn(max = 470.dp),
        bodyModifier = Modifier
            .fillMaxWidth()
            .testTag("adjustments_popover"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Pc.PanelHeader)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Adjustments", color = Pc.TextPrimary, fontSize = Pc.TitleSize, fontWeight = FontWeight.SemiBold)
        }
        HorizontalDivider(color = Pc.Divider, thickness = 0.6.dp)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            groups.forEach { (groupName, kinds) ->
                PcSectionCard(groupName) {
                    kinds.forEachIndexed { idx, kind ->
                        PcNavRow(
                            label = kind.label,
                            icon = iconFor(kind),
                            iconTint = Pc.Accent,
                            testTag = "adjust_item_${kind.name.lowercase()}",
                        ) { vm.startAdjustment(kind) }
                        if (idx < kinds.lastIndex) {
                            PcRowDivider(indent = 42.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AdjustmentControlBar(vm: CanvasViewModel) {
    val kind = vm.adjustingKind ?: return
    val gradients = remember { BrushLibraryCatalog.gradientPresets() }

    Column(
        Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Pc.PanelBg)
            .border(0.8.dp, Pc.Divider, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("adjustment_control_bar"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Title, Layer/Brush segmented mode pills & Apply/Cancel
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(kind.label, color = Pc.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (kind != AdjustmentKind.LIQUIFY && kind != AdjustmentKind.CLONE) {
                    Row(
                        Modifier
                            .padding(top = 4.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(Pc.PanelSurface)
                            .padding(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        val layerSel = !vm.adjustmentPaintMode
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (layerSel) Pc.Accent else Color.Transparent)
                                .clickable { vm.toggleAdjustmentPaintMode(false) }
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                                .testTag("adjust_mode_layer"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(LucideIcons.ModeLayer, contentDescription = "Layer", tint = if (layerSel) Color.White else Pc.TextSecondary, modifier = Modifier.size(14.dp))
                            Text("Layer", color = if (layerSel) Color.White else Pc.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (!layerSel) Pc.Accent else Color.Transparent)
                                .clickable { vm.toggleAdjustmentPaintMode(true) }
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                                .testTag("adjust_mode_brush"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(LucideIcons.ModePencil, contentDescription = "Brush", tint = if (!layerSel) Color.White else Pc.TextSecondary, modifier = Modifier.size(14.dp))
                            Text("Pencil", color = if (!layerSel) Color.White else Pc.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = { vm.cancelAdjustment() },
                    modifier = Modifier.testTag("adjustment_cancel_button"),
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Cancel Adjustment", tint = Pc.Destructive, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Cancel", color = Pc.Destructive)
                }
                Button(
                    onClick = { vm.applyAdjustment() },
                    colors = ButtonDefaults.buttonColors(containerColor = Pc.Accent),
                    shape = RoundedCornerShape(9.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("adjustment_apply_button"),
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = "Apply Adjustment", tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Apply", color = Color.White)
                }
            }
        }

        if (vm.adjustmentPaintMode && kind != AdjustmentKind.LIQUIFY && kind != AdjustmentKind.CLONE) {
            CompactAdjustSlider("Brush Size", "${(vm.brushSize * 100).roundToInt()}%", vm.brushSize) {
                vm.brushSize = it
            }
        }

        when (kind) {
            AdjustmentKind.HSB -> {
                CompactAdjustSlider("Hue", "${vm.adjustHsb.hueDeg.roundToInt()}°", (vm.adjustHsb.hueDeg + 180f) / 360f) { f ->
                    vm.adjustHsb = vm.adjustHsb.copy(hueDeg = f * 360f - 180f)
                    vm.previewAdjustment()
                }
                CompactAdjustSlider("Saturation", "${(vm.adjustHsb.saturation * 100).roundToInt()}%", vm.adjustHsb.saturation) { s ->
                    vm.adjustHsb = vm.adjustHsb.copy(saturation = s)
                    vm.previewAdjustment()
                }
                CompactAdjustSlider("Brightness", "${(vm.adjustHsb.brightness * 100).roundToInt()}%", vm.adjustHsb.brightness) { b ->
                    vm.adjustHsb = vm.adjustHsb.copy(brightness = b)
                    vm.previewAdjustment()
                }
            }

            AdjustmentKind.COLOR_BALANCE -> {
                CompactAdjustSlider("Cyan / Red", "${(vm.adjustBalance.cyanRed * 100).roundToInt()}", (vm.adjustBalance.cyanRed + 1f) * 0.5f) { v ->
                    vm.adjustBalance = vm.adjustBalance.copy(cyanRed = v * 2f - 1f)
                    vm.previewAdjustment()
                }
                CompactAdjustSlider("Magenta / Green", "${(vm.adjustBalance.magentaGreen * 100).roundToInt()}", (vm.adjustBalance.magentaGreen + 1f) * 0.5f) { v ->
                    vm.adjustBalance = vm.adjustBalance.copy(magentaGreen = v * 2f - 1f)
                    vm.previewAdjustment()
                }
                CompactAdjustSlider("Yellow / Blue", "${(vm.adjustBalance.yellowBlue * 100).roundToInt()}", (vm.adjustBalance.yellowBlue + 1f) * 0.5f) { v ->
                    vm.adjustBalance = vm.adjustBalance.copy(yellowBlue = v * 2f - 1f)
                    vm.previewAdjustment()
                }
            }

            AdjustmentKind.CURVES -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CurveChannel.entries.forEach { ch ->
                            val sel = vm.adjustCurveChannel == ch
                            val chColor = when (ch) {
                                CurveChannel.ALL -> Pc.Accent
                                CurveChannel.RED -> Color(0xFFE5484D)
                                CurveChannel.GREEN -> Color(0xFF30A46C)
                                CurveChannel.BLUE -> Color(0xFF0090FF)
                            }
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (sel) chColor else Pc.PanelSurface)
                                    .clickable {
                                        vm.adjustCurveChannel = ch
                                        vm.previewAdjustment()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            ) {
                                Text(ch.label, color = if (sel) Color.White else Pc.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                    TextButton(
                        onClick = {
                            vm.adjustCurves = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
                            vm.previewAdjustment()
                        },
                    ) { Text("Reset", color = Pc.TextSecondary, fontSize = 12.sp) }
                }

                val curveCol = when (vm.adjustCurveChannel) {
                    CurveChannel.ALL -> Pc.Accent
                    CurveChannel.RED -> Color(0xFFE5484D)
                    CurveChannel.GREEN -> Color(0xFF30A46C)
                    CurveChannel.BLUE -> Color(0xFF0090FF)
                }
                var activeNode by remember { mutableIntStateOf(-1) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(142.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF141418))
                        .border(1.dp, Pc.Divider, RoundedCornerShape(10.dp))
                        .pointerInput(vm.adjustCurves) {
                            detectDragGestures(
                                onDragStart = { down ->
                                    val w = size.width.toFloat().coerceAtLeast(1f)
                                    val h = size.height.toFloat().coerceAtLeast(1f)
                                    val nx = (down.x / w).coerceIn(0f, 1f)
                                    val ny = (1f - down.y / h).coerceIn(0f, 1f)
                                    activeNode = (nx * 4f).roundToInt().coerceIn(0, 4)
                                    vm.adjustCurves = vm.adjustCurves.toMutableList().apply { set(activeNode, ny) }
                                    vm.previewAdjustment()
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    if (activeNode in 0..4) {
                                        val h = size.height.toFloat().coerceAtLeast(1f)
                                        val ny = (1f - change.position.y / h).coerceIn(0f, 1f)
                                        vm.adjustCurves = vm.adjustCurves.toMutableList().apply { set(activeNode, ny) }
                                        vm.previewAdjustment()
                                    }
                                },
                                onDragEnd = { activeNode = -1 },
                                onDragCancel = { activeNode = -1 },
                            )
                        },
                ) {
                    Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                        val w = size.width
                        val h = size.height
                        for (g in 1..3) {
                            val gx = w * g / 4f
                            val gy = h * g / 4f
                            drawLine(Color(0x22FFFFFF), Offset(gx, 0f), Offset(gx, h), strokeWidth = 1f)
                            drawLine(Color(0x22FFFFFF), Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
                        }
                        drawLine(Color(0x33FFFFFF), Offset(0f, h), Offset(w, 0f), strokeWidth = 1.2f)
                        val pts = List(5) { i ->
                            Offset(w * (i / 4f), h * (1f - vm.adjustCurves.getOrElse(i) { i / 4f }))
                        }
                        val path = Path().apply {
                            moveTo(pts.first().x, pts.first().y)
                            for (i in 0 until pts.lastIndex) {
                                val p0 = pts[i]
                                val p1 = pts[i + 1]
                                val cx = (p0.x + p1.x) * 0.5f
                                cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                            }
                        }
                        drawPath(path, curveCol, style = Stroke(width = 3f))
                        pts.forEachIndexed { idx, pt ->
                            drawCircle(Color.White, if (idx == activeNode) 8f else 6f, pt)
                            drawCircle(curveCol, if (idx == activeNode) 8f else 6f, pt, style = Stroke(width = 2f))
                        }
                    }
                }
            }

            AdjustmentKind.GRADIENT_MAP -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(gradients, key = { it.id }) { grad ->
                        val sel = vm.adjustGradient.id == grad.id
                        Column(
                            Modifier
                                .width(84.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (sel) Pc.RowSelected else Pc.PanelSurface)
                                .border(if (sel) 1.5.dp else 0.8.dp, if (sel) Pc.Accent else Pc.Divider, RoundedCornerShape(8.dp))
                                .clickable {
                                    vm.adjustGradient = grad
                                    vm.previewAdjustment()
                                }
                                .padding(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(16.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Brush.horizontalGradient(grad.colors.map { CanvasSpace.color(it) })),
                            )
                            Text(grad.name, color = Pc.TextPrimary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                CompactAdjustSlider("Mix", "${(vm.adjustAmount * 100).roundToInt()}%", vm.adjustAmount) {
                    vm.adjustAmount = it
                    vm.previewAdjustment()
                }
            }

            AdjustmentKind.LIQUIFY -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(LiquifyMode.entries) { mode ->
                        val sel = vm.liquifyMode == mode
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (sel) Pc.Accent else Pc.PanelSurface)
                                .clickable { vm.liquifyMode = mode }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        ) {
                            Text(mode.label, color = if (sel) Color.White else Pc.TextPrimary, fontSize = 12.sp)
                        }
                    }
                }
                CompactAdjustSlider("Brush Size", "${(vm.brushSize * 100).roundToInt()}%", vm.brushSize) {
                    vm.brushSize = it
                }
            }

            AdjustmentKind.CLONE -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (vm.cloneSource == null) "Tap canvas to set Clone source, then drag to clone."
                        else "Clone source locked. Drag anywhere on canvas to paint.",
                        color = Pc.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (vm.cloneSource != null) {
                        TextButton(onClick = { vm.cloneSource = null }) { Text("Reset Source", color = Pc.Accent, fontSize = 12.sp) }
                    }
                }
                CompactAdjustSlider("Brush Size", "${(vm.brushSize * 100).roundToInt()}%", vm.brushSize) {
                    vm.brushSize = it
                }
            }

            else -> {
                CompactAdjustSlider("Intensity", "${(vm.adjustAmount * 100).roundToInt()}%", vm.adjustAmount) {
                    vm.adjustAmount = it
                    vm.previewAdjustment()
                }
            }
        }
    }
}

@Composable
private fun CompactAdjustSlider(
    label: String,
    valueLabel: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Pc.TextSecondary, fontSize = 12.sp, modifier = Modifier.width(86.dp))
        PcSlider(value = value.coerceIn(0f, 1f), onValueChange = onValueChange, modifier = Modifier.weight(1f))
        Text(valueLabel, color = Pc.TextPrimary, fontSize = 12.sp, modifier = Modifier.width(44.dp))
    }
}
