package io.github.artcraftandroid.procraft.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import io.github.artcraftandroid.procraft.model.AdjustmentKind
import io.github.artcraftandroid.procraft.model.Panel
import io.github.artcraftandroid.procraft.model.PickLoupeState
import io.github.artcraftandroid.procraft.model.ToolKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private fun Modifier.noRippleTap(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = onClick)
}

@Composable
fun TopBar(vm: CanvasViewModel, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(Pc.BarHeight)
            .background(Pc.Bar),
    ) {
        val fit = Pc.barFit(maxWidth)
        Row(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = fit.margin),
            horizontalArrangement = Arrangement.spacedBy(fit.actionGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(Pc.GalleryWidth, Pc.BarButtonHeight)
                    .testTag("top_gallery_button")
                    .noRippleTap { vm.closeArtwork() },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    "Gallery",
                    color = Pc.BarText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            ActionButton(
                icon = PcIcons.Actions,
                label = "Actions",
                width = fit.button,
                selected = vm.panel == Panel.ACTIONS,
                testTag = "top_actions_button",
            ) { vm.togglePanel(Panel.ACTIONS) }

            ActionButton(
                icon = PcIcons.Adjustments,
                label = "Adjustments",
                width = fit.button,
                selected = vm.panel == Panel.ADJUSTMENTS || vm.adjustingKind != null,
                testTag = "top_adjustments_button",
            ) { vm.togglePanel(Panel.ADJUSTMENTS) }

            val hasSel = vm.selectionMask?.let { !it.isEmpty() } == true
            ActionButton(
                icon = PcIcons.Selection,
                label = "Selection",
                width = fit.button,
                selected = vm.selecting || hasSel,
                glyph = 32.dp,
                testTag = "top_selection_button",
            ) { vm.toggleSelecting() }

            ActionButton(
                icon = PcIcons.Transform,
                label = "Transform",
                width = fit.button,
                selected = vm.transforming,
                glyph = 40.dp,
                testTag = "top_transform_button",
            ) { vm.toggleTransform() }
        }

        val panelOpen = vm.panel == Panel.LAYERS || vm.panel == Panel.COLOR || vm.transforming || vm.selecting || vm.adjustingKind != null
        Row(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = fit.margin),
            horizontalArrangement = Arrangement.spacedBy(fit.toolGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton(
                icon = if (vm.adjustingKind == AdjustmentKind.LIQUIFY || vm.adjustingKind == AdjustmentKind.CLONE) PcIcons.BrushPaintedOn else PcIcons.Brush,
                label = "Paint",
                width = fit.button,
                selected = !panelOpen && vm.tool == ToolKind.PAINT,
                testTag = "top_paint_button",
            ) { vm.toolPressed(ToolKind.PAINT) }

            ToolButton(
                icon = PcIcons.Smudge,
                label = "Smudge",
                width = fit.button,
                selected = !panelOpen && vm.tool == ToolKind.SMUDGE,
                testTag = "top_smudge_button",
            ) { vm.toolPressed(ToolKind.SMUDGE) }

            ToolButton(
                icon = PcIcons.Erase,
                label = "Erase",
                width = fit.button,
                selected = !panelOpen && vm.tool == ToolKind.ERASE,
                testTag = "top_erase_button",
            ) { vm.toolPressed(ToolKind.ERASE) }

            ToolButton(
                icon = PcIcons.Layers,
                label = "Layers",
                width = fit.button,
                selected = vm.panel == Panel.LAYERS,
                testTag = "top_layers_button",
            ) { vm.togglePanel(Panel.LAYERS) }

            ColorButton(vm, fit.button)
        }
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    width: Dp,
    selected: Boolean = false,
    glyph: Dp = 34.dp,
    testTag: String,
    onClick: () -> Unit,
) {
    val lit = animateHighlight(selected)
    val k = Pc.barGlyphScale(width)
    Box(
        Modifier
            .size(width, Pc.BarButtonHeight)
            .testTag(testTag)
            .noRippleTap(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(Pc.ActionDiscSize * k)
                .clip(CircleShape)
                .background(lerp(Pc.ActionDisc, Pc.BarGlyphSelected, lit)),
        )
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = lerp(Pc.ActionGlyph, Color.White, lit),
            modifier = Modifier.requiredSize(glyph * k),
        )
    }
}

