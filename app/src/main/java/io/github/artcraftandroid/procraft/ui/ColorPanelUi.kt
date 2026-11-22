package io.github.artcraftandroid.procraft.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import io.github.artcraftandroid.procraft.model.ColorMode
import io.github.artcraftandroid.procraft.model.HarmonyKind
import kotlin.math.*

@Composable
fun ColorPanelPopover(
    vm: CanvasViewModel,
    caretFromEnd: Dp = 20.dp,
    modifier: Modifier = Modifier,
) {
    PcPopoverFrame(
        caretFromEnd = caretFromEnd,
        caretColor = Pc.PanelHeader,
        modifier = modifier
            .widthIn(max = 336.dp)
            .fillMaxWidth(0.92f)
            .heightIn(max = 470.dp),
        bodyModifier = Modifier
            .fillMaxWidth()
            .testTag("color_panel_popover"),
    ) {
        // Header with current + previous swatch split
        Row(
            Modifier
                .fillMaxWidth()
                .background(Pc.PanelHeader)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Colors", color = Pc.TextPrimary, fontSize = Pc.TitleSize, fontWeight = FontWeight.SemiBold)
            Row(
                Modifier
                    .height(26.dp)
                    .width(72.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .border(0.8.dp, Pc.Divider, RoundedCornerShape(7.dp)),
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(CanvasSpace.color(vm.prevColorRgb))
                        .clickable { vm.swapPreviousColor() },
                )
                Box(Modifier.weight(1f).fillMaxHeight().background(CanvasSpace.color(vm.colorRgb)))
            }
        }
        HorizontalDivider(color = Pc.Divider, thickness = 0.6.dp)

        // Mode Body
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            when (vm.colorMode) {
                ColorMode.DISC -> DiscPicker(vm)
                ColorMode.CLASSIC -> ClassicPicker(vm)
                ColorMode.HARMONY -> HarmonyPicker(vm)
                ColorMode.VALUE -> ValuePicker(vm)
                ColorMode.PALETTES -> PalettesPicker(vm)
            }
        }

        // Color History Strip (shown in Disc/Classic/Harmony/Value)
        if (vm.colorMode != ColorMode.PALETTES) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("History", color = Pc.TextSecondary, fontSize = 11.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(vm.colorHistory) { rgb ->
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(CanvasSpace.color(rgb))
                                .border(0.8.dp, Pc.Divider, RoundedCornerShape(5.dp))
                                .clickable { vm.setColor(rgb, recordHistory = false) },
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = Pc.Divider, thickness = 0.6.dp)
        // Bottom 5 Tabs
        Row(
            Modifier
                .fillMaxWidth()
                .background(Pc.PanelHeader)
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            val tabs = listOf(
                ColorMode.DISC to LucideIcons.ModeDisc,
                ColorMode.CLASSIC to LucideIcons.ModeClassic,
                ColorMode.HARMONY to LucideIcons.ModeHarmony,
                ColorMode.VALUE to LucideIcons.ModeValue,
                ColorMode.PALETTES to LucideIcons.ModePalettes,
            )
            tabs.forEach { (mode, icon) ->
                val sel = vm.colorMode == mode
                Column(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { vm.colorMode = mode }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(icon, contentDescription = mode.label, tint = if (sel) Pc.Accent else Pc.TextSecondary, modifier = Modifier.size(22.dp))
                    Text(mode.label, color = if (sel) Pc.Accent else Pc.TextSecondary, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun DiscPicker(vm: CanvasViewModel) {
    val hueStep = (vm.hue / 2f).roundToInt() * 2f
    val svDiscImage = remember(hueStep) {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        val half = (size - 1) * 0.5f
        val hsv = floatArrayOf(hueStep, 0f, 0f)
        for (py in 0 until size) {
            val v = (1f - py.toFloat() / (size - 1)).coerceIn(0f, 1f)
            for (px in 0 until size) {
                val dx = px - half
                val dy = py - half
                if (dx * dx + dy * dy <= (half + 1f) * (half + 1f)) {
                    val s = (px.toFloat() / (size - 1)).coerceIn(0f, 1f)
                    hsv[1] = s
                    hsv[2] = v
                    pixels[py * size + px] = android.graphics.Color.HSVToColor(255, hsv)
                } else {
                    pixels[py * size + px] = 0
                }
            }
        }
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)
        bmp.asImageBitmap()
    }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(
            Modifier
                .size(226.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var pickingHue = false
                        val cx = size.width * 0.5f
                        val cy = size.height * 0.5f
                        val outerR = min(cx, cy)
                        val innerR = outerR * 0.74f
                        val discR = innerR * 0.88f

                        fun handle(pos: Offset, isDown: Boolean, record: Boolean) {
                            var dx = pos.x - cx
                            var dy = pos.y - cy
                            val d = hypot(dx, dy)
                            if (isDown) pickingHue = d >= innerR * 0.94f
                            if (pickingHue) {
                                val deg = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()) + 360f) % 360f
                                vm.setHsv(deg, vm.saturation, vm.brightness, record)
                            } else {
                                if (d > discR && d > 0f) {
                                    val scale = discR / d
                                    dx *= scale
                                    dy *= scale
                                }
                                val s = ((dx + discR) / (discR * 2f)).coerceIn(0f, 1f)
                                val v = (1f - (dy + discR) / (discR * 2f)).coerceIn(0f, 1f)
                                vm.setHsv(vm.hue, s, v, record)
                            }
                        }

                        handle(down.position, isDown = true, record = false)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) {
                                handle(ch.position, isDown = false, record = true)
                                break
                            }
                            handle(ch.position, isDown = false, record = false)
                            ch.consume()
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width * 0.5f
                val cy = size.height * 0.5f
                val outerR = min(cx, cy)
                val innerR = outerR * 0.74f
                val ringWidth = outerR - innerR
                val discR = innerR * 0.88f

                val hueColors = (0..360 step 30).map { h -> CanvasSpace.color(CanvasSpace.argbFromHsv(h.toFloat(), 1f, 1f)) }
                drawCircle(
                    brush = Brush.sweepGradient(hueColors),
                    radius = (outerR + innerR) * 0.5f,
                    style = Stroke(width = ringWidth),
                )
                drawCircle(Pc.Divider, radius = outerR, style = Stroke(1.dp.toPx()))
                drawCircle(Pc.Divider, radius = innerR, style = Stroke(1.dp.toPx()))

                // Procreate-style circular SV disc inside hue ring
                drawImage(
                    image = svDiscImage,
                    dstOffset = IntOffset((cx - discR).roundToInt(), (cy - discR).roundToInt()),
                    dstSize = IntSize((discR * 2f).roundToInt(), (discR * 2f).roundToInt()),
                )
                drawCircle(Pc.Divider, radius = discR, style = Stroke(1.dp.toPx()))

                // Hue reticle
                val hRad = Math.toRadians(vm.hue.toDouble())
                val hMidR = (outerR + innerR) * 0.5f
                val hx = cx + (cos(hRad) * hMidR).toFloat()
                val hy = cy + (sin(hRad) * hMidR).toFloat()
                drawCircle(Color.White, 9f, Offset(hx, hy), style = Stroke(3f))
                drawCircle(Color(0x55000000), 10.5f, Offset(hx, hy), style = Stroke(1f))

                // SV reticle clamped inside disc
                var sdx = (vm.saturation - 0.5f) * 2f * discR
                var sdy = (0.5f - vm.brightness) * 2f * discR
                val sd = hypot(sdx, sdy)
                if (sd > discR && sd > 0f) {
                    val scale = discR / sd
                    sdx *= scale
                    sdy *= scale
                }
                val sx = cx + sdx
                val sy = cy + sdy
                drawCircle(Color.White, 9f, Offset(sx, sy), style = Stroke(3f))
                drawCircle(Color.Black, 10.5f, Offset(sx, sy), style = Stroke(1.2f))
            }
        }
    }
}

@Composable
private fun ClassicPicker(vm: CanvasViewModel) {
    val hueBrush = remember {
        Brush.horizontalGradient((0..360 step 30).map { h -> CanvasSpace.color(CanvasSpace.argbFromHsv(h.toFloat(), 1f, 1f)) })
    }
    val pureHue = CanvasSpace.color(CanvasSpace.argbFromHsv(vm.hue, 1f, 1f))
    val satBrush = remember(vm.hue) {
        Brush.horizontalGradient(listOf(Color.White, pureHue))
    }
    val satHue = CanvasSpace.color(CanvasSpace.argbFromHsv(vm.hue, vm.saturation, 1f))
    val valBrush = remember(vm.hue, vm.saturation) {
        Brush.horizontalGradient(listOf(Color.Black, satHue))
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun update(pos: Offset, record: Boolean) {
                            val s = (pos.x / size.width).coerceIn(0f, 1f)
                            val v = (1f - pos.y / size.height).coerceIn(0f, 1f)
                            vm.setHsv(vm.hue, s, v, record)
                        }
                        update(down.position, false)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) { update(ch.position, true); break }
                            update(ch.position, false)
                            ch.consume()
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.horizontalGradient(listOf(Color.White, pureHue)))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                val sx = vm.saturation * size.width
                val sy = (1f - vm.brightness) * size.height
                drawCircle(Color.White, 9f, Offset(sx, sy), style = Stroke(3f))
                drawCircle(Color.Black, 10.5f, Offset(sx, sy), style = Stroke(1.2f))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Hue ${vm.hue.roundToInt()}°", color = Pc.TextSecondary, fontSize = 11.sp)
            Text("Sat ${(vm.saturation * 100).roundToInt()}%  Val ${(vm.brightness * 100).roundToInt()}%", color = Pc.TextSecondary, fontSize = 11.sp)
        }
        PcSlider(
            value = vm.hue / 360f,
            onValueChange = { vm.setHsv(it * 360f, vm.saturation, vm.brightness, false) },
            onValueChangeFinished = { vm.setColor(vm.colorRgb, true) },
            trackBrush = hueBrush,
        )
        PcSlider(
            value = vm.saturation,
            onValueChange = { vm.setHsv(vm.hue, it, vm.brightness, false) },
            onValueChangeFinished = { vm.setColor(vm.colorRgb, true) },
            trackBrush = satBrush,
        )
        PcSlider(
            value = vm.brightness,
            onValueChange = { vm.setHsv(vm.hue, vm.saturation, it, false) },
            onValueChangeFinished = { vm.setColor(vm.colorRgb, true) },
            trackBrush = valBrush,
        )
    }
}

