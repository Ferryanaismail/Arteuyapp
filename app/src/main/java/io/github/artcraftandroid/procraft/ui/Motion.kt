package io.github.artcraftandroid.procraft.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically

/** Interface transition timings matching ProCraft's feel. */
object Motion {
    val EaseInOut: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
    val EaseOut: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)
    val Settle: Easing = CubicBezierEasing(0.2f, 0.8f, 0.5f, 1.0f)

    fun fade(): FiniteAnimationSpec<Float> = tween(220, easing = EaseInOut)
    fun bars(): FiniteAnimationSpec<Float> = tween(300, easing = EaseInOut)
    fun panelFade(): FiniteAnimationSpec<Float> = tween(220, easing = EaseInOut)

    fun toolbarIn(): EnterTransition =
        slideInVertically(tween(260, easing = Settle)) { it } + fadeIn(tween(180))

    fun toolbarOut(): ExitTransition =
        slideOutVertically(tween(120, easing = EaseInOut)) { it } + fadeOut(tween(100))

    fun sideBarIn(right: Boolean): EnterTransition =
        slideInHorizontally(tween(200, easing = EaseOut)) { if (right) it else -it }

    fun sideBarOut(right: Boolean): ExitTransition =
        slideOutHorizontally(tween(130, easing = EaseInOut)) { if (right) it else -it }
}
