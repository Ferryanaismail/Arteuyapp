package io.github.artcraftandroid.procraft.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Sleek iOS / Procreate style capsule toggle switch tailored to ProCraft tokens.
 */
@Composable
fun PcSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackColor by animateColorAsState(
        targetValue = if (checked) Pc.Accent else if (Pc.light) Color(0xFFD0D0D6) else Color(0xFF3E3E44),
        animationSpec = tween(150),
        label = "pc_switch_track",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = tween(150),
        label = "pc_switch_thumb",
    )
    Box(
        modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 40.dp)
            .semantics {
                role = Role.Switch
                toggleableState = ToggleableState(checked)
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(46.dp, 26.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(trackColor),
        ) {
            Box(
                Modifier
                    .offset(x = thumbOffset, y = 2.dp)
                    .size(22.dp)
                    .shadow(2.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}

/**
 * Sleek ProCraft thin-track slider with circular white thumb and optional gradient track brush.
 */
@Composable
fun PcSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    activeColor: Color = Pc.Accent,
    trackBrush: Brush? = null,
) {
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-5f)
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val latestOnChange by rememberUpdatedState(onValueChange)
    val latestOnFinish by rememberUpdatedState(onValueChangeFinished)
    val inactiveTrack = if (Pc.light) Color(0xFFC8C8CE) else Color(0xFF3E3E45)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(34.dp)
            .pointerInput(valueRange) {
                detectTapGestures { pos ->
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val pad = 9.dp.toPx()
                    val usable = (w - pad * 2f).coerceAtLeast(1f)
                    val f = ((pos.x - pad) / usable).coerceIn(0f, 1f)
                    latestOnChange(valueRange.start + f * span)
                    latestOnFinish?.invoke()
                }
            }
            .pointerInput(valueRange) {
                detectHorizontalDragGestures(
                    onDragStart = { pos ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val pad = 9.dp.toPx()
                        val usable = (w - pad * 2f).coerceAtLeast(1f)
                        val f = ((pos.x - pad) / usable).coerceIn(0f, 1f)
                        latestOnChange(valueRange.start + f * span)
                    },
                    onDragEnd = { latestOnFinish?.invoke() },
                    onDragCancel = { latestOnFinish?.invoke() },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val pad = 9.dp.toPx()
                        val usable = (w - pad * 2f).coerceAtLeast(1f)
                        val f = ((change.position.x - pad) / usable).coerceIn(0f, 1f)
                        latestOnChange(valueRange.start + f * span)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pad = 9.dp.toPx()
            val cy = size.height * 0.5f
            val trackLeft = pad
            val trackRight = (size.width - pad).coerceAtLeast(pad + 1f)
            val trackW = trackRight - trackLeft
            val trackH = if (trackBrush != null) 7.dp.toPx() else 4.dp.toPx()
            val top = cy - trackH * 0.5f

            if (trackBrush != null) {
                drawRoundRect(
                    brush = trackBrush,
                    topLeft = Offset(trackLeft, top),
                    size = androidx.compose.ui.geometry.Size(trackW, trackH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackH * 0.5f),
                )
            } else {
                drawRoundRect(
                    color = inactiveTrack,
                    topLeft = Offset(trackLeft, top),
                    size = androidx.compose.ui.geometry.Size(trackW, trackH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackH * 0.5f),
                )
                drawRoundRect(
                    color = activeColor,
                    topLeft = Offset(trackLeft, top),
                    size = androidx.compose.ui.geometry.Size(trackW * fraction, trackH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackH * 0.5f),
                )
            }

            val kx = trackLeft + trackW * fraction
            val kr = 9.dp.toPx()
            drawCircle(Color(0x44000000), radius = kr + 1.dp.toPx(), center = Offset(kx, cy + 1.dp.toPx()))
            drawCircle(Color.White, radius = kr, center = Offset(kx, cy))
            drawCircle(Color(0x33000000), radius = kr, center = Offset(kx, cy), style = Stroke(width = 0.8.dp.toPx()))
        }
    }
}

/**
 * Sleek rounded ProCraft layer visibility checkbox (40.dp hit area, 22.dp box).
 */
@Composable
fun PcCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(40.dp)
            .semantics {
                role = Role.Checkbox
                toggleableState = ToggleableState(checked)
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (checked) Pc.Accent else Color.Transparent)
                .border(
                    width = 1.5.dp,
                    color = if (checked) Pc.Accent else Pc.TextSecondary.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(6.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = LucideIcons.DoneTick,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * ProCraft popover wrapper with an upward-pointing caret aligned from start or end.
 */
@Composable
fun PcPopoverFrame(
    modifier: Modifier = Modifier,
    caretFromStart: Dp? = null,
    caretFromEnd: Dp? = null,
    caretColor: Color = Pc.PanelHeader,
    bodyModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        if (caretFromStart != null || caretFromEnd != null) {
            Box(Modifier.fillMaxWidth().height(9.dp)) {
                val align = if (caretFromStart != null) Alignment.BottomStart else Alignment.BottomEnd
                val padMod = if (caretFromStart != null) {
                    Modifier.padding(start = (caretFromStart - 9.dp).coerceAtLeast(10.dp))
                } else {
                    Modifier.padding(end = ((caretFromEnd ?: 16.dp) - 9.dp).coerceAtLeast(10.dp))
                }
                Canvas(
                    Modifier
                        .align(align)
                        .then(padMod)
                        .size(18.dp, 9.dp),
                ) {
                    val path = Path().apply {
                        moveTo(0f, size.height)
                        lineTo(size.width * 0.5f, 0f)
                        lineTo(size.width, size.height)
                        close()
                    }
                    drawPath(path, caretColor)
                }
            }
        }
        Column(
            bodyModifier
                .shadow(18.dp, Pc.PanelShape)
                .clip(Pc.PanelShape)
                .background(Pc.PanelBg)
                .border(0.8.dp, Pc.Divider, Pc.PanelShape)
                .clickable(enabled = false) {},
            content = content,
        )
    }
}

@Composable
fun PcSectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (title != null) {
            Text(
                title.uppercase(),
                color = Pc.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.6.sp,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(11.dp))
                .background(Pc.PanelCard)
                .border(0.6.dp, Pc.Divider, RoundedCornerShape(11.dp)),
            content = content,
        )
    }
}

