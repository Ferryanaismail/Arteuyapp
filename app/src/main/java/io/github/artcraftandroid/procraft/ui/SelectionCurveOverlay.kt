package io.github.artcraftandroid.procraft.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesomeMotion
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import io.github.artcraftandroid.procraft.canvas.canInsertSelectionKeyframe
import io.github.artcraftandroid.procraft.canvas.generateInBetweenKeyframes
import io.github.artcraftandroid.procraft.canvas.resolveGroupedPaintTrack
import io.github.artcraftandroid.procraft.canvas.resolveTweenContext
import io.github.artcraftandroid.procraft.canvas.updateSelectionCurvePreference
import io.github.artcraftandroid.procraft.model.CubicBezierControl
import io.github.artcraftandroid.procraft.model.TweenCurveKind
import java.util.Locale
import kotlin.math.hypot

@Composable
fun SelectionCurveOverlayPanel(
    vm: CanvasViewModel,
    modifier: Modifier = Modifier,
) {
    val activeCurve = vm.selectionTweenCurve
    val bezier = vm.selectionCubicBezier
    val activeTrack = vm.resolveGroupedPaintTrack()
    val trackKeys = remember(vm.motionKeyframes, activeTrack?.id) {
        if (activeTrack == null) emptyList()
        else vm.motionKeyframes.filter { it.trackId == activeTrack.id }.sortedBy { it.frameIndex }
    }
    val curF = vm.animationState.currentFrameIndex
    val canTween = vm.canInsertSelectionKeyframe()

    val animProgress by rememberInfiniteTransition(label = "curve_dot").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "curve_t",
    )

    Column(
        modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .shadow(16.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(Pc.PanelBg)
            .border(0.9.dp, Pc.Accent.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("selection_curve_overlay"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Header Bar
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Rounded.Timeline,
                    contentDescription = "Transition Curve",
                    tint = Pc.Accent,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    "Transition Curve",
                    color = Pc.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                val keyBadge = if (trackKeys.isEmpty()) "F${curF + 1}" else "${trackKeys.size} Keys"
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Pc.RowSelected)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(keyBadge, color = Pc.Accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            IconButton(
                onClick = { vm.selectionCurveOverlayOpen = false },
                modifier = Modifier.size(32.dp).testTag("close_curve_overlay_button"),
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "Close Curve Overlay", tint = Pc.TextSecondary, modifier = Modifier.size(17.dp))
            }
        }

        // Interactive Graph + Info Row
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InteractiveCurveCanvas(
                curve = activeCurve,
                bezier = bezier,
                animT = animProgress,
                onBezierChange = { nextB ->
                    vm.updateSelectionCurvePreference(TweenCurveKind.CUBIC_BEZIER, nextB)
                },
                modifier = Modifier
                    .size(width = 132.dp, height = 96.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Pc.PanelSurface)
                    .border(0.7.dp, Pc.Divider, RoundedCornerShape(10.dp))
                    .testTag("interactive_curve_canvas"),
            )

            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    activeCurve.label,
                    color = Pc.Accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (activeCurve == TweenCurveKind.CUBIC_BEZIER) {
                    Text(
                        String.format(
                            Locale.US,
                            "cubic-bezier(%.2f, %.2f, %.2f, %.2f)",
                            bezier.x1,
                            bezier.y1,
                            bezier.x2,
                            bezier.y2,
                        ),
                        color = Pc.TextSecondary,
                        fontSize = 10.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        BezierPresetChip("Smooth") {
                            vm.updateSelectionCurvePreference(
                                TweenCurveKind.CUBIC_BEZIER,
                                CubicBezierControl(0.42f, 0f, 0.58f, 1f),
                            )
                        }
                        BezierPresetChip("Snappy") {
                            vm.updateSelectionCurvePreference(
                                TweenCurveKind.CUBIC_BEZIER,
                                CubicBezierControl(0.16f, 1f, 0.30f, 1f),
                            )
                        }
                        BezierPresetChip("Pop") {
                            vm.updateSelectionCurvePreference(
                                TweenCurveKind.CUBIC_BEZIER,
                                CubicBezierControl(0.34f, 1.35f, 0.64f, 1f),
                            )
                        }
                    }
                } else {
                    Text(
                        when (activeCurve) {
                            TweenCurveKind.LINEAR -> "Constant speed from key A to key B."
                            TweenCurveKind.EASE_IN -> "Starts slow, accelerates into end pose."
                            TweenCurveKind.EASE_OUT -> "Starts fast, settles smoothly at end pose."
                            TweenCurveKind.EASE_IN_OUT -> "Smooth acceleration and deceleration."
                            TweenCurveKind.OVERSHOOT -> "Goes slightly past target then settles."
                            TweenCurveKind.BOUNCE -> "Rebounds at the target pose."
                            TweenCurveKind.SPRING -> "Damped spring oscillation around target."
                            TweenCurveKind.STEP -> "Holds pose until final frame."
                            TweenCurveKind.CUBIC_BEZIER -> ""
                        },
                        color = Pc.TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = {
                            if (!canTween) {
                                vm.showNotice("Select a Group Track to interpolate")
                                return@Button
                            }
                            val prevKey = trackKeys.lastOrNull { it.frameIndex < curF }
                            if (prevKey != null) {
                                val (ctxStart, ctxEnd) = vm.resolveTweenContext()
                                vm.generateInBetweenKeyframes(
                                    startFrame = prevKey.frameIndex,
                                    endFrame = curF,
                                    startState = ctxStart,
                                    endState = ctxEnd,
                                    curve = activeCurve,
                                    bezier = bezier,
                                )
                            } else {
                                vm.inBetweenDialogOpen = true
                            }
                        },
                        enabled = canTween,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Pc.Accent,
                            disabledContainerColor = Pc.PanelSurface,
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .heightIn(min = 34.dp)
                            .testTag("curve_apply_tween_button"),
                    ) {
                        Icon(
                            Icons.Rounded.AutoAwesomeMotion,
                            contentDescription = "Interpolate",
                            tint = if (canTween) Color.White else Pc.TextSecondary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Tween",
                            color = if (canTween) Color.White else Pc.TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    Box(
                        Modifier
                            .heightIn(min = 34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Pc.PanelSurface)
                            .border(0.6.dp, Pc.Divider, RoundedCornerShape(8.dp))
                            .clickable { vm.inBetweenDialogOpen = true }
                            .padding(horizontal = 9.dp, vertical = 6.dp)
                            .testTag("curve_more_options_button"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Range...", color = Pc.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // Scrollable Curve Preset Chips
        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            items(TweenCurveKind.entries) { curve ->
                val sel = activeCurve == curve
                Column(
                    Modifier
                        .width(74.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (sel) Pc.RowSelected else Pc.PanelSurface)
                        .border(
                            width = if (sel) 1.4.dp else 0.6.dp,
                            color = if (sel) Pc.Accent else Pc.Divider,
                            shape = RoundedCornerShape(9.dp),
                        )
                        .clickable {
                            vm.updateSelectionCurvePreference(curve, bezier)
                        }
                        .padding(horizontal = 6.dp, vertical = 6.dp)
                        .testTag("overlay_curve_${curve.name.lowercase()}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    CurvePreviewGraph(
                        curve = curve,
                        bezier = bezier,
                        accent = if (sel) Pc.Accent else Pc.TextSecondary,
                        modifier = Modifier.fillMaxWidth().height(32.dp),
                    )
                    Text(
                        curve.label,
                        color = if (sel) Pc.Accent else Pc.TextPrimary,
                        fontSize = 10.sp,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun BezierPresetChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Pc.PanelSurface)
            .border(0.6.dp, Pc.Divider, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(label, color = Pc.TextPrimary, fontSize = 9.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun InteractiveCurveCanvas(
    curve: TweenCurveKind,
    bezier: CubicBezierControl,
    animT: Float,
    onBezierChange: (CubicBezierControl) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestBezier by rememberUpdatedState(bezier)
    val latestCurve by rememberUpdatedState(curve)
    var draggingHandle by remember { mutableIntStateOf(0) } // 1 = P1, 2 = P2

    val gridCol = Pc.Divider
    val accent = Pc.Accent
    val handle2Col = Pc.Warning

    Canvas(
        modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { offset ->
                    val pad = 14.dp.toPx()
                    val w = (size.width - pad * 2f).coerceAtLeast(1f)
                    val h = (size.height - pad * 2f).coerceAtLeast(1f)
                    val b = latestBezier
                    val p1 = Offset(pad + b.x1 * w, pad + h - b.y1 * h)
                    val p2 = Offset(pad + b.x2 * w, pad + h - b.y2 * h)
                    val d1 = hypot(offset.x - p1.x, offset.y - p1.y)
                    val d2 = hypot(offset.x - p2.x, offset.y - p2.y)
                    draggingHandle = if (d1 <= d2) 1 else 2
                },
                onDragEnd = { draggingHandle = 0 },
                onDragCancel = { draggingHandle = 0 },
                onDrag = { change, _ ->
                    change.consume()
                    val pad = 14.dp.toPx()
                    val w = (size.width - pad * 2f).coerceAtLeast(1f)
                    val h = (size.height - pad * 2f).coerceAtLeast(1f)
                    val nx = ((change.position.x - pad) / w).coerceIn(0f, 1f)
                    val ny = ((pad + h - change.position.y) / h).coerceIn(-0.4f, 1.4f)
                    val cur = if (latestCurve == TweenCurveKind.CUBIC_BEZIER) latestBezier else CubicBezierControl(0.42f, 0f, 0.58f, 1f)
                    val updated = if (draggingHandle == 1) {
                        cur.copy(x1 = nx, y1 = ny)
                    } else {
                        cur.copy(x2 = nx, y2 = ny)
                    }
                    onBezierChange(updated)
                },
            )
        },
    ) {
        val pad = 14.dp.toPx()
        val w = (size.width - pad * 2f).coerceAtLeast(1f)
        val h = (size.height - pad * 2f).coerceAtLeast(1f)
        val startPt = Offset(pad, pad + h)
        val endPt = Offset(pad + w, pad)

        // Box & diagonal reference
        drawRect(gridCol.copy(alpha = 0.4f), topLeft = Offset(pad, pad), size = androidx.compose.ui.geometry.Size(w, h), style = Stroke(1f))
        drawLine(gridCol.copy(alpha = 0.5f), startPt, endPt, strokeWidth = 1f)

        // Curve path
        val steps = 36
        val path = Path()
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val v = curve.evaluate(t, bezier).coerceIn(-0.35f, 1.35f)
            val x = pad + t * w
            val y = pad + h - v * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = accent, style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round))

        // Control handles when Cubic Bezier is selected
        if (curve == TweenCurveKind.CUBIC_BEZIER) {
            val p1 = Offset(pad + bezier.x1 * w, pad + h - bezier.y1 * h)
            val p2 = Offset(pad + bezier.x2 * w, pad + h - bezier.y2 * h)
            drawLine(accent.copy(alpha = 0.65f), startPt, p1, strokeWidth = 1.4.dp.toPx())
            drawLine(handle2Col.copy(alpha = 0.65f), endPt, p2, strokeWidth = 1.4.dp.toPx())
            drawCircle(accent, radius = 5.dp.toPx(), center = p1)
            drawCircle(Color.White, radius = 2.2.dp.toPx(), center = p1)
            drawCircle(handle2Col, radius = 5.dp.toPx(), center = p2)
            drawCircle(Color.White, radius = 2.2.dp.toPx(), center = p2)
        }

        // Animated dot tracking curve progress
        val curV = curve.evaluate(animT, bezier).coerceIn(-0.35f, 1.35f)
        val dotPt = Offset(pad + animT * w, pad + h - curV * h)
        drawCircle(accent.copy(alpha = 0.28f), radius = 7.dp.toPx(), center = dotPt)
        drawCircle(Color.White, radius = 3.2.dp.toPx(), center = dotPt)
    }
}

@Composable
fun CurvePreviewGraph(
    curve: TweenCurveKind,
    accent: Color,
    modifier: Modifier = Modifier,
    bezier: CubicBezierControl = CubicBezierControl(),
) {
    val gridCol = Pc.Divider
    Canvas(modifier) {
        val pad = 5.dp.toPx()
        val w = (size.width - pad * 2).coerceAtLeast(1f)
        val h = (size.height - pad * 2).coerceAtLeast(1f)
        drawLine(gridCol, Offset(pad, pad), Offset(pad + w, pad), strokeWidth = 1f)
        drawLine(gridCol, Offset(pad, pad + h), Offset(pad + w, pad + h), strokeWidth = 1f)

        val steps = 28
        val path = Path()
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val v = curve.evaluate(t, bezier).coerceIn(-0.22f, 1.22f)
            val x = pad + t * w
            val y = pad + h - v * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = accent, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round))
    }
}
