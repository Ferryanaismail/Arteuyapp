package io.github.artcraftandroid.procraft.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.artcraftandroid.procraft.engine.BrushLibraryCatalog
import io.github.artcraftandroid.procraft.engine.BrushStrokeSession
import io.github.artcraftandroid.procraft.engine.StrokePoint
import io.github.artcraftandroid.procraft.model.*
import kotlin.math.roundToInt

private enum class StudioSection(val label: String) {
    STROKE("Stroke Path"),
    STABILIZE("Stabilization"),
    TAPER("Taper"),
    SHAPE("Shape"),
    GRAIN("Grain"),
    WET_MIX("Wet Mix"),
    COLOR_DYNAMICS("Color Dynamics"),
    DYNAMICS("Dynamics"),
    ABOUT("About this brush"),
}

@Composable
fun BrushStudioModal(
    preset: BrushPreset,
    colorRgb: Int,
    onUpdate: (BrushPreset) -> Unit,
    onDeleteCustom: (() -> Unit)? = null,
    onClose: () -> Unit,
) {
    var draft by remember(preset.id) { mutableStateOf(preset) }
    var section by remember { mutableStateOf(StudioSection.STROKE) }
    var padTick by remember { mutableIntStateOf(0) }
    val padBitmap = remember { Bitmap.createBitmap(720, 420, Bitmap.Config.ARGB_8888) }
    val padImg = remember(padTick) { padBitmap.asImageBitmap() }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Pc.Background)
                .windowInsetsPadding(WindowInsets.systemBars)
                .testTag("brush_studio_modal"),
        ) {
            // ProCraft Studio Header: Cancel | Brush Studio — Name | Done
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .background(Pc.Bar)
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onClose,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Cancel", color = Pc.TextSecondary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }

                Column(
                    Modifier.weight(1f).padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Brush Studio",
                        color = Pc.TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                    Text(
                        draft.name,
                        color = Pc.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Button(
                    onClick = {
                        onUpdate(draft)
                        onClose()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Pc.Accent),
                    shape = RoundedCornerShape(9.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    modifier = Modifier.heightIn(min = 38.dp).testTag("brush_studio_done"),
                ) {
                    Text("Done", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            HorizontalDivider(color = Pc.Divider, thickness = 0.7.dp)

            val clearPad: () -> Unit = {
                padBitmap.eraseColor(android.graphics.Color.TRANSPARENT)
                padTick++
            }

            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(10.dp),
            ) {
                val wide = maxWidth >= 600.dp
                if (wide) {
                    // Authentic 3-Pane Procreate / ProCraft Brush Studio (Categories | Controls | Drawing Pad)
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StudioCategoryRail(
                            selected = section,
                            onSelect = { section = it },
                            modifier = Modifier.width(174.dp).fillMaxHeight(),
                        )
                        StudioControlsPane(
                            section = section,
                            draft = draft,
                            onDraftChange = { draft = it },
                            onDeleteCustom = onDeleteCustom,
                            onClose = onClose,
                            modifier = Modifier.weight(1.05f).fillMaxHeight(),
                        )
                        DrawingPadPane(
                            draft = draft,
                            colorRgb = colorRgb,
                            padBitmap = padBitmap,
                            padImg = padImg,
                            onStrokeStep = { padTick++ },
                            onClear = clearPad,
                            onPreviewSizeChange = { s -> draft = draft.copy(settings = draft.settings.copy(size = s)) },
                            modifier = Modifier.weight(0.95f).fillMaxHeight(),
                        )
                    }
                } else {
                    // Compact handheld layout: Drawing Pad on top + Categories | Controls below
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DrawingPadPane(
                            draft = draft,
                            colorRgb = colorRgb,
                            padBitmap = padBitmap,
                            padImg = padImg,
                            onStrokeStep = { padTick++ },
                            onClear = clearPad,
                            onPreviewSizeChange = { s -> draft = draft.copy(settings = draft.settings.copy(size = s)) },
                            modifier = Modifier.fillMaxWidth().height(168.dp),
                        )
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StudioCategoryRail(
                                selected = section,
                                onSelect = { section = it },
                                modifier = Modifier.width(126.dp).fillMaxHeight(),
                            )
                            StudioControlsPane(
                                section = section,
                                draft = draft,
                                onDraftChange = { draft = it },
                                onDeleteCustom = onDeleteCustom,
                                onClose = onClose,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioCategoryRail(
    selected: StudioSection,
    onSelect: (StudioSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sectionIcons = remember {
        mapOf(
            StudioSection.STROKE to LucideIcons.StudioStroke,
            StudioSection.STABILIZE to LucideIcons.StudioStabilize,
            StudioSection.TAPER to LucideIcons.StudioTaper,
            StudioSection.SHAPE to LucideIcons.StudioShape,
            StudioSection.GRAIN to LucideIcons.StudioGrain,
            StudioSection.WET_MIX to LucideIcons.StudioWet,
            StudioSection.COLOR_DYNAMICS to LucideIcons.StudioColor,
            StudioSection.DYNAMICS to LucideIcons.StudioDynamics,
            StudioSection.ABOUT to LucideIcons.StudioAbout,
        )
    }
    LazyColumn(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Pc.PanelBg)
            .border(0.6.dp, Pc.Divider, RoundedCornerShape(12.dp))
            .padding(vertical = 6.dp, horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(StudioSection.entries) { sec ->
            val sel = selected == sec
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (sel) Pc.RowSelected else Color.Transparent)
                    .clickable { onSelect(sec) }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = sectionIcons[sec] ?: LucideIcons.StudioStroke,
                    contentDescription = sec.label,
                    tint = if (sel) Pc.Accent else Pc.TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    sec.label,
                    color = if (sel) Pc.Accent else Pc.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DrawingPadPane(
    draft: BrushPreset,
    colorRgb: Int,
    padBitmap: Bitmap,
    padImg: androidx.compose.ui.graphics.ImageBitmap,
    onStrokeStep: () -> Unit,
    onClear: () -> Unit,
    onPreviewSizeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF141416))
            .border(0.8.dp, Pc.Divider, RoundedCornerShape(12.dp)),
    ) {
        // Pad Header bar
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF1D1D20))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(CanvasSpace.color(colorRgb))
                        .border(1.dp, Color(0x66FFFFFF), CircleShape),
                )
                Text("Drawing Pad", color = Color(0xFFEAEAEF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Clear Drawing Pad",
                color = Pc.Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onClear)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        HorizontalDivider(color = Color(0x1FFFFFFF), thickness = 0.6.dp)

        // Pad surface
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(draft.settings) {
                    var session: BrushStrokeSession? = null
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val sx = down.position.x / size.width.coerceAtLeast(1) * padBitmap.width
                        val sy = down.position.y / size.height.coerceAtLeast(1) * padBitmap.height
                        session = BrushStrokeSession(
                            tool = ToolKind.PAINT,
                            brush = draft.settings,
                            colorRgb = colorRgb,
                            canvasWidth = padBitmap.width,
                            canvasHeight = padBitmap.height,
                            alphaLock = false,
                            guide = GuideConfig(),
                            selectionMask = null,
                            targetBitmap = padBitmap,
                            baseLayerSnapshot = null,
                        ).apply { start(StrokePoint(sx, sy, 0.85f)) }
                        onStrokeStep()
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) {
                                session?.end()
                                onStrokeStep()
                                break
                            }
                            val mx = ch.position.x / size.width.coerceAtLeast(1) * padBitmap.width
                            val my = ch.position.y / size.height.coerceAtLeast(1) * padBitmap.height
                            session?.move(StrokePoint(mx, my, ch.pressure.coerceIn(0.15f, 1f)))
                            onStrokeStep()
                            ch.consume()
                        }
                    }
                },
        ) {
            Image(
                bitmap = padImg,
                contentDescription = "Brush test drawing pad",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                "Test stroke here",
                color = Color(0x44FFFFFF),
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            )
        }

        // Quick preview size bar
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A1D))
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Preview Size", color = Color(0xFF9C9CA3), fontSize = 11.sp)
            PcSlider(
                value = draft.settings.size,
                valueRange = 0.02f..1f,
                onValueChange = onPreviewSizeChange,
                modifier = Modifier.weight(1f),
            )
            Text("${(draft.settings.size * 100).roundToInt()}%", color = Color(0xFFEAEAEF), fontSize = 11.sp, modifier = Modifier.width(34.dp))
        }
    }
}

