package io.github.artcraftandroid.procraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
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
import io.github.artcraftandroid.procraft.model.CanvasPreset
import io.github.artcraftandroid.procraft.model.CanvasUnit
import java.util.UUID

@Composable
fun CustomCanvasDialog(
    editing: CanvasPreset?,
    onDismiss: () -> Unit,
    onSaveAndCreate: (CanvasPreset) -> Unit,
) {
    var name by remember { mutableStateOf(editing?.name ?: "Custom Canvas") }
    var widthStr by remember { mutableStateOf(editing?.width?.toInt()?.toString() ?: "2048") }
    var heightStr by remember { mutableStateOf(editing?.height?.toInt()?.toString() ?: "2048") }
    var dpiStr by remember { mutableStateOf((editing?.dpi ?: 300).toString()) }
    var unit by remember { mutableStateOf(editing?.unit ?: CanvasUnit.PX) }
    var bgRgb by remember { mutableIntStateOf(editing?.backgroundRgb ?: 0xFFFFFFFF.toInt()) }
    var timelapse by remember { mutableStateOf(editing?.timelapseEnabled ?: false) }

    val wVal = widthStr.toFloatOrNull()?.coerceAtLeast(1f) ?: 2048f
    val hVal = heightStr.toFloatOrNull()?.coerceAtLeast(1f) ?: 2048f
    val dpiVal = dpiStr.toIntOrNull()?.coerceIn(72, 600) ?: 300

    val previewPreset = CanvasPreset(
        id = editing?.id ?: UUID.randomUUID().toString(),
        name = name.ifBlank { "Custom Canvas" },
        width = wVal,
        height = hVal,
        unit = unit,
        dpi = dpiVal,
        backgroundRgb = bgRgb,
        timelapseEnabled = timelapse,
        isCustom = true,
    )
    val maxLayers = remember(previewPreset.pixelWidth, previewPreset.pixelHeight) {
        val mp = (previewPreset.pixelWidth * previewPreset.pixelHeight) / 1_000_000f
        (120f / mp.coerceAtLeast(0.5f)).toInt().coerceIn(8, 128)
    }

    PcDialogShell(
        title = "Custom Canvas",
        onDismiss = onDismiss,
        leftLabel = "Cancel",
        onLeftClick = onDismiss,
        leftTag = "custom_canvas_cancel",
        rightLabel = "Create",
        onRightClick = { onSaveAndCreate(previewPreset) },
        rightTag = "custom_canvas_create",
        modifier = Modifier
            .widthIn(max = 440.dp)
            .fillMaxWidth(0.92f)
            .heightIn(max = 580.dp),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PcInlineTextField(
                value = name,
                onValueChange = { name = it },
                label = "Preset Name",
                placeholder = "Custom Canvas",
                inputTag = "custom_canvas_name",
                modifier = Modifier.fillMaxWidth(),
            )

            PcSectionCard("Dimensions") {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PcInlineTextField(
                            value = widthStr,
                            onValueChange = { widthStr = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            label = "Width",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            inputTag = "custom_canvas_width",
                            modifier = Modifier.weight(1f),
                        )
                        PcInlineTextField(
                            value = heightStr,
                            onValueChange = { heightStr = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            label = "Height",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            inputTag = "custom_canvas_height",
                            modifier = Modifier.weight(1f),
                        )
                        PcInlineTextField(
                            value = dpiStr,
                            onValueChange = { dpiStr = it.filter { ch -> ch.isDigit() } },
                            label = "DPI",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            inputTag = "custom_canvas_dpi",
                            modifier = Modifier.weight(0.78f),
                        )
                    }

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(Pc.PanelSurface)
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        CanvasUnit.entries.forEach { u ->
                            val sel = unit == u
                            Box(
                                Modifier
                                    .weight(1f)
                                    .heightIn(min = 36.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(if (sel) Pc.Accent else Color.Transparent)
                                    .clickable { unit = u },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(u.label, color = if (sel) Color.White else Pc.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }

            PcSectionCard("Canvas Metrics") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("Output Resolution", color = Pc.TextSecondary, fontSize = 11.sp)
                        Text(previewPreset.dimensionLabel, color = Pc.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Maximum Layers", color = Pc.TextSecondary, fontSize = 11.sp)
                        Text("$maxLayers", color = Pc.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            PcSectionCard("Canvas Properties") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Background Color", color = Pc.TextSecondary, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(
                            0xFFFFFFFF.toInt(),
                            0xFFF8F4EA.toInt(),
                            0xFFE5E5EA.toInt(),
                            0xFF222226.toInt(),
                            0xFF0E0E10.toInt(),
                        ).forEach { col ->
                            val sel = bgRgb == col
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(Color(col))
                                    .border(if (sel) 2.5.dp else 1.dp, if (sel) Pc.Accent else Pc.Divider, CircleShape)
                                    .clickable { bgRgb = col },
                            )
                        }
                    }
                }
                PcRowDivider()
                PcToggleRow(
                    label = "Time-lapse Recording",
                    icon = LucideIcons.InfoVideo,
                    checked = timelapse,
                ) { timelapse = it }
            }
        }
    }
}
