package io.github.artcraftandroid.procraft.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import io.github.artcraftandroid.procraft.model.LayerBlendMode
import kotlin.math.roundToInt

private fun blendShortCode(mode: LayerBlendMode): String = when (mode) {
    LayerBlendMode.NORMAL -> "N"
    LayerBlendMode.MULTIPLY -> "M"
    LayerBlendMode.DARKEN -> "D"
    LayerBlendMode.COLOR_BURN -> "CB"
    LayerBlendMode.LINEAR_BURN -> "LB"
    LayerBlendMode.LIGHTEN -> "L"
    LayerBlendMode.SCREEN -> "S"
    LayerBlendMode.COLOR_DODGE -> "CD"
    LayerBlendMode.ADD -> "A"
    LayerBlendMode.OVERLAY -> "O"
    LayerBlendMode.SOFT_LIGHT -> "SL"
    LayerBlendMode.HARD_LIGHT -> "HL"
    LayerBlendMode.DIFFERENCE -> "Df"
    LayerBlendMode.EXCLUSION -> "Ex"
    LayerBlendMode.SUBTRACT -> "Sb"
    LayerBlendMode.DIVIDE -> "Dv"
    LayerBlendMode.HUE -> "H"
    LayerBlendMode.SATURATION -> "St"
    LayerBlendMode.COLOR -> "C"
    LayerBlendMode.LUMINOSITY -> "Lu"
}

