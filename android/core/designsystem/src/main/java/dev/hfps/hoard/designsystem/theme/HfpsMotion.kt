package dev.hfps.hoard.designsystem.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween

/**
 * hfps motion: 150-250 ms, strong ease-out for things entering, only for what the user caused, and every transition
 * is a `tween` (interruptible: a new target retargets from the current value). Gestures follow the finger instead.
 */
object HfpsMotion {
    const val FAST = 150
    const val STANDARD = 200
    const val SLOW = 250

    /** Strong ease-out (`cubic-bezier(0.23, 1, 0.32, 1)`, the web kit's curve). */
    val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

    fun <T> fast() = tween<T>(durationMillis = FAST, easing = EaseOut)

    fun <T> standard() = tween<T>(durationMillis = STANDARD, easing = EaseOut)

    fun <T> slow() = tween<T>(durationMillis = SLOW, easing = EaseOut)
}
