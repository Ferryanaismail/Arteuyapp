package io.github.artcraftandroid.procraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import io.github.artcraftandroid.procraft.canvas.activePaintTrack
import io.github.artcraftandroid.procraft.canvas.generateInBetweenKeyframes
import io.github.artcraftandroid.procraft.canvas.resolveTweenContext
import io.github.artcraftandroid.procraft.model.CubicBezierControl
import io.github.artcraftandroid.procraft.model.TweenCurveKind
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun InBetweenDialog(
    vm: CanvasViewModel,
    onDismiss: () -> Unit,
) {
    val activeTrack = vm.activePaintTrack()
    val trackKeys = remember(vm.motionKeyframes, activeTrack?.id) {
        if (activeTrack == null) emptyList()
        else vm.motionKeyframes.filter { it.trackId == activeTrack.id }.sortedBy { it.frameIndex }
    }
    val curF = vm.animationState.currentFrameIndex
    val defaultStart = trackKeys.lastOrNull { it.frameIndex < curF }?.frameIndex
        ?: trackKeys.firstOrNull()?.frameIndex
        ?: 0
    val defaultEnd = if (curF > defaultStart) curF else (defaultStart + 8).coerceAtMost(1199)

    val (ctxStart, ctxEnd) = remember(vm.transformState, vm.selectionAnchor, curF) {
        vm.resolveTweenContext()
    }

    var startFrame by remember { mutableIntStateOf(defaultStart) }
    var endFrame by remember { mutableIntStateOf(defaultEnd) }
    var selectedCurve by remember { mutableStateOf(vm.selectionTweenCurve) }
    var bezierCtrl by remember { mutableStateOf(vm.selectionCubicBezier) }
    var stepBy by remember { mutableIntStateOf(1) }

    // Allow fine-tuning End Pose right inside dialog (pre-populated from current selection transform)
    var endRotDeg by remember { mutableFloatStateOf(ctxEnd.rotationDeg) }
    var endScale by remember { mutableFloatStateOf(ctxEnd.scaleX) }
    var endMoveX by remember { mutableFloatStateOf(ctxEnd.translateX) }
    var endMoveY by remember { mutableFloatStateOf(ctxEnd.translateY) }

    val frameCount = ((endFrame - startFrame).coerceAtLeast(1) / stepBy) + 1

    PcDialogShell(
        title = "Insert Keyframe & In-Between",
        onDismiss = onDismiss,
        leftLabel = "Cancel",
        onLeftClick = onDismiss,
        rightLabel = "Generate",
        rightTag = "inbetween_generate_button",
        onRightClick = {
            val finalEnd = ctxEnd.copy(
                rotationDeg = endRotDeg,
                scaleX = endScale,
                scaleY = endScale,
                translateX = endMoveX,
                translateY = endMoveY,
            )
            vm.generateInBetweenKeyframes(
                startFrame = startFrame,
                endFrame = endFrame,
                startState = ctxStart,
                endState = finalEnd,
                curve = selectedCurve,
                bezier = bezierCtrl,
                stepBy = stepBy,
            )
            onDismiss()
        },
        modifier = Modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth(0.94f)
            .heightIn(max = 620.dp)
            .testTag("inbetween_dialog"),
    ) {
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PcSectionCard("Frame Span ($frameCount keyframes)") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FrameStepperBox(
                        label = "Start Frame",
                        frameIndex = startFrame,
                        onDelta = { d ->
                            startFrame = (startFrame + d).coerceIn(0, 1198)
                            if (endFrame <= startFrame) endFrame = startFrame + 1
                        },
                        modifier = Modifier.weight(1f),
                    )
                    FrameStepperBox(
                        label = "End Frame",
                        frameIndex = endFrame,
                        onDelta = { d ->
                            endFrame = (endFrame + d).coerceIn(startFrame + 1, 1199)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(6, 12, 24).forEach { spanLen ->
                        val sel = (endFrame - startFrame) == spanLen
                        Box(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (sel) Pc.Accent else Pc.PanelSurface)
                                .clickable { endFrame = (startFrame + spanLen).coerceAtMost(1199) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "+${spanLen}f",
                                color = if (sel) Color.White else Pc.TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            PcSectionCard("Interpolation Curve") {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(TweenCurveKind.entries) { curve ->
                            val sel = selectedCurve == curve
                            Column(
                                Modifier
                                    .width(86.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (sel) Pc.RowSelected else Pc.PanelSurface)
                                    .border(
                                        width = if (sel) 1.5.dp else 0.6.dp,
                                        color = if (sel) Pc.Accent else Pc.Divider,
                                        shape = RoundedCornerShape(10.dp),
                                    )
                                    .clickable { selectedCurve = curve }
                                    .padding(8.dp)
                                    .testTag("curve_${curve.name.lowercase()}"),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                CurvePreviewGraph(
                                    curve = curve,
                                    accent = if (sel) Pc.Accent else Pc.TextSecondary,
                                    modifier = Modifier.fillMaxWidth().height(42.dp),
                                    bezier = bezierCtrl,
                                )
                                Text(
                                    curve.label,
                                    color = if (sel) Pc.Accent else Pc.TextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                                    maxLines = 1,
                                )
                            }
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Step", color = Pc.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(end = 4.dp))
                        listOf(1 to "On 1s", 2 to "On 2s", 3 to "On 3s").forEach { (stepVal, lbl) ->
                            val sel = stepBy == stepVal
                            Box(
                                Modifier
                                    .weight(1f)
                                    .heightIn(min = 34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (sel) Pc.Accent else Pc.PanelSurface)
                                    .clickable { stepBy = stepVal },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    lbl,
                                    color = if (sel) Color.White else Pc.TextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }

            PcSectionCard("End Keyframe Pose (around Anchor)") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val ax = ctxEnd.anchorX?.roundToInt() ?: 0
                    val ay = ctxEnd.anchorY?.roundToInt() ?: 0
                    Text(
                        "Pivot Axis: ($ax, $ay) px",
                        color = Pc.Accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Rotation", color = Pc.TextPrimary, fontSize = 12.sp)
                        Text("${endRotDeg.roundToInt()}°", color = Pc.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    PcSlider(
                        value = ((endRotDeg + 180f) / 360f).coerceIn(0f, 1f),
                        onValueChange = { f -> endRotDeg = f * 360f - 180f },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Scale", color = Pc.TextPrimary, fontSize = 12.sp)
                        Text("${(endScale * 100f).roundToInt()}%", color = Pc.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    PcSlider(
                        value = ((endScale - 0.25f) / 2.25f).coerceIn(0f, 1f),
                        onValueChange = { f -> endScale = 0.25f + f * 2.25f },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Move X / Y", color = Pc.TextPrimary, fontSize = 12.sp)
                        Text("${endMoveX.roundToInt()}, ${endMoveY.roundToInt()} px", color = Pc.TextSecondary, fontSize = 11.sp)
                    }
                    if (abs(endRotDeg - ctxStart.rotationDeg) < 0.5f &&
                        abs(endScale - ctxStart.scaleX) < 0.01f &&
                        abs(endMoveX - ctxStart.translateX) < 1f &&
                        abs(endMoveY - ctxStart.translateY) < 1f
                    ) {
                        Text(
                            "Tip: Adjust Rotation / Scale above or transform selection on canvas so Start and End poses differ.",
                            color = Pc.Warning,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FrameStepperBox(
    label: String,
    frameIndex: Int,
    onDelta: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(Pc.PanelSurface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = Pc.TextSecondary, fontSize = 10.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = { onDelta(-1) }, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Rounded.Remove, contentDescription = "Decrease $label", tint = Pc.TextPrimary, modifier = Modifier.size(16.dp))
            }
            Text("F${frameIndex + 1}", color = Pc.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = { onDelta(1) }, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Rounded.Add, contentDescription = "Increase $label", tint = Pc.TextPrimary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