@Composable
fun PcRowDivider(indent: Dp = 14.dp) {
    HorizontalDivider(color = Pc.Divider, thickness = 0.6.dp, modifier = Modifier.padding(start = indent))
}

@Composable
fun PcToggleRow(
    label: String,
    checked: Boolean,
    icon: ImageVector? = null,
    subtitle: String? = null,
    testTag: String = "",
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .then(if (testTag.isNotEmpty()) Modifier.testTag(testTag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = label, tint = Pc.TextPrimary, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, color = Pc.TextPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, color = Pc.TextSecondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        PcSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun PcNavRow(
    label: String,
    icon: ImageVector? = null,
    trailingText: String? = null,
    destructive: Boolean = false,
    showChevron: Boolean = true,
    iconTint: Color = if (destructive) Pc.Destructive else Pc.TextPrimary,
    testTag: String = "",
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .then(if (testTag.isNotEmpty()) Modifier.testTag(testTag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = label, tint = iconTint, modifier = Modifier.size(20.dp))
        }
        Text(
            label,
            color = if (destructive) Pc.Destructive else Pc.TextPrimary,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailingText != null) {
            Text(trailingText, color = Pc.TextSecondary, fontSize = 12.sp, maxLines = 1)
        }
        if (showChevron) {
            Icon(
                LucideIcons.MenuChevron,
                contentDescription = null,
                tint = Pc.TextSecondary.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
fun PcInlineTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    singleLine: Boolean = true,
    maxLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    inputTag: String = "",
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label != null) {
            Text(label, color = Pc.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 42.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Pc.PanelSurface)
                .border(0.8.dp, Pc.Divider, RoundedCornerShape(9.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, color = Pc.TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                maxLines = maxLines,
                keyboardOptions = keyboardOptions,
                textStyle = TextStyle(color = Pc.TextPrimary, fontSize = 14.sp),
                cursorBrush = SolidColor(Pc.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (inputTag.isNotEmpty()) Modifier.testTag(inputTag) else Modifier),
            )
        }
    }
}

@Composable
fun PcCompactSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "Search...",
    modifier: Modifier = Modifier,
    inputTag: String = "",
) {
    Row(
        modifier
            .height(42.dp)
            .clip(RoundedCornerShape(21.dp))
            .background(Pc.PanelSurface)
            .border(0.8.dp, Pc.Divider, RoundedCornerShape(21.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.Search, contentDescription = "Search", tint = Pc.TextSecondary, modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(placeholder, color = Pc.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = Pc.TextPrimary, fontSize = 13.sp),
                cursorBrush = SolidColor(Pc.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (inputTag.isNotEmpty()) Modifier.testTag(inputTag) else Modifier),
            )
        }
        if (value.isNotEmpty()) {
            IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = "Clear search", tint = Pc.TextSecondary, modifier = Modifier.size(15.dp))
            }
        }
    }
}

@Composable
fun PcDialogShell(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    leftLabel: String? = "Cancel",
    onLeftClick: (() -> Unit)? = onDismiss,
    leftTag: String? = null,
    rightLabel: String? = "Done",
    onRightClick: (() -> Unit)? = onDismiss,
    rightTag: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier
                .clip(Pc.PanelShape)
                .background(Pc.PanelBg)
                .border(0.8.dp, Pc.Divider, Pc.PanelShape),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Pc.PanelHeader)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (leftLabel != null && onLeftClick != null) {
                    TextButton(
                        onClick = onLeftClick,
                        modifier = Modifier
                            .heightIn(min = 42.dp)
                            .then(if (leftTag != null) Modifier.testTag(leftTag) else Modifier),
                    ) {
                        Text(leftLabel, color = Pc.TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                } else {
                    Spacer(Modifier.width(64.dp))
                }
                Text(
                    title,
                    color = Pc.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (rightLabel != null && onRightClick != null) {
                    Button(
                        onClick = onRightClick,
                        colors = ButtonDefaults.buttonColors(containerColor = Pc.Accent),
                        shape = RoundedCornerShape(9.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier
                            .heightIn(min = 36.dp)
                            .then(if (rightTag != null) Modifier.testTag(rightTag) else Modifier),
                    ) {
                        Text(rightLabel, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Spacer(Modifier.width(64.dp))
                }
            }
            HorizontalDivider(color = Pc.Divider, thickness = 0.6.dp)
            content()
        }
    }
}
