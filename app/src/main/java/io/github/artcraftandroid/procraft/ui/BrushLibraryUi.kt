package io.github.artcraftandroid.procraft.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import io.github.artcraftandroid.procraft.engine.BrushLibraryCatalog

@Composable
fun BrushLibraryPopover(
    vm: CanvasViewModel,
    caretFromEnd: Dp = 180.dp,
    onImportAbr: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val active = vm.activeBrush
    var selectedSetId by remember(active.setId) { mutableStateOf(active.setId) }
    val currentSet = vm.brushSets.firstOrNull { it.id == selectedSetId } ?: vm.brushSets.firstOrNull() ?: return
    var newSetDialogOpen by remember { mutableStateOf(false) }
    var renameSetId by remember { mutableStateOf<String?>(null) }

    PcPopoverFrame(
        caretFromEnd = caretFromEnd,
        caretColor = Pc.PanelHeader,
        modifier = modifier
            .widthIn(max = 368.dp)
            .fillMaxWidth(0.92f)
            .heightIn(max = 470.dp),
        bodyModifier = Modifier
            .fillMaxWidth()
            .testTag("brush_library_popover"),
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
            Text("Brush Library", color = Pc.TextPrimary, fontSize = Pc.TitleSize, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                if (onImportAbr != null) {
                    IconButton(
                        onClick = onImportAbr,
                        modifier = Modifier.size(48.dp).testTag("brush_library_import_abr"),
                    ) {
                        Icon(Icons.Rounded.FileOpen, contentDescription = "Import .abr Brushes", tint = Pc.Accent, modifier = Modifier.size(20.dp))
                    }
                }
                IconButton(
                    onClick = { newSetDialogOpen = true },
                    modifier = Modifier.size(48.dp).testTag("new_brush_set_button"),
                ) {
                    Icon(LucideIcons.MenuNewSet, contentDescription = "New Brush Set", tint = Pc.Accent, modifier = Modifier.size(22.dp))
                }
                IconButton(
                    onClick = { vm.brushStudioTarget = active },
                    modifier = Modifier.size(48.dp).testTag("open_brush_studio_button"),
                ) {
                    Icon(LucideIcons.StudioProperties, contentDescription = "Edit Brush", tint = Pc.Accent, modifier = Modifier.size(22.dp))
                }
                IconButton(
                    onClick = { vm.createCustomBrushInSet(currentSet.id) },
                    modifier = Modifier.size(48.dp).testTag("new_custom_brush_button"),
                ) {
                    Icon(LucideIcons.MenuNewBrush, contentDescription = "New Brush", tint = Pc.Accent, modifier = Modifier.size(22.dp))
                }
            }
        }
        HorizontalDivider(color = Pc.Divider, thickness = 0.6.dp)

        Row(Modifier.fillMaxWidth().weight(1f)) {
            // Left: Brush Sets with Lucide SetIcons
            LazyColumn(
                Modifier
                    .width(134.dp)
                    .fillMaxHeight()
                    .background(Pc.PanelSurface)
                    .padding(vertical = 6.dp),
            ) {
                itemsIndexed(vm.brushSets, key = { _, s -> s.id }) { idx, set ->
                    val isSel = set.id == currentSet.id
                    val setIcon = LucideIcons.SetIcons[idx % LucideIcons.SetIcons.size]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSel) Pc.RowSelected else Color.Transparent)
                            .clickable { selectedSetId = set.id }
                            .padding(horizontal = 8.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Icon(
                            imageVector = setIcon,
                            contentDescription = set.name,
                            tint = if (isSel) Pc.Accent else Pc.TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            set.name,
                            color = if (isSel) Pc.Accent else Pc.TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (set.isCustom && isSel) {
                            Icon(
                                Icons.Rounded.Edit,
                                contentDescription = "Rename Set",
                                tint = Pc.Accent,
                                modifier = Modifier.size(14.dp).clickable { renameSetId = set.id },
                            )
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = "Delete Set",
                                tint = Pc.Destructive,
                                modifier = Modifier.size(14.dp).clickable { vm.deleteBrushSet(set.id) },
                            )
                        }
                    }
                }
            }

            // Right: Brushes with stroke previews
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(currentSet.brushes, key = { it.id }) { brush ->
                    val isSel = brush.id == active.id
                    val preview = remember(brush.id, brush.settings, Pc.light) {
                        BrushLibraryCatalog.renderStrokePreview(
                            brush,
                            if (Pc.light) 0xFF1A1A1C.toInt() else 0xFFF2F2F4.toInt(),
                        )
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSel) Pc.RowSelected else Pc.PanelCard)
                            .border(if (isSel) 1.5.dp else 0.5.dp, if (isSel) Pc.Accent else Pc.Divider, RoundedCornerShape(10.dp))
                            .clickable {
                                if (isSel) {
                                    vm.brushStudioTarget = brush
                                } else {
                                    vm.selectBrush(brush)
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            brush.name,
                            color = if (isSel) Pc.Accent else Pc.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Image(
                            bitmap = preview,
                            contentDescription = "${brush.name} stroke preview",
                            modifier = Modifier.fillMaxWidth().height(34.dp),
                        )
                    }
                }
            }
        }
    }

    if (newSetDialogOpen) {
        var name by remember { mutableStateOf("My Brushes") }
        PcDialogShell(
            title = "New Brush Set",
            onDismiss = { newSetDialogOpen = false },
            leftLabel = "Cancel",
            onLeftClick = { newSetDialogOpen = false },
            rightLabel = "Create",
            onRightClick = {
                vm.createBrushSet(name)
                newSetDialogOpen = false
            },
            modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(0.86f),
        ) {
            Column(Modifier.padding(14.dp)) {
                PcInlineTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "Set Name",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    renameSetId?.let { id ->
        val set = vm.brushSets.firstOrNull { it.id == id }
        if (set != null) {
            var name by remember(id) { mutableStateOf(set.name) }
            PcDialogShell(
                title = "Rename Brush Set",
                onDismiss = { renameSetId = null },
                leftLabel = "Cancel",
                onLeftClick = { renameSetId = null },
                rightLabel = "Save",
                onRightClick = {
                    vm.renameBrushSet(id, name)
                    renameSetId = null
                },
                modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(0.86f),
            ) {
                Column(Modifier.padding(14.dp)) {
                    PcInlineTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = "Set Name",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
