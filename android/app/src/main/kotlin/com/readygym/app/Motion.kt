package com.readygym.app

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.DurationBasedAnimationSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The only place in the app where an animation may be defined.
 *
 * Two rules hold everywhere else in `com.readygym.app`:
 *  1. no `tween(` / `spring(` literal outside this file ([MotionTokenTest] enforces it);
 *  2. durations come from the two-step scale below. A gesture that must follow the finger uses
 *     `Animatable` + `snapTo/animateTo` instead of a duration, so an interrupted drag never lags.
 *
 * The scales are deliberately small. Duolingo's "silky" feel is not more animation; it is the same
 * three curves and two durations used consistently, plus an overshoot that is felt more than seen.
 */
object SunnyMotion {
    /** Confirmation ticks, colour fades, press feedback. */
    const val DurationShort = 200
    /** Card entrances and state transitions. Nothing longer: the coach loop should never wait. */
    const val DurationMedium = 300

    /** Decelerating curve for anything that arrives; matches Material's standard easing. */
    val EasingStandard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** For movement that both starts and ends on screen (a shake, a swap). */
    val EasingEmphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Slight overshoot — the "bounce" on a success confirmation. */
    fun <T> springStandard(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow)

    /** Almost no overshoot — used where a value must read as settled (breathing button, dots). */
    fun <T> springSnappy(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)

    fun <T> tweenStandard(): FiniteAnimationSpec<T> = tween(durationMillis = DurationMedium, easing = EasingStandard)
    fun <T> tweenShort(): FiniteAnimationSpec<T> = tween(durationMillis = DurationShort, easing = EasingStandard)
    fun <T> instant(): FiniteAnimationSpec<T> = snap()

    /**
     * The looping specs live here too, so a component never has to write `tween(...)` by hand and
     * a reviewer can see every duration the app can spend in one file.
     */
    fun typingWave(): DurationBasedAnimationSpec<Float> = tween(durationMillis = WaveMillis, easing = LinearOutSlowInEasing)

    /** Skeleton sweep: slow and linear — a shimmer that eases reads as a progress bar. */
    fun skeletonSweep(): DurationBasedAnimationSpec<Float> =
        tween(durationMillis = DurationMedium * 4, easing = LinearOutSlowInEasing)

    /** The confirm button's breath. Reverse mode doubles it to ~600 ms per cycle. */
    fun confirmBreath(): DurationBasedAnimationSpec<Float> = tween(durationMillis = DurationMedium, easing = EasingStandard)

    /** One dot wavelength; even thirds read as a metronome rather than a wave. */
    const val WaveMillis = 620

    /** The card entrance: slide up by this much while fading in. */
    val EntranceOffset: Dp = 24.dp
    /** A failed/blocked message moves this far, once. The review gate's original spec was 4dp. */
    val ShakeDistance: Dp = 4.dp
    /** The one-shot celebration scale. Bigger than this reads as a game, not a fitness app. */
    const val CelebrationScale = 1.06f
}

/**
 * Resolved motion settings for the whole app.
 *
 * `Animator duration scale = 0` is an accessibility setting, and also how the review gate checks
 * "所有动效在关闭动画下不阻塞功能". Every animated component reads [reduceMotion] and jumps to its
 * end value instead of playing; nothing is hidden or disabled because of it.
 */
data class MotionSettings(val reduceMotion: Boolean) {
    companion object {
        val Full = MotionSettings(reduceMotion = false)
        val Reduced = MotionSettings(reduceMotion = true)
    }
}

/** Set once in [ReadyGymTheme] from the system animator duration scale. */
val LocalMotionSettings = compositionLocalOf { MotionSettings.Full }
