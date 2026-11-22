package io.github.artcraftandroid.procraft.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import io.github.artcraftandroid.procraft.model.*
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun CopyPastePopover(
    vm: CanvasViewModel,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x55000000))
            .clickable(onClick = onDismiss)
            .testTag("copy_paste_backdrop"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .fillMaxWidth(0.86f)
                .shadow(18.dp, Pc.PanelShape)
                .clip(Pc.PanelShape)
                .background(Pc.PanelBg)
                .border(0.8.dp, Pc.Divider, Pc.PanelShape)
                .clickable(enabled = false) {}
                .padding(14.dp)
                .testTag("copy_paste_popover"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Copy & Paste", color = Pc.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close Copy & Paste", tint = Pc.TextSecondary)
                }
            }
            HorizontalDivider(color = Pc.Divider, thickness = 0.6.dp)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CopyPasteTile(LucideIcons.CopyCut, "Cut", Modifier.weight(1f), "cp_cut") {
                    vm.cutActiveOrSelection()
                    onDismiss()
                }
                CopyPasteTile(LucideIcons.CopyOne, "Copy", Modifier.weight(1f), "cp_copy") {
                    vm.copyActiveOrSelection()
                    onDismiss()
                }
                CopyPasteTile(LucideIcons.CopyAll, "Copy All", Modifier.weight(1f), "cp_copy_all") {
                    vm.copyCanvasAll()
                    onDismiss()
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CopyPasteTile(LucideIcons.CutPaste, "Cut & Paste", Modifier.weight(1f), "cp_cut_paste") {
                    vm.cutAndPaste()
                    onDismiss()
                }
                CopyPasteTile(LucideIcons.CopyDuplicate, "Duplicate", Modifier.weight(1f), "cp_duplicate") {
                    val hasSelection = vm.selectionMask?.isEmpty() == false
                    if (hasSelection) {
                        vm.copyActiveOrSelection()
                        vm.pasteFromClipboard()
                    } else {
                        vm.duplicateLayer(vm.activeLayerIndex)
                    }
                    onDismiss()
                }
                CopyPasteTile(LucideIcons.PasteDown, "Paste", Modifier.weight(1f), "cp_paste") {
                    vm.pasteFromClipboard()
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun CopyPasteTile(
    icon: ImageVector,
    label: String,
    modifier: Modifier,
    testTag: String,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .heightIn(min = 68.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Pc.PanelCard)
            .border(0.6.dp, Pc.Divider, RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Pc.Accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = Pc.TextPrimary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun AnimatedExportDialog(vm: CanvasViewModel, onDismiss: () -> Unit) {
    var selectedFormat by remember { mutableStateOf("GIF") }
    var fps by remember { mutableIntStateOf(vm.animationState.fps.coerceIn(1, 24)) }
    var transparentBg by remember { mutableStateOf(false) }
    var pingPong by remember { mutableStateOf(false) }

    PcDialogShell(
        title = "Animated Export",
        onDismiss = onDismiss,
        leftLabel = "Cancel",
        onLeftClick = onDismiss,
        rightLabel = "Export",
        onRightClick = {
            vm.exportAnimatedSequence(selectedFormat, fps, transparentBg, pingPong)
            onDismiss()
        },
        rightTag = "export_sequence_confirm",
        modifier = Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth(0.9f)
            .testTag("animated_export_dialog"),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PcSectionCard("Format") {
                Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("GIF" to "Animated GIF", "SHEET" to "Sprite Sheet", "PDF" to "Multi-page PDF").forEach { (fmt, lbl) ->
                        val sel = selectedFormat == fmt
                        Box(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (sel) Pc.Accent else Pc.PanelSurface)
                                .clickable { selectedFormat = fmt }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(lbl, color = if (sel) Color.White else Pc.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            if (selectedFormat == "GIF") {
                PcSectionCard("Animation Playback") {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Frames Per Second", color = Pc.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("$fps FPS", color = Pc.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        PcSlider(
                            value = (fps - 1) / 23f,
                            onValueChange = { f -> fps = 1 + (f * 23f).roundToInt().coerceIn(0, 23) },
                        )
                    }
                    PcRowDivider()
                    PcToggleRow("Ping-Pong Loop", pingPong) { pingPong = it }
                }
            }

            PcSectionCard("Background") {
                PcToggleRow("Transparent Background", transparentBg) { transparentBg = it }
            }

            val frameCountText = if (vm.timelineTracks.isNotEmpty()) {
                "${vm.animationState.totalFrames} timeline frames at ${vm.animationState.fps} FPS"
            } else {
                "${vm.layers.size} layer frames"
            }
            Text(
                "Ready to export $frameCountText.",
                color = Pc.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
fun CompanionWindowOverlay(
    vm: CanvasViewModel,
    modifier: Modifier = Modifier,
) {
    val art = vm.currentArtwork ?: return
    var offset by remember { mutableStateOf(Offset.Zero) }
    val previewPaint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    val tick = vm.compositeTick

    Column(
        modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .width(164.dp)
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
            .testTag("companion_window"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Pc.PanelHeader)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Preview", color = Pc.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row {
                IconButton(onClick = { vm.fitCanvasToScreen() }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.FitScreen, contentDescription = "Fit Canvas", tint = Pc.Accent, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = { vm.companionVisible = false }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close Preview", tint = Pc.TextSecondary, modifier = Modifier.size(16.dp))
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(128.dp)
                .background(Pc.GalleryCell)
                .clickable { vm.fitCanvasToScreen() }
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val _observe = tick
                val cache = vm.tileCache ?: return@Canvas
                val scale = min(size.width / art.width.coerceAtLeast(1), size.height / art.height.coerceAtLeast(1))
                val drawW = art.width * scale
                val drawH = art.height * scale
                val ox = (size.width - drawW) * 0.5f
                val oy = (size.height - drawH) * 0.5f
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    native.save()
                    native.translate(ox, oy)
                    native.scale(scale, scale)
                    val tiles = cache.tiles
                    for (i in tiles.indices) {
                        val t = tiles[i]
                        if (t.version >= 0 && !t.bitmap.isRecycled) {
                            native.drawBitmap(t.bitmap, t.left.toFloat(), t.top.toFloat(), previewPaint)
                        }
                    }
                    native.restore()
                }
            }
        }
    }
}

@Composable
fun ZoomHudPill(
    vm: CanvasViewModel,
    modifier: Modifier = Modifier,
) {
    val pct = (vm.viewZoom * 100f).roundToInt()
    val rot = (((vm.viewRotationDeg % 360f) + 360f) % 360f).roundToInt()
    val label = if (abs(vm.viewRotationDeg) > 1.5f) "$pct% • ${rot}°" else "$pct%"

    Box(
        modifier
            .clip(Pc.PillShape)
            .background(Color(0xBB1C1C20))
            .border(0.8.dp, Color(0x44FFFFFF), Pc.PillShape)
            .clickable { vm.fitCanvasToScreen() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("zoom_hud_pill"),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun TransformNumericDialog(vm: CanvasViewModel, onDismiss: () -> Unit) {
    val t = vm.transformState
    var scaleText by remember { mutableStateOf((t.scaleX * 100f).roundToInt().toString()) }
    var rotText by remember { mutableStateOf(t.rotationDeg.roundToInt().toString()) }
    var txText by remember { mutableStateOf(t.translateX.roundToInt().toString()) }
    var tyText by remember { mutableStateOf(t.translateY.roundToInt().toString()) }

    PcDialogShell(
        title = "Transform Values",
        onDismiss = onDismiss,
        leftLabel = "Cancel",
        onLeftClick = onDismiss,
        rightLabel = "Apply",
        onRightClick = {
            val s = (scaleText.toFloatOrNull()?.div(100f) ?: t.scaleX).coerceIn(0.1f, 5f)
            val r = (rotText.toFloatOrNull() ?: t.rotationDeg)
            val dx = txText.toFloatOrNull() ?: t.translateX
            val dy = tyText.toFloatOrNull() ?: t.translateY
            vm.previewTransform(t.copy(scaleX = s, scaleY = s, rotationDeg = r, translateX = dx, translateY = dy))
            onDismiss()
        },
        modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth(0.9f),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PcSectionCard {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumericField("Scale (%)", scaleText) { scaleText = it }
                    NumericField("Rotation (°)", rotText) { rotText = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { NumericField("Move X (px)", txText) { txText = it } }
                        Box(Modifier.weight(1f)) { NumericField("Move Y (px)", tyText) { tyText = it } }
                    }
                }
            }
        }
    }
}

@Composable
private fun NumericField(label: String, value: String, onValueChange: (String) -> Unit) {
    PcInlineTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun ShortcutsHelpDialog(onDismiss: () -> Unit) {
    PcDialogShell(
        title = "Shortcuts",
        onDismiss = onDismiss,
        leftLabel = null,
        onLeftClick = null,
        rightLabel = "Done",
        onRightClick = onDismiss,
        modifier = Modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth(0.92f)
            .heightIn(max = 560.dp)
            .testTag("shortcuts_help_dialog"),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PcSectionCard("Touch Gestures") {
                ShortcutEntry("Two-finger tap", "Undo")
                PcRowDivider()
                ShortcutEntry("Three-finger tap", "Redo")
                PcRowDivider()
                ShortcutEntry("Three-finger swipe down", "Copy & Paste Menu")
                PcRowDivider()
                ShortcutEntry("Three-finger scrub", "Clear active layer")
                PcRowDivider()
                ShortcutEntry("Four-finger tap", "Toggle Full Screen")
                PcRowDivider()
                ShortcutEntry("Pinch & rotate", "Zoom, pan & rotate")
                PcRowDivider()
                ShortcutEntry("Hold stroke at end", "QuickShape line / shape")
            }

            PcSectionCard("Hardware Keyboard") {
                ShortcutEntry("B / S / E", "Brush / Smudge / Eraser")
                PcRowDivider()
                ShortcutEntry("Ctrl + Z / Shift + Z", "Undo / Redo")
                PcRowDivider()
                ShortcutEntry("Ctrl + C / X / V", "Copy / Cut / Paste")
                PcRowDivider()
                ShortcutEntry("[ / ]", "Decrease / Increase Brush Size")
                PcRowDivider()
                ShortcutEntry("V / M", "Transform / Selection")
                PcRowDivider()
                ShortcutEntry("L / C", "Layers / Color Panel")
                PcRowDivider()
                ShortcutEntry("Space + Drag or 0", "Fit Canvas to Screen")
            }
        }
    }
}

@Composable
private fun ShortcutEntry(keys: String, action: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(keys, color = Pc.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(action, color = Pc.TextSecondary, fontSize = 12.sp)
    }
}

@Composable
fun PageAssistBar(vm: CanvasViewModel) {
    val total = vm.layers.size.coerceAtLeast(1)
    val current = (vm.activeLayerIndex + 1).coerceIn(1, total)

    Row(
        Modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .shadow(14.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(Pc.PanelBg)
            .border(0.8.dp, Pc.Divider, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("page_assist_bar"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { vm.prevAssistPage() },
                enabled = current > 1,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Prev", color = if (current > 1) Pc.Accent else Pc.TextSecondary) }
            Text("Page $current / $total", color = Pc.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            TextButton(
                onClick = { vm.nextAssistPage() },
                enabled = current < total,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Next", color = if (current < total) Pc.Accent else Pc.TextSecondary) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.addLayer() }, modifier = Modifier.size(48.dp).testTag("page_assist_add")) {
                Icon(Icons.Rounded.Add, contentDescription = "Add Page", tint = Pc.Accent)
            }
            IconButton(onClick = { vm.togglePageAssist() }, modifier = Modifier.size(48.dp).testTag("page_assist_close")) {
                Icon(Icons.Rounded.Close, contentDescription = "Close Page Assist", tint = Pc.TextSecondary)
            }
        }
    }
}
