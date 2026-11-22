package io.github.artcraftandroid.procraft.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

/**
 * ProCraft's color tokens & spacing metrics:
 * dark #1C1C1C canvas surround and frosted charcoal surfaces, with a Light Interface option.
 */
object Pc {
    var light by mutableStateOf(false)
    var accentOverride by mutableStateOf<Color?>(null)

    val Background: Color get() = if (light) Color(0xFFDADADC) else Color(0xFF1C1C1C)
    val GalleryBackground: Color get() = if (light) Color(0xFFE7E7EA) else Color(0xFF151517)
    val GalleryCell: Color get() = if (light) Color(0xFFF5F5F7) else Color(0xFF1F1F22)

    val Bar: Color get() = if (light) Color(0xFFEFEFF1) else Color(0xFF232325)
    val BarText: Color get() = if (light) Color(0xFF1A1A1C) else Color(0xFFF2F2F4)
    val BarGlyph: Color get() = if (light) Color(0xFF333338) else Color(0xFFD2D2D6)
    val ActionDisc: Color get() = if (light) Color(0xFFDFDFE4) else Color(0xFF303034)
    val ActionGlyph: Color get() = if (light) Color(0xFF222226) else Color(0xFFE2E2E6)
    val BarGlyphSelected: Color get() = Accent

    val SideBar: Color get() = if (light) Color(0xD9EAEAEF) else Color(0xD9262629)
    val SideGlyph: Color get() = if (light) Color(0xFF2A2A2E) else Color(0xFFE0E0E4)
    val SideGlyphMuted: Color get() = if (light) Color(0x552A2A2E) else Color(0x55E0E0E4)
    val SliderEdge: Color get() = if (light) Color(0x33000000) else Color(0x44FFFFFF)
    val ColorRing: Color get() = if (light) Color(0x66000000) else Color(0x88FFFFFF)

    val PanelBg: Color get() = if (light) Color(0xF5F4F4F7) else Color(0xF5222225)
    val PanelSurface: Color get() = if (light) Color(0xFFE5E5EA) else Color(0xFF2C2C30)
    val PanelCard: Color get() = if (light) Color(0xFFFFFFFF) else Color(0xFF2A2A2E)
    val PanelHeader: Color get() = if (light) Color(0xFFF9F9FB) else Color(0xFF28282C)
    val RowSelected: Color get() = Accent.copy(alpha = 0.24f)
    val Divider: Color get() = if (light) Color(0x1F000000) else Color(0x1FFFFFFF)

    val TextPrimary: Color get() = if (light) Color(0xFF141416) else Color(0xFFF4F4F6)
    val TextSecondary: Color get() = if (light) Color(0xFF636369) else Color(0xFF9C9CA3)
    val Accent: Color get() = accentOverride ?: Color(0xFF0A84FF)
    val Destructive: Color = Color(0xFFFF453A)
    val Warning: Color = Color(0xFFFF9F0A)
    val Success: Color = Color(0xFF30D158)

    val BarHeight = 48.dp
    val BarButtonHeight = 40.dp
    val GalleryWidth = 74.dp
    val ActionDiscSize = 32.dp
    val ColorDot = 26.dp

    val SideBarWidth = 50.dp
    val SideBarCorner = 16.dp
    val SliderWidth = 40.dp
    val SliderHeight = 175.dp
    val SliderCorner = 12.dp
    val SliderEdgeWidth = 1.dp
    val SideBarHeight = 528.dp
    val ModifySize = 24.dp
    val ModifyCorner = 5.dp
    val ModifyStroke = 2.dp

    val PanelCorner = 14.dp
    val PanelShape = RoundedCornerShape(PanelCorner)
    val PillShape = RoundedCornerShape(10.dp)
    val CardShape = RoundedCornerShape(10.dp)

    val TitleSize = 17.sp
    val HeadingSize = 15.sp
    val BodySize = 14.sp
    val CaptionSize = 12.sp

    fun barFit(maxWidth: Dp): BarFit {
        val compact = maxWidth < 600.dp
        val button = if (compact) 34.dp else 44.dp
        val margin = if (compact) 8.dp else 14.dp
        val actionGap = if (compact) 2.dp else 6.dp
        val toolGap = if (compact) 2.dp else 6.dp
        return BarFit(button, margin, actionGap, toolGap)
    }

    fun barGlyphScale(width: Dp): Float = (width / 44.dp).coerceIn(0.72f, 1f)

    fun sideTrackHeight(availableHeight: Dp): Dp {
        val maxShorter = ((availableHeight - 178.dp) / 2f).coerceIn(100.dp, SliderHeight)
        return maxShorter
    }
}

data class BarFit(
    val button: Dp,
    val margin: Dp,
    val actionGap: Dp,
    val toolGap: Dp,
)

@Composable
fun animateHighlight(lit: Boolean): Float {
    val value by animateFloatAsState(
        targetValue = if (lit) 1f else 0f,
        animationSpec = tween(140),
        label = "lit",
    )
    return value
}

object CanvasSpace {
    fun color(argb: Int): Color = Color(argb)

    fun argbFromHsv(h: Float, s: Float, v: Float, a: Float = 1f): Int {
        val hsv = floatArrayOf(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
        val rgb = android.graphics.Color.HSVToColor((a.coerceIn(0f, 1f) * 255f).toInt(), hsv)
        return rgb
    }

    fun hsvFromArgb(argb: Int): FloatArray {
        val out = FloatArray(3)
        android.graphics.Color.colorToHSV(argb, out)
        return out
    }

    fun hexString(argb: Int): String {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return String.format("#%02X%02X%02X", r, g, b)
    }

    fun parseHex(raw: String): Int? {
        val cleaned = raw.trim().removePrefix("#")
        return when (cleaned.length) {
            6 -> cleaned.toIntOrNull(16)?.or(0xFF000000.toInt())
            8 -> cleaned.toLongOrNull(16)?.toInt()
            else -> null
        }
    }

    fun contrastOn(argb: Int): Color {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val lum = 0.299f * r + 0.587f * g + 0.114f * b
        return if (lum > 0.58f) Color(0xFF141416) else Color(0xFFF6F6F8)
    }
}