@Composable
private fun HarmonyPicker(vm: CanvasViewModel) {
    val harmonyColors = remember(vm.hue, vm.saturation, vm.brightness, vm.harmonyKind) {
        listOf(vm.colorRgb) + vm.harmonyKind.offsetsDeg.map { off ->
            CanvasSpace.argbFromHsv((vm.hue + off + 360f) % 360f, vm.saturation, vm.brightness)
        }
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(HarmonyKind.entries) { kind ->
                val sel = vm.harmonyKind == kind
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (sel) Pc.Accent else Pc.PanelSurface)
                        .clickable { vm.harmonyKind = kind }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(kind.label, color = if (sel) Color.White else Pc.TextPrimary, fontSize = 11.sp)
                }
            }
        }
        Box(
            Modifier
                .size(164.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val cx = size.width * 0.5f
                        val cy = size.height * 0.5f
                        val maxR = min(cx, cy)
                        fun pick(pos: Offset, record: Boolean) {
                            val dx = pos.x - cx
                            val dy = pos.y - cy
                            val h = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()) + 360f) % 360f
                            val s = (hypot(dx, dy) / maxR).coerceIn(0f, 1f)
                            vm.setHsv(h, s, vm.brightness, record)
                        }
                        pick(down.position, false)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) { pick(ch.position, true); break }
                            pick(ch.position, false)
                            ch.consume()
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width * 0.5f
                val cy = size.height * 0.5f
                val r = min(cx, cy)
                val colors = (0..360 step 30).map { h -> CanvasSpace.color(CanvasSpace.argbFromHsv(h.toFloat(), 1f, vm.brightness)) }
                drawCircle(Brush.sweepGradient(colors), radius = r)
                drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), radius = r), radius = r)
                val allAngles = listOf(0f) + vm.harmonyKind.offsetsDeg
                allAngles.forEach { off ->
                    val rad = Math.toRadians(((vm.hue + off) % 360f).toDouble())
                    val px = cx + (cos(rad) * vm.saturation * r).toFloat()
                    val py = cy + (sin(rad) * vm.saturation * r).toFloat()
                    drawCircle(Color.White, 8f, Offset(px, py), style = Stroke(2.5f))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            harmonyColors.forEach { c ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(CanvasSpace.color(c))
                        .border(1.dp, Pc.Divider, RoundedCornerShape(6.dp))
                        .clickable { vm.setColor(c, recordHistory = true) },
                )
            }
        }
    }
}

