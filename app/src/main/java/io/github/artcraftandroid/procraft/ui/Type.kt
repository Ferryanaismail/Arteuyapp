package io.github.artcraftandroid.procraft.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private fun Context.interFamily(): FontFamily = FontFamily(
    Font("fonts/Inter-Regular.ttf", assets, FontWeight.Normal),
    Font("fonts/Inter-Medium.ttf", assets, FontWeight.Medium),
    Font("fonts/Inter-SemiBold.ttf", assets, FontWeight.SemiBold),
    Font("fonts/Inter-Bold.ttf", assets, FontWeight.Bold),
)

private val Tracking = (-0.035).em

private fun proCraftTypography(family: FontFamily): Typography {
    val d = Typography()
    fun TextStyle.t() = copy(fontFamily = family, letterSpacing = Tracking)
    return Typography(
        displayLarge = d.displayLarge.t(),
        displayMedium = d.displayMedium.t(),
        displaySmall = d.displaySmall.t(),
        headlineLarge = d.headlineLarge.t(),
        headlineMedium = d.headlineMedium.t(),
        headlineSmall = d.headlineSmall.t(),
        titleLarge = d.titleLarge.t(),
        titleMedium = d.titleMedium.t(),
        titleSmall = d.titleSmall.t(),
        bodyLarge = d.bodyLarge.t(),
        bodyMedium = d.bodyMedium.t(),
        bodySmall = d.bodySmall.t(),
        labelLarge = d.labelLarge.t(),
        labelMedium = d.labelMedium.t(),
        labelSmall = d.labelSmall.t(),
    )
}

@Composable
fun ProCraftTheme(light: Boolean = Pc.light, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val family = remember(context) { context.interFamily() }
    val base = TextStyle(fontFamily = family, fontSize = 15.sp, letterSpacing = Tracking)
    val scheme = if (light) {
        lightColorScheme(
            primary = Pc.Accent,
            surface = Pc.PanelBg,
            background = Pc.GalleryBackground,
            onSurface = Pc.TextPrimary,
            onBackground = Pc.TextPrimary,
        )
    } else {
        darkColorScheme(
            primary = Pc.Accent,
            surface = Pc.PanelBg,
            background = Pc.Background,
            onSurface = Pc.TextPrimary,
            onBackground = Pc.TextPrimary,
        )
    }
    MaterialTheme(colorScheme = scheme, typography = proCraftTypography(family)) {
        CompositionLocalProvider(LocalTextStyle provides base) {
            content()
        }
    }
}

@Composable
fun ClearBandShows(background: Color, darkBar: Boolean = Pc.light) {
    val view = LocalView.current
    SideEffect {
        val activity = view.context.findActivity() ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.isAppearanceLightStatusBars = darkBar
        controller.isAppearanceLightNavigationBars = darkBar
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