@Composable
private fun StudioControlsPane(
    section: StudioSection,
    draft: BrushPreset,
    onDraftChange: (BrushPreset) -> Unit,
    onDeleteCustom: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Pc.PanelBg)
            .border(0.6.dp, Pc.Divider, RoundedCornerShape(12.dp))
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(section.label, color = Pc.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

        when (section) {
            StudioSection.STROKE -> {
                PcSectionCard("Stroke Properties") {
                    StudioSliderRow("Spacing", draft.settings.spacing, 0.03f..0.7f) {
                        onDraftChange(draft.copy(settings = draft.settings.copy(spacing = it)))
                    }
                    PcRowDivider()
                    StudioSliderRow("Jitter", draft.settings.scatter) {
                        onDraftChange(draft.copy(settings = draft.settings.copy(scatter = it)))
                    }
                    PcRowDivider()
                    StudioSliderRow("Falloff / Opacity", draft.settings.opacity, 0.05f..1f) {
                        onDraftChange(draft.copy(settings = draft.settings.copy(opacity = it)))
                    }
                }
            }

            StudioSection.STABILIZE -> {
                PcSectionCard("StreamLine") {
                    StudioSliderRow("Amount", draft.settings.streamline) {
                        onDraftChange(draft.copy(settings = draft.settings.copy(streamline = it)))
                    }
                }
                PcSectionCard("Shape Assistance") {
                    PcToggleRow(
                        label = "QuickShape Recognition",
                        subtitle = "Hold stroke at end to snap lines & shapes",
                        checked = draft.settings.quickShapeEnabled,
                    ) {
                        onDraftChange(draft.copy(settings = draft.settings.copy(quickShapeEnabled = it)))
                    }
                }
            }

            StudioSection.TAPER -> {
                PcSectionCard("Pressure & Touch Taper") {
                    StudioSliderRow("Start Taper", draft.settings.taperStart) {
                        onDraftChange(draft.copy(settings = draft.settings.copy(taperStart = it)))
                    }
                    PcRowDivider()
                    StudioSliderRow("End Taper", draft.settings.taperEnd) {
                        onDraftChange(draft.copy(settings = draft.settings.copy(taperEnd = it)))
                    }
                }
            }

            StudioSection.SHAPE -> {
                PcSectionCard("Shape Source") {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BrushShapeKind.entries.chunked(2).forEach { rowKinds ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                rowKinds.forEach { kind ->
                                    val sel = draft.settings.shapeKind == kind
                                    Row(
                                        Modifier
                                            .weight(1f)
                                            .heightIn(min = 44.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (sel) Pc.Accent else Pc.PanelSurface)
                                            .clickable { onDraftChange(draft.copy(settings = draft.settings.copy(shapeKind = kind))) }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Icon(
                                            shapeIconFor(kind),
                                            contentDescription = kind.label,
                                            tint = if (sel) Color.White else Pc.TextSecondary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Text(
                                            kind.label,
                                            color = if (sel) Color.White else Pc.TextPrimary,
                                            fontSize = 11.sp,
                                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                PcSectionCard("Shape Behavior") {
                    StudioSliderRow("Hardness", draft.settings.hardness) {
                        onDraftChange(draft.copy(settings = draft.settings.copy(hardness = it)))
                    }
                    PcRowDivider()
                    StudioSliderRow("Roundness", draft.settings.roundness, 0.15f..1f) {
                        onDraftChange(draft.copy(settings = draft.settings.copy(roundness = it)))
                    }
                    PcRowDivider()
                    val angleNorm = ((draft.settings.angleDeg + 90f) / 180f).coerceIn(0f, 1f)
                    StudioSliderRow(
                        label = "Rotation",
                        value = angleNorm,
                        valueText = "${draft.settings.angleDeg.roundToInt()}°",
                    ) { f ->
                        onDraftChange(draft.copy(settings = draft.settings.copy(angleDeg = f * 180f - 90f)))
                    }
                }
            }

            StudioSection.GRAIN -> {
                PcSectionCard("Grain Behavior") {
                    Row(
                        Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val moving = draft.settings.grainMoving
                        GrainModeButton(
                            icon = LucideIcons.GrainMoving,
                            label = "Moving",
                            selected = moving,
                            modifier = Modifier.weight(1f),
                        ) { onDraftChange(draft.copy(settings = draft.settings.copy(grainMoving = true))) }
                        GrainModeButton(
                            icon = LucideIcons.GrainTexturized,
                            label = "Texturized",
                            selected = !moving,
                            modifier = Modifier.weight(1f),
                        ) { onDraftChange(draft.copy(settings = draft.settings.copy(grainMoving = false))) }
                    }
                    PcRowDivider()
                    StudioSliderRow("Depth", draft.settings.grainAmount) {
                        onDraftChange(draft.copy(settings = draft.settings.copy(grainAmount = it)))
                    }
                    PcRowDivider()
                    StudioSliderRow(
                        label = "Scale",
                        value = ((draft.settings.grainScale - 0.3f) / 2.2f).coerceIn(0f, 1f),
                        valueText = "${(draft.settings.grainScale * 100).roundToInt()}%",
                    ) { f ->
                        onDraftChange(draft.copy(settings = draft.settings.copy(grainScale = 0.3f + f * 2.2f)))
                    }
                }
            }

            StudioSection.WET_MIX -> {
                PcSectionCard("Paint Mix") {
                    StudioSliderRow("Dilution / Pull", draft.settings.wetMix) {
                        onDraftChange(draft.copy(settings = draft.settings.copy(wetMix = it)))
                    }
                }
                Text(
                    "Wet Mix blends paint with underlying canvas colors along the stroke direction.",
                    color = Pc.TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            StudioSection.COLOR_DYNAMICS -> {
                PcSectionCard("Stamp Color Jitter") {
                    StudioSliderRow("Hue Jitter", draft.settings.hueJitter) {
                        onDraftChange(draft.copy(settings = draft.settings.copy(hueJitter = it)))
                    }
                    PcRowDivider()
                    StudioSliderRow("Saturation Jitter", draft.settings.saturationJitter) {
                        onDraftChange(draft.copy(settings = draft.settings.copy(saturationJitter = it)))
                    }
                }
            }

            StudioSection.DYNAMICS -> {
                PcSectionCard("Stylus Pressure") {
                    StudioSliderRow("Pressure Size", draft.settings.pressureSize) {
                        onDraftChange(draft.copy(settings = draft.settings.copy(pressureSize = it)))
                    }
                    PcRowDivider()
                    StudioSliderRow("Pressure Opacity", draft.settings.pressureOpacity) {
                        onDraftChange(draft.copy(settings = draft.settings.copy(pressureOpacity = it)))
                    }
                }
                PcSectionCard("Size Jitter") {
                    StudioSliderRow("Jitter", draft.settings.jitter) {
                        onDraftChange(draft.copy(settings = draft.settings.copy(jitter = it)))
                    }
                }
            }

            StudioSection.ABOUT -> {
                PcSectionCard("Brush Name") {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PcInlineTextField(
                            value = draft.name,
                            onValueChange = { onDraftChange(draft.copy(name = it)) },
                            placeholder = "Brush Name",
                            inputTag = "brush_name_input",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            if (draft.isCustom) "Custom Brush Preset" else "Built-in ProCraft Brush",
                            color = Pc.TextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                }
                PcSectionCard("Reset & Manage") {
                    PcNavRow(
                        label = "Reset all settings",
                        icon = PcIcons.TransformReset,
                        showChevron = false,
                        iconTint = Pc.Accent,
                    ) {
                        val original = rememberOrLookupDefault(draft.id)
                        if (original != null) {
                            onDraftChange(draft.copy(settings = original.settings))
                        } else {
                            onDraftChange(draft.copy(settings = BrushSettings()))
                        }
                    }
                    if (draft.isCustom && onDeleteCustom != null) {
                        PcRowDivider()
                        PcNavRow(
                            label = "Delete Custom Brush",
                            icon = Icons.Rounded.Delete,
                            destructive = true,
                            showChevron = false,
                        ) {
                            onDeleteCustom()
                            onClose()
                        }
                    }
                }
            }
        }
    }
}

private fun rememberOrLookupDefault(id: String): BrushPreset? =
    BrushLibraryCatalog.defaultBrushSets().asSequence().flatMap { it.brushes.asSequence() }.firstOrNull { it.id == id }

private fun shapeIconFor(kind: BrushShapeKind): ImageVector = when (kind) {
    BrushShapeKind.ROUND -> LucideIcons.ShapeCircle
    BrushShapeKind.SOFT_AIR -> LucideIcons.AdjustBlur
    BrushShapeKind.FLAT_BRISTLE -> LucideIcons.ShapeRect
    BrushShapeKind.GRAINY_CHALK -> LucideIcons.StudioGrain
    BrushShapeKind.CALLIGRAPHY_FLAT -> LucideIcons.ShapeQuad
    BrushShapeKind.SPECKLE_SPRAY -> LucideIcons.AdjustNoise
    BrushShapeKind.WATERCOLOR_BLOOM -> LucideIcons.StudioWet
    BrushShapeKind.INK_NIB -> LucideIcons.StudioStylus
}

@Composable
private fun GrainModeButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Pc.Accent else Pc.PanelSurface)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = label, tint = if (selected) Color.White else Pc.TextPrimary, modifier = Modifier.size(22.dp))
        Text(label, color = if (selected) Color.White else Pc.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StudioSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    valueText: String = "${(value * 100).roundToInt()}%",
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Pc.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(valueText, color = Pc.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        PcSlider(
            value = value.coerceIn(range.start, range.endInclusive),
            valueRange = range,
            onValueChange = onValueChange,
        )
    }
}