@Composable
private fun ValuePicker(vm: CanvasViewModel) {
    val r = (vm.colorRgb shr 16) and 0xFF
    val g = (vm.colorRgb shr 8) and 0xFF
    val b = vm.colorRgb and 0xFF
    var hexInput by remember(vm.colorRgb) { mutableStateOf(CanvasSpace.hexString(vm.colorRgb)) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ValueSlider(
            label = "R",
            value = r,
            trackBrush = Brush.horizontalGradient(listOf(Color(0, g, b), Color(255, g, b))),
        ) { nr -> vm.setColor(android.graphics.Color.rgb(nr, g, b), true) }
        ValueSlider(
            label = "G",
            value = g,
            trackBrush = Brush.horizontalGradient(listOf(Color(r, 0, b), Color(r, 255, b))),
        ) { ng -> vm.setColor(android.graphics.Color.rgb(r, ng, b), true) }
        ValueSlider(
            label = "B",
            value = b,
            trackBrush = Brush.horizontalGradient(listOf(Color(r, g, 0), Color(r, g, 255))),
        ) { nb -> vm.setColor(android.graphics.Color.rgb(r, g, nb), true) }
        HorizontalDivider(color = Pc.Divider, thickness = 0.6.dp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Hexadecimal", color = Pc.TextSecondary, fontSize = 12.sp)
            PcInlineTextField(
                value = hexInput,
                onValueChange = { txt ->
                    hexInput = txt
                    CanvasSpace.parseHex(txt)?.let { parsed -> vm.setColor(parsed, true) }
                },
                placeholder = "#FFFFFF",
                inputTag = "color_hex_input",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ValueSlider(
    label: String,
    value: Int,
    trackBrush: Brush,
    onChange: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Pc.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(18.dp))
        PcSlider(
            value = value / 255f,
            onValueChange = { onChange((it * 255f).roundToInt().coerceIn(0, 255)) },
            trackBrush = trackBrush,
            modifier = Modifier.weight(1f),
        )
        Text("$value", color = Pc.TextPrimary, fontSize = 12.sp, modifier = Modifier.width(30.dp))
    }
}

@Composable
private fun PalettesPicker(vm: CanvasViewModel) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Palettes", color = Pc.TextSecondary, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("New Palette", color = Pc.Accent, fontSize = 12.sp, modifier = Modifier.clickable { vm.createPalette("Palette ${vm.palettes.size + 1}") })
                IconButton(onClick = { vm.createPalette("Palette ${vm.palettes.size + 1}") }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Add, contentDescription = "New Palette", tint = Pc.Accent, modifier = Modifier.size(18.dp))
                }
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(vm.palettes, key = { it.id }) { pal ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Pc.PanelCard)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(pal.name, color = Pc.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "+ Add Swatch",
                                color = Pc.Accent,
                                fontSize = 11.sp,
                                modifier = Modifier.clickable { vm.addColorToPalette(pal.id, vm.colorRgb) },
                            )
                            if (vm.palettes.size > 1) {
                                Icon(
                                    Icons.Rounded.Delete,
                                    contentDescription = "Delete Palette",
                                    tint = Pc.TextSecondary,
                                    modifier = Modifier.size(16.dp).clickable { vm.deletePalette(pal.id) },
                                )
                            }
                        }
                    }
                    // 10-column swatch grid
                    val chunks = pal.swatches.filterNotNull().chunked(10)
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        chunks.forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                row.forEach { col ->
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .height(22.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(CanvasSpace.color(col))
                                            .clickable { vm.setColor(col, recordHistory = true) },
                                    )
                                }
                                repeat(10 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
