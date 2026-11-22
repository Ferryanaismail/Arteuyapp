package io.github.artcraftandroid.procraft.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import io.github.artcraftandroid.procraft.engine.TransformEngine
import io.github.artcraftandroid.procraft.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.*

import io.github.artcraftandroid.procraft.canvas.canInsertSelectionKeyframe
import io.github.artcraftandroid.procraft.canvas.centerAnchorOnSelectionOrLayer
import io.github.artcraftandroid.procraft.canvas.enterSelectionTransform
import io.github.artcraftandroid.procraft.canvas.hasActiveSelectionKeyframe
import io.github.artcraftandroid.procraft.canvas.insertSelectionMotionKeyframe

@Composable
fun SelectionControlBar(vm: CanvasViewModel) {
    var featherRadius by remember { mutableFloatStateOf(0f) }

    Column(
        Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .shadow(16.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(Pc.PanelBg)
            .border(0.8.dp, Pc.Divider, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("selection_control_bar"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val modes = listOf(
                    SelectionKind.AUTOMATIC to LucideIcons.Automatic,
                    SelectionKind.FREEHAND to LucideIcons.Freehand,
                    SelectionKind.RECTANGLE to LucideIcons.RectangleShape,
                    SelectionKind.ELLIPSE to LucideIcons.EllipseShape,
                )
                modes.forEach { (kind, icon) ->
                    val sel = vm.selectionKind == kind
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (sel) Pc.RowSelected else Color.Transparent)
                            .clickable { vm.selectionKind = kind }
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(icon, contentDescription = kind.label, tint = if (sel) Pc.Accent else Pc.TextPrimary, modifier = Modifier.size(22.dp))
                        Text(kind.label, color = if (sel) Pc.Accent else Pc.TextSecondary, fontSize = 10.sp)
                    }
                }
            }
            Button(
                onClick = {
                    vm.anchorMoveMode = false
                    vm.selecting = false
                },
                colors = ButtonDefaults.buttonColors(containerColor = Pc.Accent),
                shape = RoundedCornerShape(9.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                modifier = Modifier.testTag("selection_done_button"),
            ) { Text("Done", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            item {
                ToolChip(
                    icon = Icons.Rounded.GpsFixed,
                    label = if (vm.anchorMoveMode) "Dragging Anchor" else "Move Anchor",
                    selected = vm.anchorMoveMode,
                ) { vm.anchorMoveMode = !vm.anchorMoveMode }
            }
            item {
                ToolChip(Icons.Rounded.FilterCenterFocus, "Center Anchor") {
                    vm.centerAnchorOnSelectionOrLayer()
                }
            }
            item {
                ToolChip(Icons.Rounded.OpenWith, "Transform") {
                    vm.enterSelectionTransform()
                }
            }
            if (vm.animationState.isAnimation) {
                val canKey = vm.canInsertSelectionKeyframe()
                item {
                    ToolChip(
                        icon = Icons.Rounded.AddBox,
                        label = if (canKey) "Insert Keyframe" else "Key (Group Only)",
                        enabled = canKey,
                    ) {
                        vm.insertSelectionMotionKeyframe()
                    }
                }
                if (canKey || vm.hasActiveSelectionKeyframe()) {
                    item {
                        ToolChip(
                            icon = Icons.Rounded.ShowChart,
                            label = "Curve: ${vm.selectionTweenCurve.label}",
                            selected = vm.selectionCurveOverlayOpen,
                        ) {
                            vm.selectionCurveOverlayOpen = !vm.selectionCurveOverlayOpen
                        }
                    }
                    item {
                        ToolChip(Icons.Rounded.Timeline, "In-Between", enabled = canKey) {
                            vm.inBetweenDialogOpen = true
                        }
                    }
                }
            }
            item {
                ToolChip(
                    icon = if (vm.selectionSubtract) LucideIcons.SelectRemove else LucideIcons.SelectAdd,
                    label = if (vm.selectionSubtract) "Remove" else "Add",
                    selected = vm.selectionSubtract,
                ) { vm.selectionSubtract = !vm.selectionSubtract }
            }
            item { ToolChip(LucideIcons.Invert, "Invert") { vm.invertSelection() } }
            item { ToolChip(LucideIcons.ColorFill, "Color Fill") { vm.fillSelectionWithColor() } }
            item { ToolChip(LucideIcons.ClearSelection, "Clear Pixels") { vm.clearSelectedPixels() } }
            item { ToolChip(LucideIcons.SaveLoad, "Save") { vm.saveCurrentSelection() } }
            item { ToolChip(LucideIcons.SaveLoad, "Load") { vm.loadSavedSelection() } }
            item { ToolChip(LucideIcons.CancelAdjust, "Deselect") { vm.clearSelectionMask() } }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(LucideIcons.Feather, contentDescription = "Feather", tint = Pc.TextSecondary, modifier = Modifier.size(18.dp))
            Text("Feather", color = Pc.TextSecondary, fontSize = 12.sp)
            PcSlider(
                value = featherRadius / 24f,
                onValueChange = { featherRadius = (it * 24f).roundToInt().toFloat() },
                onValueChangeFinished = { if (featherRadius > 0f) vm.featherSelection(featherRadius.roundToInt()) },
                modifier = Modifier.weight(1f),
            )
            Text("${featherRadius.roundToInt()}px", color = Pc.TextPrimary, fontSize = 12.sp, modifier = Modifier.width(36.dp))
        }
    }
}

@Composable
fun TransformControlBar(vm: CanvasViewModel) {
    val t = vm.transformState
    Column(
        Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .shadow(16.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(Pc.PanelBg)
            .border(0.8.dp, Pc.Divider, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("transform_control_bar"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val modes = listOf(
                    TransformKind.FREEFORM to LucideIcons.Freeform,
                    TransformKind.UNIFORM to LucideIcons.Uniform,
                    TransformKind.DISTORT to LucideIcons.Distort,
                    TransformKind.WARP to LucideIcons.Warp,
                )
                modes.forEach { (kind, icon) ->
                    val sel = t.kind == kind
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (sel) Pc.RowSelected else Color.Transparent)
                            .clickable { vm.previewTransform(t.copy(kind = kind)) }
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(icon, contentDescription = kind.label, tint = if (sel) Pc.Accent else Pc.TextPrimary, modifier = Modifier.size(20.dp))
                        Text(kind.label, color = if (sel) Pc.Accent else Pc.TextSecondary, fontSize = 10.sp)
                    }
                }
            }
            Button(
                onClick = { vm.commitTransform() },
                colors = ButtonDefaults.buttonColors(containerColor = Pc.Accent),
                shape = RoundedCornerShape(9.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                modifier = Modifier.testTag("transform_done_button"),
            ) { Text("Done", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                ToolChip(
                    icon = Icons.Rounded.GpsFixed,
                    label = if (vm.anchorMoveMode) "Dragging Anchor" else "Move Anchor",
                    selected = vm.anchorMoveMode,
                ) { vm.anchorMoveMode = !vm.anchorMoveMode }
            }
            item {
                ToolChip(Icons.Rounded.FilterCenterFocus, "Center Anchor") {
                    vm.centerAnchorOnSelectionOrLayer()
                }
            }
            if (vm.animationState.isAnimation) {
                val canKey = vm.canInsertSelectionKeyframe()
                item {
                    ToolChip(
                        icon = Icons.Rounded.AddBox,
                        label = if (canKey) "Insert Keyframe" else "Key (Group Only)",
                        enabled = canKey,
                    ) {
                        vm.insertSelectionMotionKeyframe()
                    }
                }
                if (canKey || vm.hasActiveSelectionKeyframe()) {
                    item {
                        ToolChip(
                            icon = Icons.Rounded.ShowChart,
                            label = "Curve: ${vm.selectionTweenCurve.label}",
                            selected = vm.selectionCurveOverlayOpen,
                        ) {
                            vm.selectionCurveOverlayOpen = !vm.selectionCurveOverlayOpen
                        }
                    }
                    item {
                        ToolChip(Icons.Rounded.Timeline, "In-Between", enabled = canKey) {
                            vm.inBetweenDialogOpen = true
                        }
                    }
                }
            }
            item {
                ToolChip(LucideIcons.Snapping, "Snapping", selected = t.snapping) {
                    vm.previewTransform(t.copy(snapping = !t.snapping))
                }
            }
            item {
                ToolChip(LucideIcons.Interpolation, t.interpolation.label, selected = t.interpolation == TransformInterpolation.NEAREST) {
                    val next = if (t.interpolation == TransformInterpolation.BILINEAR) TransformInterpolation.NEAREST else TransformInterpolation.BILINEAR
                    vm.previewTransform(t.copy(interpolation = next))
                }
            }
            item {
                ToolChip(LucideIcons.FlipHorizontal, "Flip H", selected = t.flipH) {
                    vm.previewTransform(t.copy(flipH = !t.flipH))
                }
            }
            item {
                ToolChip(LucideIcons.FlipVertical, "Flip V", selected = t.flipV) {
                    vm.previewTransform(t.copy(flipV = !t.flipV))
                }
            }
            item {
                ToolChip(LucideIcons.Rotate45, "Rotate 45°") {
                    vm.previewTransform(t.copy(rotationDeg = (t.rotationDeg + 45f) % 360f))
                }
            }
            item {
                ToolChip(LucideIcons.FitToCanvas, "Fit") {
                    val bmp = vm.transformSelectionScopedBitmap ?: vm.activeLayer()?.bitmap ?: return@ToolChip
                    vm.previewTransform(TransformEngine.fitToCanvas(bmp).copy(kind = t.kind, snapping = t.snapping, interpolation = t.interpolation))
                }
            }
            item {
                ToolChip(PcIcons.TransformReset, "Reset") { vm.resetTransform() }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Scale", color = Pc.TextSecondary, fontSize = 12.sp, modifier = Modifier.width(46.dp))
            PcSlider(
                value = ((t.scaleX - 0.25f) / 2.25f).coerceIn(0f, 1f),
                onValueChange = { f ->
                    val s = 0.25f + f * 2.25f
                    vm.previewTransform(t.copy(scaleX = s, scaleY = s))
                },
                modifier = Modifier.weight(1f),
            )
            Text(
                "${(t.scaleX * 100).roundToInt()}%",
                color = Pc.Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .width(52.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { vm.transformNumericOpen = true }
                    .padding(vertical = 4.dp),
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Angle", color = Pc.TextSecondary, fontSize = 12.sp, modifier = Modifier.width(46.dp))
            PcSlider(
                value = ((t.rotationDeg + 180f) / 360f).coerceIn(0f, 1f),
                onValueChange = { f ->
                    vm.previewTransform(t.copy(rotationDeg = f * 360f - 180f))
                },
                modifier = Modifier.weight(1f),
            )
            Text(
                "${t.rotationDeg.roundToInt()}°",
                color = Pc.Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .width(52.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { vm.transformNumericOpen = true }
                    .padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
fun CropControlBar(vm: CanvasViewModel) {
    val art = vm.currentArtwork ?: return
    val crop = vm.cropState ?: return
    val w = (crop.right - crop.left).coerceAtLeast(64)
    val h = (crop.bottom - crop.top).coerceAtLeast(64)

    Column(
        Modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .shadow(16.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(Pc.PanelBg)
            .border(0.8.dp, Pc.Divider, RoundedCornerShape(16.dp))
            .padding(14.dp)
            .testTag("crop_control_bar"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Crop & Resize — $w × $h px", color = Pc.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.cropping = false; vm.cropState = null }) { Text("Cancel", color = Pc.Destructive) }
                Button(
                    onClick = { vm.applyCrop() },
                    colors = ButtonDefaults.buttonColors(containerColor = Pc.Accent),
                    shape = RoundedCornerShape(9.dp),
                    modifier = Modifier.testTag("crop_apply_button"),
                ) { Text("Apply", color = Color.White, fontWeight = FontWeight.SemiBold) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Width", color = Pc.TextSecondary, fontSize = 12.sp, modifier = Modifier.width(48.dp))
            PcSlider(
                value = w.toFloat() / art.width.coerceAtLeast(64),
                valueRange = 0.1f..1f,
                onValueChange = { f ->
                    val nw = (art.width * f).roundToInt().coerceIn(64, art.width)
                    val nl = crop.left.coerceIn(0, (art.width - nw).coerceAtLeast(0))
                    vm.cropState = crop.copy(left = nl, right = nl + nw)
                },
                modifier = Modifier.weight(1f),
            )
            Text("${w}px", color = Pc.TextPrimary, fontSize = 12.sp, modifier = Modifier.width(48.dp))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Height", color = Pc.TextSecondary, fontSize = 12.sp, modifier = Modifier.width(48.dp))
            PcSlider(
                value = h.toFloat() / art.height.coerceAtLeast(64),
                valueRange = 0.1f..1f,
                onValueChange = { f ->
                    val nh = (art.height * f).roundToInt().coerceIn(64, art.height)
                    val nt = crop.top.coerceIn(0, (art.height - nh).coerceAtLeast(0))
                    vm.cropState = crop.copy(top = nt, bottom = nt + nh)
                },
                modifier = Modifier.weight(1f),
            )
            Text("${h}px", color = Pc.TextPrimary, fontSize = 12.sp, modifier = Modifier.width(48.dp))
        }
    }
}

@Composable
fun GuideControlBar(vm: CanvasViewModel) {
    val g = vm.guideConfig
    Column(
        Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .shadow(16.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(Pc.PanelBg)
            .border(0.8.dp, Pc.Divider, RoundedCornerShape(16.dp))
            .padding(14.dp)
            .testTag("guide_control_bar"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Drawing Guides", color = Pc.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Assisted", color = Pc.TextSecondary, fontSize = 12.sp)
                PcSwitch(checked = g.assisted, onCheckedChange = { vm.guideConfig = g.copy(assisted = it) })
                Button(
                    onClick = { vm.editingGuide = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Pc.Accent),
                    shape = RoundedCornerShape(9.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) { Text("Done", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(GuideKind.entries) { kind ->
                val sel = g.kind == kind
                val icon = when (kind) {
                    GuideKind.GRID_2D -> LucideIcons.GuideGrid
                    GuideKind.ISOMETRIC -> LucideIcons.GuideIsometric
                    GuideKind.PERSPECTIVE -> LucideIcons.ChromaPerspective
                    else -> LucideIcons.GuideSymmetry
                }
                ToolChip(icon = icon, label = kind.label, selected = sel) {
                    vm.guideConfig = g.copy(enabled = true, kind = kind)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Grid", color = Pc.TextSecondary, fontSize = 12.sp, modifier = Modifier.width(38.dp))
            PcSlider(
                value = ((g.gridSpacingPx - 24f) / 260f).coerceIn(0f, 1f),
                onValueChange = { vm.guideConfig = g.copy(gridSpacingPx = 24f + it * 260f) },
                modifier = Modifier.weight(1f),
            )
            Text("Opacity", color = Pc.TextSecondary, fontSize = 12.sp)
            PcSlider(
                value = g.opacity,
                onValueChange = { vm.guideConfig = g.copy(opacity = it) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun TextEditorBar(vm: CanvasViewModel) {
    val cfg = vm.textConfig
    Column(
        Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .shadow(16.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(Pc.PanelBg)
            .border(0.8.dp, Pc.Divider, RoundedCornerShape(16.dp))
            .padding(14.dp)
            .testTag("text_editor_bar"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Edit Text", color = Pc.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.textEditing = false }) { Text("Cancel", color = Pc.Destructive) }
                Button(
                    onClick = { vm.commitTextLayer() },
                    colors = ButtonDefaults.buttonColors(containerColor = Pc.Accent),
                    shape = RoundedCornerShape(9.dp),
                    modifier = Modifier.testTag("text_commit_button"),
                ) { Text("Done", color = Color.White, fontWeight = FontWeight.SemiBold) }
            }
        }
        PcInlineTextField(
            value = cfg.text,
            onValueChange = { vm.textConfig = cfg.copy(text = it) },
            placeholder = "Enter text...",
            singleLine = false,
            maxLines = 2,
            inputTag = "text_layer_input",
            modifier = Modifier.fillMaxWidth(),
        )
        LazyRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item { TextFormatPill("B", cfg.bold) { vm.textConfig = cfg.copy(bold = !cfg.bold) } }
            item { TextFormatPill("I", cfg.italic) { vm.textConfig = cfg.copy(italic = !cfg.italic) } }
            item {
                TextFormatIconPill(LucideIcons.TextUnderline, "Underline", cfg.underline) {
                    vm.textConfig = cfg.copy(underline = !cfg.underline)
                }
            }
            item { Spacer(Modifier.width(4.dp)) }
            item {
                TextFormatIconPill(LucideIcons.AlignLeft, "Align Left", cfg.alignment == 0) {
                    vm.textConfig = cfg.copy(alignment = 0)
                }
            }
            item {
                TextFormatIconPill(LucideIcons.AlignCenter, "Align Center", cfg.alignment == 1) {
                    vm.textConfig = cfg.copy(alignment = 1)
                }
            }
            item {
                TextFormatIconPill(LucideIcons.AlignRight, "Align Right", cfg.alignment == 2) {
                    vm.textConfig = cfg.copy(alignment = 2)
                }
            }
            item {
                Text("${cfg.fontSizePx.roundToInt()}px", color = Pc.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Size", color = Pc.TextSecondary, fontSize = 12.sp)
            PcSlider(
                value = ((cfg.fontSizePx - 24f) / 220f).coerceIn(0f, 1f),
                onValueChange = { vm.textConfig = cfg.copy(fontSizePx = 24f + it * 220f) },
                modifier = Modifier.weight(1f),
            )
            Text("Y", color = Pc.TextSecondary, fontSize = 12.sp)
            PcSlider(
                value = cfg.yFraction,
                onValueChange = { vm.textConfig = cfg.copy(yFraction = it) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TextFormatPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 38.dp)
            .widthIn(min = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Pc.Accent else Pc.PanelSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) Color.White else Pc.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TextFormatIconPill(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 38.dp)
            .widthIn(min = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Pc.Accent else Pc.PanelSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (selected) Color.White else Pc.TextPrimary, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun ReferenceWindowOverlay(
    vm: CanvasViewModel,
    onPickImage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var offset by remember { mutableStateOf(Offset.Zero) }
    val bmp = vm.referenceBitmap
    val imgBmp = remember(bmp) { bmp?.asImageBitmap() }

    Column(
        modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .width(190.dp)
            .shadow(14.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(Pc.PanelBg)
            .border(0.8.dp, Pc.Divider, RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    offset += drag
                }
            }
            .testTag("reference_window"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Pc.PanelHeader)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Reference", color = Pc.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row {
                IconButton(onClick = onPickImage, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Rounded.PhotoLibrary, contentDescription = "Pick Reference Photo", tint = Pc.Accent, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = { vm.referenceVisible = false }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close Reference", tint = Pc.TextSecondary, modifier = Modifier.size(16.dp))
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(170.dp)
                .background(Pc.GalleryCell)
                .clickable(onClick = onPickImage),
            contentAlignment = Alignment.Center,
        ) {
            if (imgBmp != null) {
                Image(imgBmp, contentDescription = "Reference photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text("Tap to pick photo", color = Pc.TextSecondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun RadialQuickMenu(
    vm: CanvasViewModel,
    centerPx: Offset,
    onDismiss: () -> Unit,
) {
    val slots = vm.gestureSettings.quickMenuSlots.take(6)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x44000000))
            .clickable(onClick = onDismiss)
            .testTag("radial_quick_menu"),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(296.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(58.dp)
                    .shadow(12.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Pc.PanelBg)
                    .border(1.5.dp, Pc.Accent, CircleShape)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "Close QuickMenu", tint = Pc.TextPrimary)
            }
            slots.forEachIndexed { idx, action ->
                val angle = -PI / 2.0 + idx * (2.0 * PI / slots.size.coerceAtLeast(1))
                val radiusDp = 104f
                val ox = (cos(angle) * radiusDp).dp
                val oy = (sin(angle) * radiusDp).dp
                Box(
                    Modifier
                        .offset(ox, oy)
                        .widthIn(min = 86.dp)
                        .shadow(10.dp, RoundedCornerShape(18.dp))
                        .clip(RoundedCornerShape(18.dp))
                        .background(Pc.PanelBg)
                        .border(0.8.dp, Pc.Divider, RoundedCornerShape(18.dp))
                        .clickable { vm.runQuickAction(action) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(action.label, color = Pc.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

private enum class GestureCategory(val label: String) {
    GENERAL("General"),
    STYLUS_TOUCH("Stylus & Touch"),
    EYEDROPPER("Eyedropper"),
    QUICK_MENU("QuickMenu"),
    QUICK_SHAPE("QuickShape"),
    ASSISTED("Assisted Drawing"),
    FULL_SCREEN("Full Screen"),
    CLEAR_LAYER("Clear Layer"),
    COPY_PASTE("Copy & Paste"),
    UNDO_REDO("Undo & Redo"),
}

@Composable
fun GestureControlsDialog(vm: CanvasViewModel, onDismiss: () -> Unit) {
    val s = vm.gestureSettings
    var selectedCategory by remember { mutableStateOf(GestureCategory.GENERAL) }
    var editingSlotIndex by remember { mutableStateOf<Int?>(null) }

    val icons = remember {
        mapOf(
            GestureCategory.GENERAL to LucideIcons.GestureGeneral,
            GestureCategory.STYLUS_TOUCH to LucideIcons.StudioStylus,
            GestureCategory.EYEDROPPER to LucideIcons.GestureEyedropper,
            GestureCategory.QUICK_MENU to LucideIcons.GestureQuickMenu,
            GestureCategory.QUICK_SHAPE to LucideIcons.GestureQuickShape,
            GestureCategory.ASSISTED to LucideIcons.GestureAssisted,
            GestureCategory.FULL_SCREEN to LucideIcons.GestureFullScreen,
            GestureCategory.CLEAR_LAYER to LucideIcons.GestureClearLayer,
            GestureCategory.COPY_PASTE to LucideIcons.GestureCopyPaste,
            GestureCategory.UNDO_REDO to LucideIcons.Undo,
        )
    }

    PcDialogShell(
        title = "Gesture Controls",
        onDismiss = onDismiss,
        leftLabel = "Reset",
        onLeftClick = { vm.updateGestureSettings { GestureSettings() } },
        rightLabel = "Done",
        onRightClick = onDismiss,
        modifier = Modifier
            .widthIn(max = 660.dp)
            .fillMaxWidth(0.94f)
            .heightIn(max = 560.dp)
            .testTag("gesture_controls_dialog"),
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 520.dp
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier
                            .width(178.dp)
                            .fillMaxHeight()
                            .background(Pc.PanelSurface)
                            .padding(vertical = 8.dp, horizontal = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(GestureCategory.entries) { cat ->
                            val sel = selectedCategory == cat
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 44.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(if (sel) Pc.RowSelected else Color.Transparent)
                                    .clickable { selectedCategory = cat; editingSlotIndex = null }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(9.dp),
                            ) {
                                Icon(
                                    imageVector = icons[cat] ?: LucideIcons.GestureGeneral,
                                    contentDescription = cat.label,
                                    tint = if (sel) Pc.Accent else Pc.TextSecondary,
                                    modifier = Modifier.size(19.dp),
                                )
                                Text(
                                    cat.label,
                                    color = if (sel) Pc.Accent else Pc.TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    VerticalDivider(color = Pc.Divider, thickness = 0.6.dp)
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        GestureCategoryPane(
                            vm = vm,
                            category = selectedCategory,
                            editingSlotIndex = editingSlotIndex,
                            onEditSlotChange = { editingSlotIndex = it },
                        )
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    LazyRow(
                        Modifier
                            .fillMaxWidth()
                            .background(Pc.PanelSurface)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(GestureCategory.entries) { cat ->
                            val sel = selectedCategory == cat
                            Row(
                                Modifier
                                    .heightIn(min = 40.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(if (sel) Pc.RowSelected else Pc.PanelCard)
                                    .clickable { selectedCategory = cat; editingSlotIndex = null }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = icons[cat] ?: LucideIcons.GestureGeneral,
                                    contentDescription = cat.label,
                                    tint = if (sel) Pc.Accent else Pc.TextSecondary,
                                    modifier = Modifier.size(17.dp),
                                )
                                Text(
                                    cat.label,
                                    color = if (sel) Pc.Accent else Pc.TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = Pc.Divider, thickness = 0.6.dp)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        GestureCategoryPane(
                            vm = vm,
                            category = selectedCategory,
                            editingSlotIndex = editingSlotIndex,
                            onEditSlotChange = { editingSlotIndex = it },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GestureCategoryPane(
    vm: CanvasViewModel,
    category: GestureCategory,
    editingSlotIndex: Int?,
    onEditSlotChange: (Int?) -> Unit,
) {
    val s = vm.gestureSettings
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(category.label, color = Pc.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

        when (category) {
            GestureCategory.GENERAL -> {
                PcSectionCard("Canvas & Interface") {
                    PcToggleRow("Light Interface", s.lightInterface, LucideIcons.AdjustHsb) { v ->
                        vm.updateGestureSettings { it.copy(lightInterface = v) }
                    }
                    PcRowDivider()
                    PcToggleRow("Right-Hand Interface", s.rightHanded, LucideIcons.FlipHorizontal) { v ->
                        vm.updateGestureSettings { it.copy(rightHanded = v) }
                    }
                    PcRowDivider()
                    PcToggleRow("Show Live Zoom HUD", s.zoomHudEnabled, LucideIcons.FitToCanvas) { v ->
                        vm.updateGestureSettings { it.copy(zoomHudEnabled = v) }
                    }
                    PcRowDivider()
                    PcToggleRow("Stroke Feedback Sound", s.strokeSoundEnabled, LucideIcons.NoiseClouds) { v ->
                        vm.updateGestureSettings { it.copy(strokeSoundEnabled = v) }
                    }
                }
                PcSectionCard("ColorDrop Threshold") {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Fill Tolerance", color = Pc.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("${(s.colorDropThreshold * 100).roundToInt()}%", color = Pc.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        PcSlider(
                            value = s.colorDropThreshold,
                            onValueChange = { f -> vm.updateGestureSettings { it.copy(colorDropThreshold = f) } },
                        )
                    }
                }
            }

            GestureCategory.STYLUS_TOUCH -> {
                PcSectionCard("Palm Rejection & Cursor") {
                    PcToggleRow(
                        label = "Stylus Only (Palm Rejection)",
                        subtitle = "Ignore finger strokes while painting",
                        checked = s.palmRejection,
                        icon = LucideIcons.StudioStylus,
                    ) { v -> vm.updateGestureSettings { it.copy(palmRejection = v) } }
                    PcRowDivider()
                    PcToggleRow(
                        label = "Brush Cursor",
                        subtitle = "Show live outline while painting",
                        checked = s.brushCursor,
                        icon = LucideIcons.ModeDisc,
                    ) { v -> vm.updateGestureSettings { it.copy(brushCursor = v) } }
                }
            }

            GestureCategory.EYEDROPPER -> {
                PcSectionCard("Touch & Hold") {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Hold Delay", color = Pc.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("${s.eyedropperHoldMs} ms", color = Pc.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        PcSlider(
                            value = ((s.eyedropperHoldMs - 150) / 850f).coerceIn(0f, 1f),
                            onValueChange = { f ->
                                vm.updateGestureSettings { it.copy(eyedropperHoldMs = 150 + (f * 850f).roundToInt()) }
                            },
                        )
                    }
                }
                Text(
                    "Touch and hold anywhere on the canvas, or tap the sidebar Modify button, to sample a color with the magnifying loupe.",
                    color = Pc.TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            GestureCategory.QUICK_MENU -> {
                PcSectionCard("Invocation") {
                    PcToggleRow("QuickMenu Enabled", s.quickMenuEnabled, LucideIcons.GestureQuickMenu) { v ->
                        vm.updateGestureSettings { it.copy(quickMenuEnabled = v) }
                    }
                }
                val allActions = QuickAction.entries
                val slots = (0 until 6).map { idx -> s.quickMenuSlots.getOrElse(idx) { allActions[idx % allActions.size] } }
                PcSectionCard("Radial QuickMenu Slots") {
                    slots.forEachIndexed { idx, act ->
                        if (idx > 0) PcRowDivider()
                        PcNavRow(
                            label = "Slot ${idx + 1}",
                            trailingText = act.label,
                            showChevron = true,
                        ) {
                            onEditSlotChange(if (editingSlotIndex == idx) null else idx)
                        }
                    }
                }
                if (editingSlotIndex != null && editingSlotIndex in 0 until 6) {
                    val slotIdx = editingSlotIndex
                    val currentAct = slots[slotIdx]
                    PcSectionCard("Choose Action for Slot ${slotIdx + 1}") {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            allActions.chunked(2).forEach { pair ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    pair.forEach { candidate ->
                                        val sel = candidate == currentAct
                                        Box(
                                            Modifier
                                                .weight(1f)
                                                .heightIn(min = 40.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (sel) Pc.Accent else Pc.PanelSurface)
                                                .clickable {
                                                    val nextSlots = slots.toMutableList().apply { set(slotIdx, candidate) }
                                                    vm.updateGestureSettings { it.copy(quickMenuSlots = nextSlots) }
                                                    onEditSlotChange(null)
                                                }
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            contentAlignment = Alignment.CenterStart,
                                        ) {
                                            Text(
                                                candidate.label,
                                                color = if (sel) Color.White else Pc.TextPrimary,
                                                fontSize = 11.sp,
                                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }

            GestureCategory.QUICK_SHAPE -> {
                PcSectionCard("Current Brush QuickShape") {
                    PcToggleRow(
                        label = "Draw & Hold QuickShape",
                        subtitle = "Active brush: ${vm.activeBrush.name}",
                        checked = vm.activeBrush.settings.quickShapeEnabled,
                        icon = LucideIcons.GestureQuickShape,
                    ) { v ->
                        vm.updateActiveBrushSettings { it.copy(quickShapeEnabled = v) }
                    }
                }
                Text(
                    "Hold your finger or stylus still at the end of a stroke to snap it into a straight line, ellipse, or polygon.",
                    color = Pc.TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            GestureCategory.ASSISTED -> {
                val g = vm.guideConfig
                PcSectionCard("Drawing Guide Assistance") {
                    PcToggleRow("Drawing Guide Overlay", g.enabled, LucideIcons.GuideGrid) { v ->
                        vm.guideConfig = g.copy(enabled = v)
                    }
                    PcRowDivider()
                    PcToggleRow("Assisted Drawing", g.assisted, LucideIcons.GestureAssisted) { v ->
                        vm.guideConfig = g.copy(assisted = v)
                    }
                }
            }

            GestureCategory.FULL_SCREEN -> {
                PcSectionCard("Touch Toggle") {
                    PcToggleRow("Four-finger tap for Full Screen", s.fourFingerFullscreen, LucideIcons.GestureFullScreen) { v ->
                        vm.updateGestureSettings { it.copy(fourFingerFullscreen = v) }
                    }
                }
            }

            GestureCategory.CLEAR_LAYER -> {
                PcSectionCard("Scrub Gesture") {
                    PcToggleRow("Three-finger scrub to Clear Layer", s.threeFingerClear, LucideIcons.GestureClearLayer) { v ->
                        vm.updateGestureSettings { it.copy(threeFingerClear = v) }
                    }
                }
            }

            GestureCategory.COPY_PASTE -> {
                PcSectionCard("Three-Finger Menu") {
                    PcToggleRow("Three-finger swipe down for Copy & Paste", s.threeFingerCopyPaste, LucideIcons.GestureCopyPaste) { v ->
                        vm.updateGestureSettings { it.copy(threeFingerCopyPaste = v) }
                    }
                }
            }

            GestureCategory.UNDO_REDO -> {
                PcSectionCard("Tap Gestures") {
                    PcToggleRow("Two-finger tap to Undo", s.twoFingerUndo, LucideIcons.Undo) { v ->
                        vm.updateGestureSettings { it.copy(twoFingerUndo = v) }
                    }
                    PcRowDivider()
                    PcToggleRow("Three-finger tap to Redo", s.threeFingerRedo, LucideIcons.Redo) { v ->
                        vm.updateGestureSettings { it.copy(threeFingerRedo = v) }
                    }
                }
            }
        }
    }
}

@Composable
fun CanvasInfoDialog(vm: CanvasViewModel, onDismiss: () -> Unit) {
    val art = vm.currentArtwork ?: return
    val mins = art.trackedSeconds / 60L
    val secs = art.trackedSeconds % 60L
    PcDialogShell(
        title = "Canvas Information",
        onDismiss = onDismiss,
        leftLabel = null,
        onLeftClick = null,
        rightLabel = "Done",
        onRightClick = onDismiss,
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(0.9f),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PcSectionCard("Artwork") {
                InfoRow(LucideIcons.InfoAbout, "Title", art.title)
                PcRowDivider()
                InfoRow(LucideIcons.InfoDimensions, "Dimensions", "${art.width} × ${art.height} px")
                PcRowDivider()
                InfoRow(LucideIcons.InfoDimensions, "DPI", "${art.dpi} DPI")
            }
            PcSectionCard("Color & Metrics") {
                InfoRow(LucideIcons.InfoProfile, "Color Profile", art.colorProfile)
                PcRowDivider()
                InfoRow(LucideIcons.InfoLayers, "Layers", "${vm.layers.size}")
                PcRowDivider()
                InfoRow(LucideIcons.InfoStatistics, "Total Strokes", "${art.totalStrokes}")
                PcRowDivider()
                InfoRow(LucideIcons.InfoStatistics, "Tracked Time", String.format("%02dm %02ds", mins, secs))
            }
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = label, tint = Pc.Accent, modifier = Modifier.size(18.dp))
            Text(label, color = Pc.TextSecondary, fontSize = 13.sp)
        }
        Text(value, color = Pc.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun TimeLapseReplayDialog(vm: CanvasViewModel, onDismiss: () -> Unit) {
    val frames = vm.timelapseFrames
    var idx by remember(frames) { mutableIntStateOf(0) }
    var playing by remember(frames) { mutableStateOf(frames.size > 1) }

    LaunchedEffect(playing, frames.size) {
        if (playing && frames.isNotEmpty()) {
            while (playing) {
                delay(220)
                idx = (idx + 1) % frames.size
            }
        }
    }

    val currentBmp by produceState<ImageBitmap?>(initialValue = null, idx, frames) {
        val f = frames.getOrNull(idx)
        value = if (f != null) {
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() }
        } else null
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Pc.Background)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(16.dp)
                .testTag("timelapse_replay_dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Time-lapse Replay", color = Pc.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Pc.Accent),
                    shape = RoundedCornerShape(9.dp),
                ) {
                    Text("Done", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Pc.GalleryCell),
                contentAlignment = Alignment.Center,
            ) {
                if (currentBmp != null) {
                    Image(currentBmp!!, contentDescription = "Time-lapse frame ${idx + 1}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                } else {
                    Text(
                        if (frames.isEmpty()) "No time-lapse frames recorded yet. Draw on canvas first!" else "Loading frame...",
                        color = Pc.TextSecondary,
                        fontSize = 14.sp,
                    )
                }
            }
            if (frames.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { playing = !playing }, modifier = Modifier.size(44.dp)) {
                        Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = "Toggle Replay", tint = Pc.Accent)
                    }
                    PcSlider(
                        value = if (frames.size <= 1) 0f else idx.toFloat() / (frames.size - 1),
                        onValueChange = { f ->
                            playing = false
                            idx = (f * (frames.size - 1)).roundToInt().coerceIn(0, frames.lastIndex)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Text("${idx + 1}/${frames.size}", color = Pc.TextSecondary, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ToolChip(
    icon: ImageVector,
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val contentTint = when {
        selected -> Color.White
        enabled -> Pc.TextPrimary
        else -> Pc.TextSecondary.copy(alpha = 0.55f)
    }
    Row(
        Modifier
            .heightIn(min = 38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Pc.Accent else Pc.PanelSurface.copy(alpha = if (enabled) 1f else 0.55f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, contentDescription = label, tint = contentTint, modifier = Modifier.size(16.dp))
        Text(label, color = contentTint, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