@Composable
private fun ToolButton(
    icon: ImageVector,
    label: String,
    width: Dp,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(width, Pc.BarButtonHeight)
            .testTag(testTag)
            .noRippleTap(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = lerp(Pc.BarGlyph, Pc.BarGlyphSelected, animateHighlight(selected)),
            modifier = Modifier.requiredSize(42.dp * Pc.barGlyphScale(width)),
        )
    }
}

@Composable
private fun ColorButton(vm: CanvasViewModel, width: Dp) {
    val rgb = vm.colorRgb
    val ringed = if (Pc.light) vm.brightness > 0.85f else vm.brightness < 0.35f
    var buttonPosInRoot by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()

    Box(
        Modifier
            .size(width, Pc.BarButtonHeight)
            .testTag("top_color_button")
            .onGloballyPositioned { buttonPosInRoot = it.positionInRoot() }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dragged = false
                    var holdTriggered = false
                    val holdJob = scope.launch {
                        delay(450)
                        if (!dragged) {
                            holdTriggered = true
                            vm.swapPreviousColor()
                        }
                    }
                    var lastPos = down.position
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) {
                            holdJob.cancel()
                            if (dragged) {
                                val dropInCanvasArea = vm.colorDroppingPos
                                vm.colorDroppingPos = null
                                if (dropInCanvasArea != null) {
                                    vm.colorDropAt(screenAreaToCanvas(vm, dropInCanvasArea))
                                }
                            } else if (!holdTriggered) {
                                vm.togglePanel(Panel.COLOR)
                            }
                            break
                        }
                        val delta = ch.position - down.position
                        if (!dragged && hypot(delta.x, delta.y) > 22f) {
                            dragged = true
                            holdJob.cancel()
                        }
                        if (dragged) {
                            // Map root y below TopBar into canvas area offset
                            val barPx = with(this) { Pc.BarHeight.toPx() }
                            val dropPt = Offset(
                                buttonPosInRoot.x + ch.position.x,
                                (buttonPosInRoot.y + ch.position.y - barPx).coerceAtLeast(0f),
                            )
                            vm.colorDroppingPos = dropPt
                            ch.consume()
                        }
                        lastPos = ch.position
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val dot = Modifier
            .size(Pc.ColorDot * Pc.barGlyphScale(width))
            .clip(CircleShape)
            .background(CanvasSpace.color(rgb))
        Box(if (ringed) dot.border(1.dp, Pc.ColorRing, CircleShape) else dot)
    }
}

private fun hypot(dx: Float, dy: Float): Float = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

fun screenAreaToCanvas(vm: CanvasViewModel, areaPt: Offset): Offset {
    val art = vm.currentArtwork ?: return Offset.Zero
    val cx = vm.canvasAreaSize.width * 0.5f + vm.viewPan.x
    val cy = vm.canvasAreaSize.height * 0.5f + vm.viewPan.y
    val dx = (areaPt.x - cx) / vm.viewZoom
    val dy = (areaPt.y - cy) / vm.viewZoom
    val rad = -Math.toRadians(vm.viewRotationDeg.toDouble())
    val rx = (dx * kotlin.math.cos(rad) - dy * kotlin.math.sin(rad)).toFloat()
    val ry = (dx * kotlin.math.sin(rad) + dy * kotlin.math.cos(rad)).toFloat()
    return Offset(rx + art.width * 0.5f, ry + art.height * 0.5f)
}