@Composable
fun LayersPopover(
    vm: CanvasViewModel,
    caretFromEnd: Dp = 64.dp,
    modifier: Modifier = Modifier,
) {
    var expandedBlendIdx by remember { mutableStateOf<Int?>(null) }
    var actionMenuIdx by remember { mutableStateOf<Int?>(null) }
    var bgColorPickerOpen by remember { mutableStateOf(false) }
    var renamingIdx by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(vm.layers) {
        if (expandedBlendIdx != null && expandedBlendIdx !in vm.layers.indices) expandedBlendIdx = null
        if (actionMenuIdx != null && actionMenuIdx !in vm.layers.indices) actionMenuIdx = null
        if (renamingIdx != null && renamingIdx !in vm.layers.indices) renamingIdx = null
    }

    PcPopoverFrame(
        caretFromEnd = caretFromEnd,
        caretColor = Pc.PanelHeader,
        modifier = modifier
            .widthIn(max = 316.dp)
            .fillMaxWidth(0.88f)
            .heightIn(max = 480.dp),
        bodyModifier = Modifier
            .fillMaxWidth()
            .testTag("layers_popover"),
    ) {
        // Header
        Row(
            Modifier
                .fillMaxWidth()
                .background(Pc.PanelHeader)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Layers", color = Pc.TextPrimary, fontSize = Pc.TitleSize, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (vm.heldLayerIds.size >= 2) {
                    TextButton(
                        onClick = { vm.mergeHeldLayers() },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("merge_held_layers_button"),
                    ) {
                        Text("Merge (${vm.heldLayerIds.size})", color = Pc.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (vm.layers.count { it.visible } >= 2) {
                    IconButton(
                        onClick = { vm.mergeVisibleLayers() },
                        modifier = Modifier.size(48.dp).testTag("merge_visible_layers_button"),
                    ) {
                        Icon(LucideIcons.Layers, contentDescription = "Merge Visible Layers", tint = Pc.TextSecondary, modifier = Modifier.size(20.dp))
                    }
                }
                IconButton(
                    onClick = { vm.addLayer() },
                    modifier = Modifier.size(48.dp).testTag("add_layer_button"),
                ) {
                    Icon(PcIcons.MenuNewLayer, contentDescription = "Add Layer", tint = Pc.Accent, modifier = Modifier.size(24.dp))
                }
            }
        }
        HorizontalDivider(color = Pc.Divider, thickness = 0.6.dp)

        // Blend & Opacity quick editor when disclosed
        val blendTargetIdx = expandedBlendIdx
        if (blendTargetIdx != null && blendTargetIdx in vm.layers.indices) {
            val l = vm.layers[blendTargetIdx]
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Pc.PanelSurface)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Opacity", color = Pc.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text("${(l.opacity * 100).roundToInt()}%", color = Pc.TextSecondary, fontSize = 12.sp)
                }
                PcSlider(
                    value = l.opacity,
                    onValueChange = { vm.setLayerOpacity(blendTargetIdx, it) },
                    onValueChangeFinished = { vm.persistCurrentArtwork() },
                )
                Text("Blend Mode", color = Pc.TextSecondary, fontSize = 11.sp)
                LazyColumn(Modifier.height(144.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(LayerBlendMode.entries) { _, mode ->
                        val sel = l.blendMode == mode
                        val glyph = LucideIcons.BlendGlyphs[mode.label]
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (sel) Pc.RowSelected else Color.Transparent)
                                .clickable { vm.setLayerBlendMode(blendTargetIdx, mode) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (glyph != null) {
                                Icon(
                                    imageVector = glyph,
                                    contentDescription = mode.label,
                                    tint = if (sel) Pc.Accent else Pc.TextSecondary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Text(mode.label, color = if (sel) Pc.Accent else Pc.TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(mode.group, color = Pc.TextSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
            HorizontalDivider(color = Pc.Divider, thickness = 0.6.dp)
        }

        // Layer stack (displayed top-most layer first!)
        val indexedLayers = remember(vm.layers) { vm.layers.indices.reversed().map { it to vm.layers[it] } }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(indexedLayers, key = { (_, layer) -> layer.id }) { (realIdx, layer) ->
                val isSelected = realIdx == vm.activeLayerIndex
                val isHeld = layer.id in vm.heldLayerIds
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected || isHeld) Pc.RowSelected else Pc.PanelCard)
                        .border(
                            if (isSelected || isHeld) 1.5.dp else 0.5.dp,
                            when {
                                isSelected -> Pc.Accent
                                isHeld -> Pc.Warning
                                else -> Pc.Divider
                            },
                            RoundedCornerShape(10.dp),
                        )
                        .clickable {
                            if (isSelected) {
                                actionMenuIdx = realIdx
                            } else {
                                vm.selectLayer(realIdx)
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (layer.clippingMask || layer.isMask) {
                        Icon(PcIcons.Clip, contentDescription = if (layer.isMask) "Layer Mask" else "Clipping Mask", tint = Pc.Accent, modifier = Modifier.size(14.dp))
                    }
                    // Layer Thumbnail
                    Box(
                        Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFFFFFFF))
                            .border(0.8.dp, Pc.Divider, RoundedCornerShape(6.dp))
                            .clickable { vm.toggleHoldLayer(layer.id) },
                    ) {
                        layer.thumbnail?.let { bmp ->
                            Image(bmp, contentDescription = layer.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        }
                        if (isHeld) {
                            Box(
                                Modifier
                                    .align(Alignment.TopStart)
                                    .size(14.dp)
                                    .background(Pc.Warning, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.Check, contentDescription = "Held for merge", tint = Color.White, modifier = Modifier.size(10.dp))
                            }
                        }
                    }

                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            layer.name,
                            color = Pc.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .clickable {
                                        expandedBlendIdx = if (expandedBlendIdx == realIdx) null else realIdx
                                    }
                                    .padding(vertical = 1.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    blendShortCode(layer.blendMode),
                                    color = Pc.Accent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    "${(layer.opacity * 100).roundToInt()}%",
                                    color = Pc.TextSecondary,
                                    fontSize = 11.sp,
                                )
                                Icon(
                                    LucideIcons.Disclose,
                                    contentDescription = "Blend and Opacity",
                                    tint = Pc.TextSecondary,
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                            if (layer.alphaLock) Text("α", color = Pc.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            if (layer.isMask) Text("MASK", color = Pc.Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            if (layer.isReference) Text("REF", color = Pc.Warning, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            if (layer.isPrivate) Text("PVT", color = Pc.TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            if (layer.locked) Icon(Icons.Rounded.Lock, contentDescription = "Locked", tint = Pc.TextSecondary, modifier = Modifier.size(12.dp))
                        }
                    }

                    IconButton(onClick = { actionMenuIdx = realIdx }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.MoreHoriz, contentDescription = "Layer Options", tint = Pc.TextSecondary, modifier = Modifier.size(18.dp))
                    }
                    PcCheckbox(
                        checked = layer.visible,
                        onCheckedChange = { vm.toggleLayerVisibility(realIdx) },
                    )
                }
            }

            // Background Color Row
            item(key = "bg_color_row") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Pc.PanelCard)
                        .border(0.5.dp, Pc.Divider, RoundedCornerShape(10.dp))
                        .clickable { bgColorPickerOpen = !bgColorPickerOpen }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(CanvasSpace.color(vm.backgroundRgb))
                            .border(1.dp, Pc.Divider, RoundedCornerShape(6.dp)),
                    )
                    Text("Background Color", color = Pc.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    PcCheckbox(checked = vm.backgroundVisible, onCheckedChange = { vm.toggleBackgroundVisibility() })
                }
            }
        }

        if (bgColorPickerOpen) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Pc.PanelSurface)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    0xFFFFFFFF.toInt(),
                    0xFFF8F4EA.toInt(),
                    0xFFE5E5EA.toInt(),
                    0xFF28282C.toInt(),
                    0xFF141416.toInt(),
                    vm.colorRgb,
                ).forEach { col ->
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(CanvasSpace.color(col))
                            .border(1.dp, Pc.Divider, CircleShape)
                            .clickable { vm.setBackgroundColor(col) },
                    )
                }
            }
        }
    }

    // Layer Options Sheet Dialog
    actionMenuIdx?.let { idx ->
        val l = vm.layers.getOrNull(idx)
        if (l != null) {
            PcDialogShell(
                title = l.name,
                onDismiss = { actionMenuIdx = null },
                leftLabel = null,
                onLeftClick = null,
                rightLabel = "Done",
                onRightClick = { actionMenuIdx = null },
                modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(0.9f).heightIn(max = 540.dp),
            ) {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PcSectionCard {
                        PcNavRow("Rename", showChevron = false) { actionMenuIdx = null; renamingIdx = idx }
                        PcRowDivider()
                        PcNavRow("Select", showChevron = false) { actionMenuIdx = null; vm.selectLayerPixels(idx) }
                        PcRowDivider()
                        PcNavRow("Fill Layer", showChevron = false) { actionMenuIdx = null; vm.fillLayer(idx) }
                        PcRowDivider()
                        PcNavRow("Clear", showChevron = false) { actionMenuIdx = null; vm.clearLayer(idx) }
                    }
                    PcSectionCard("Masks & Behavior") {
                        PcToggleRow("Alpha Lock", checked = l.alphaLock) { vm.toggleAlphaLock(idx) }
                        if (idx > 0) {
                            PcRowDivider()
                            PcToggleRow("Clipping Mask", checked = l.clippingMask) { vm.toggleClippingMask(idx) }
                            PcRowDivider()
                            PcToggleRow("Mask", checked = l.isMask) { vm.toggleMaskLayer(idx) }
                        }
                        PcRowDivider()
                        PcToggleRow("Reference", checked = l.isReference) { vm.toggleReferenceLayer(idx) }
                        PcRowDivider()
                        PcToggleRow("Private Layer", checked = l.isPrivate) { vm.togglePrivateLayer(idx) }
                        PcRowDivider()
                        PcToggleRow("Lock Layer", checked = l.locked) { vm.toggleLayerLock(idx) }
                    }
                    PcSectionCard("Order & Color") {
                        PcNavRow("Invert", showChevron = false) { actionMenuIdx = null; vm.invertLayerColors(idx) }
                        if (idx < vm.layers.lastIndex) {
                            PcRowDivider()
                            PcNavRow("Move Up", showChevron = false) { actionMenuIdx = null; vm.moveLayer(idx, idx + 1) }
                        }
                        if (idx > 0) {
                            PcRowDivider()
                            PcNavRow("Move Down", showChevron = false) { actionMenuIdx = null; vm.moveLayer(idx, idx - 1) }
                            PcRowDivider()
                            PcNavRow("Merge Down", showChevron = false) { actionMenuIdx = null; vm.mergeLayerDown(idx) }
                        }
                        PcRowDivider()
                        PcNavRow("Duplicate", showChevron = false) { actionMenuIdx = null; vm.duplicateLayer(idx) }
                        PcRowDivider()
                        PcNavRow("Delete", destructive = true, showChevron = false) { actionMenuIdx = null; vm.deleteLayer(idx) }
                    }
                }
            }
        }
    }

    renamingIdx?.let { idx ->
        val l = vm.layers.getOrNull(idx)
        if (l != null) {
            var name by remember { mutableStateOf(l.name) }
            PcDialogShell(
                title = "Rename Layer",
                onDismiss = { renamingIdx = null },
                leftLabel = "Cancel",
                onLeftClick = { renamingIdx = null },
                rightLabel = "Save",
                onRightClick = { vm.renameLayer(idx, name); renamingIdx = null },
                modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(0.86f),
            ) {
                Column(Modifier.padding(14.dp)) {
                    PcInlineTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = "Layer Name",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