@Composable
fun SideBar(
    vm: CanvasViewModel,
    right: Boolean,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scale = vm.gestureSettings.sideBarScale.coerceIn(0.45f, 1.0f)
    val verticalBias = vm.gestureSettings.sideBarVerticalBias.coerceIn(0.02f, 0.98f)
    val moveMode = vm.sideBarMoveMode

    BoxWithConstraints(modifier.fillMaxHeight()) {
        val baseTrackHeight = Pc.sideTrackHeight(maxHeight)
        val trackHeight = (baseTrackHeight * scale).coerceAtLeast(64.dp)
        val barWidth = Pc.SideBarWidth * (0.82f + 0.18f * scale)
        val sliderWidth = Pc.SliderWidth * (0.80f + 0.20f * scale)
        val extraMoveBadge = if (moveMode) 18.dp else 0.dp
        val totalHeight = (trackHeight * 2 + 162.dp + extraMoveBadge).coerceAtMost(maxHeight)
        val corner = Pc.SideBarCorner
        val availableRangePx = with(density) { (maxHeight - totalHeight).coerceAtLeast(1.dp).toPx() }
        val alignBias = BiasAlignment(
            horizontalBias = if (right) 1f else -1f,
            verticalBias = (verticalBias * 2f - 1f).coerceIn(-1f, 1f),
        )

        Column(
            Modifier
                .align(alignBias)
                .width(barWidth)
                .height(totalHeight)
                .clip(
                    if (right) RoundedCornerShape(topStart = corner, bottomStart = corner)
                    else RoundedCornerShape(topEnd = corner, bottomEnd = corner),
                )
                .background(Pc.SideBar)
                .then(
                    if (moveMode) {
                        Modifier.border(
                            1.8.dp,
                            Pc.Accent,
                            if (right) RoundedCornerShape(topStart = corner, bottomStart = corner)
                            else RoundedCornerShape(topEnd = corner, bottomEnd = corner),
                        )
                    } else Modifier,
                )
                .pointerInput(Unit) {
                    var tapCount = 0
                    var lastTapUpMs = 0L
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val downPos = down.position
                        val downTime = System.currentTimeMillis()
                        var moved = false
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull { it.id == down.id }
                            if (ch == null || !ch.pressed) break
                            if ((ch.position - downPos).getDistance() > slop) {
                                moved = true
                            }
                        }
                        val upTime = System.currentTimeMillis()
                        if (!moved && (upTime - downTime) < 320L) {
                            if (upTime - lastTapUpMs <= 500L) {
                                tapCount++
                            } else {
                                tapCount = 1
                            }
                            lastTapUpMs = upTime
                            if (tapCount >= 3) {
                                tapCount = 0
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.sideBarMoveMode = !vm.sideBarMoveMode
                                vm.showNotice(
                                    if (vm.sideBarMoveMode) "Drag sidebar up/down • Tap canvas 1x when done"
                                    else "Sidebar locked",
                                )
                            }
                        } else {
                            tapCount = 0
                        }
                    }
                }
                .then(
                    if (moveMode) {
                        Modifier.pointerInput(availableRangePx) {
                            detectVerticalDragGestures { change, dragY ->
                                change.consume()
                                val deltaBias = dragY / availableRangePx
                                vm.updateGestureSettings { s ->
                                    s.copy(sideBarVerticalBias = (s.sideBarVerticalBias + deltaBias).coerceIn(0.02f, 0.98f))
                                }
                            }
                        }
                    } else Modifier,
                )
                .padding(horizontal = 4.dp, vertical = 6.dp)
                .testTag("canvas_sidebar"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            if (moveMode) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Pc.Accent)
                        .padding(vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Move", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }

            SideVerticalSlider(
                value = vm.brushSize,
                onValueChange = { vm.brushSize = it },
                notches = vm.sizeNotches,
                width = sliderWidth,
                height = trackHeight,
                enabled = !moveMode,
                label = "Brush Size",
                testTag = "side_size_slider",
            )

            // Modify square button: tap enables Eyedropper next touch
            Box(
                Modifier
                    .size(40.dp, 38.dp)
                    .testTag("side_modify_button")
                    .pointerInput(moveMode) {
                        if (!moveMode) {
                            detectTapGestures(
                                onTap = {
                                    vm.pickNext = !vm.pickNext
                                    if (vm.pickNext) vm.showNotice("Eyedropper: tap canvas")
                                },
                                onLongPress = {
                                    vm.quickMenuCenter = Offset(vm.canvasAreaSize.width * 0.5f, vm.canvasAreaSize.height * 0.5f)
                                },
                            )
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(Pc.ModifySize)
                        .drawBehind {
                            val w = Pc.ModifyStroke.toPx()
                            drawRoundRect(
                                if (vm.pickNext) Pc.Accent else Pc.SideGlyph,
                                topLeft = Offset(w / 2f, w / 2f),
                                size = Size(size.width - w, size.height - w),
                                cornerRadius = CornerRadius(Pc.ModifyCorner.toPx() - w / 2f),
                                style = Stroke(w),
                            )
                        },
                )
            }

            SideVerticalSlider(
                value = vm.brushOpacity,
                onValueChange = { vm.brushOpacity = it },
                notches = emptyList(),
                width = sliderWidth,
                height = trackHeight,
                enabled = !moveMode,
                label = "Brush Opacity",
                testTag = "side_opacity_slider",
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(40.dp, 36.dp)
                        .testTag("side_undo_button")
                        .noRippleTap(enabled = vm.canUndo && !moveMode) { vm.undo() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        PcIcons.Undo,
                        contentDescription = "Undo",
                        tint = if (vm.canUndo) Pc.SideGlyph else Pc.SideGlyphMuted,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Box(
                    Modifier
                        .size(40.dp, 36.dp)
                        .testTag("side_redo_button")
                        .noRippleTap(enabled = vm.canRedo && !moveMode) { vm.redo() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        PcIcons.Redo,
                        contentDescription = "Redo",
                        tint = if (vm.canRedo) Pc.SideGlyph else Pc.SideGlyphMuted,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SideVerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    notches: List<Float>,
    width: Dp = Pc.SliderWidth,
    height: Dp,
    enabled: Boolean = true,
    label: String,
    testTag: String,
) {
    var draggingValue by remember { mutableStateOf<Float?>(null) }
    val shown = draggingValue ?: value

    Box(
        Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(Pc.SliderCorner))
            .background(Color(0x44000000))
            .border(1.dp, Pc.SliderEdge, RoundedCornerShape(Pc.SliderCorner))
            .testTag(testTag)
            .pointerInput(notches, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun posToVal(y: Float): Float {
                        val raw = (1f - (y / size.height.toFloat())).coerceIn(0.02f, 1f)
                        val snap = notches.firstOrNull { abs(it - raw) < 0.03f }
                        return snap ?: raw
                    }
                    draggingValue = posToVal(down.position.y)
                    onValueChange(draggingValue!!)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) {
                            draggingValue = null
                            break
                        }
                        val next = posToVal(ch.position.y)
                        draggingValue = next
                        onValueChange(next)
                        ch.consume()
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Fill bar from bottom up
            val fillH = size.height * shown.coerceIn(0f, 1f)
            drawRect(
                color = Color(0x55FFFFFF),
                topLeft = Offset(0f, size.height - fillH),
                size = Size(size.width, fillH),
            )
            // Notches
            notches.forEach { n ->
                val ny = size.height * (1f - n)
                drawLine(
                    color = Pc.Accent.copy(alpha = 0.85f),
                    start = Offset(4f, ny),
                    end = Offset(size.width - 4f, ny),
                    strokeWidth = 2.5f,
                )
            }
            // Thumb pill
            val thumbY = (size.height * (1f - shown)).coerceIn(10f, size.height - 10f)
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(4f, thumbY - 6f),
                size = Size(size.width - 8f, 12f),
                cornerRadius = CornerRadius(6f),
            )
        }
        if (draggingValue != null) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            ) {
                Text("${(shown * 100).roundToInt()}%", color = Color.White, fontSize = 9.sp)
            }
        }
    }
}

@Composable
fun EyedropperLoupe(state: PickLoupeState) {
    Box(
        Modifier
            .offset { IntOffset((state.screenPos.x - 42.dp.toPx()).roundToInt(), (state.screenPos.y - 105.dp.toPx()).roundToInt()) }
            .size(84.dp)
            .clip(CircleShape)
            .border(5.dp, Color.White, CircleShape)
            .border(1.dp, Color.Black, CircleShape),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().background(CanvasSpace.color(state.colorRgb)))
            Box(Modifier.weight(1f).fillMaxWidth().background(CanvasSpace.color(state.prevColorRgb)))
        }
    }
}
