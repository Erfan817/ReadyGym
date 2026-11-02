package com.readygym.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * The state → motion table from the review document, implemented as components.
 *
 * Every animation here is driven by one [CoachUiState]; none of them reads `reply`/`busy` directly.
 * Where a component needs something from the conversation it is passed in, so this file cannot
 * become a second state source. All durations and curves come from [SunnyMotion], and every
 * component honours [MotionSettings.reduceMotion] by snapping to its end value.
 */

// ---------------------------------------------------------- THINKING / TOOL_RUNNING

/**
 * Three-dot "coach is typing" wave.
 *
 * One [rememberInfiniteTransition] drives a phase; each dot samples a shifted sine of it, so the
 * stagger is a phase offset (exact, and cheap) rather than three animations started at different
 * times. The dots only touch `graphicsLayer`: no relayout, no per-frame recomposition of the row.
 *
 * Under reduced motion the dots sit still at a readable opacity. Nothing is lost, because the line
 * of text next to them still says what is happening.
 */
@Composable
internal fun TypingDots(modifier: Modifier = Modifier, active: Boolean = true) {
    val motion = LocalMotionSettings.current
    if (!active || motion.reduceMotion) {
        Row(modifier.semantics { contentDescription = "教练正在回复" }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) {
                Box(Modifier.size(DotSize).clip(CircleShapeBrush).background(ReadyGymColors.FaintText))
            }
        }
        return
    }
    val transition = rememberInfiniteTransition(label = "typing-wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = SunnyMotion.typingWave(),
            repeatMode = RepeatMode.Restart,
        ),
        label = "typing-phase",
    )
    val density = LocalDensity.current
    val lift = with(density) { DotLift.toPx() }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            // 0.9 rad apart: visibly sequential, still one wave and not a metronome.
            val wave = ((sin(phase - index * 0.9f - PI.toFloat() / 2f) + 1f) / 2f)
            Box(
                Modifier
                    .size(DotSize)
                    .graphicsLayer {
                        scaleX = 0.6f + 0.4f * wave
                        scaleY = 0.6f + 0.4f * wave
                        translationY = -lift * wave
                        alpha = 0.55f + 0.45f * wave
                    }
                    .clip(CircleShapeBrush)
                    .background(ReadyGymColors.MutedText),
            )
        }
    }
}

private val DotSize = 6.dp
private val DotLift = 3.dp
private val CircleShapeBrush = androidx.compose.foundation.shape.CircleShape

/**
 * Placeholder for a card that is still being drafted. Same geometry as the real card (radius,
 * padding, leading bar width) so the layout does not jump when the content replaces it.
 *
 * The sweep is one gradient position animating inside `background`: it repaints, it does not
 * remeasure. It is an `InfiniteTransition` rather than a hand-rolled `while (true) { animateTo }`
 * loop for a concrete reason: Compose's test/idling machinery knows infinite transitions are
 * allowed to keep running, whereas a manual loop keeps every frame "pending" forever and makes the
 * app fail its own `waitForIdle` (and, on device, keeps the choreographer awake).
 */
@Composable
internal fun SkeletonCard(modifier: Modifier = Modifier, lines: Int = 3) {
    val motion = LocalMotionSettings.current
    val sweep = if (motion.reduceMotion) 0.35f else {
        val transition = rememberInfiniteTransition(label = "skeleton-sweep")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(animation = SunnyMotion.skeletonSweep(), repeatMode = RepeatMode.Restart),
            label = "sweep",
        )
        value
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(GymShapes.Xl)
            .background(ReadyGymColors.Surface)
            .padding(GymSpacing.Xl)
            .semantics {
                contentDescription = "正在整理你的资料卡片"
                liveRegion = LiveRegionMode.Polite
            },
        verticalArrangement = Arrangement.spacedBy(GymSpacing.Md),
    ) {
        SkeletonBar(fraction = 0.55f, sweep = sweep)
        repeat(lines) { index ->
            SkeletonBar(fraction = if (index == lines - 1) 0.72f else 1f, sweep = sweep)
        }
    }
}

@Composable
private fun SkeletonBar(fraction: Float, sweep: Float) {
    val edge = (sweep + 0.22f).coerceAtMost(1f)
    Box(
        Modifier
            .fillMaxWidth(fraction)
            .height(14.dp)
            .clip(GymShapes.Xs)
            .background(
                Brush.horizontalGradient(
                    0f to ReadyGymColors.Sunken,
                    sweep.coerceIn(0f, 0.999f) to ReadyGymColors.Sunken.copy(alpha = 0.30f),
                    edge to ReadyGymColors.Sunken,
                )
            ),
    )
}

// ---------------------------------------------------------------- PROPOSING

/**
 * Card entrance: a fade while rising from below, on the standard curve.
 *
 * `AnimatedContent` with an explicit `transitionSpec`, because the default fade+scale is forbidden
 * here: the movement direction carries meaning (the card *arrived* from the conversation above),
 * whereas a scale would read as a popup that appeared from nowhere.
 *
 * `SizeTransform(clip = false)` lets the card grow without a hard clip during the transition.
 */
@Composable
internal fun <T> ProposingEnter(target: T, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    val motion = LocalMotionSettings.current
    val entranceOffset = with(LocalDensity.current) { SunnyMotion.EntranceOffset.roundToPx() }
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = {
            if (motion.reduceMotion) {
                fadeIn(animationSpec = SunnyMotion.instant()) togetherWith
                    fadeOut(animationSpec = SunnyMotion.instant())
            } else {
                (fadeIn(animationSpec = SunnyMotion.tweenStandard()) + slideInVertically(
                    animationSpec = SunnyMotion.tweenStandard(),
                    initialOffsetY = { entranceOffset },
                )) togetherWith fadeOut(animationSpec = SunnyMotion.tweenShort()) using SizeTransform(clip = false)
            }
        },
        label = "proposing-entrance",
    ) { value -> content(value) }
}

// ------------------------------------------------------------ AWAITING_CONFIRM

/**
 * Draw attention once when confirmation becomes available, then remain fully readable.
 */
@Composable
internal fun ConfirmAttention(enabled: Boolean, modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
    val motion = LocalMotionSettings.current
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(enabled, motion.reduceMotion) {
        alpha.snapTo(1f)
        if (enabled && !motion.reduceMotion) {
            alpha.snapTo(0.7f)
            alpha.animateTo(1f, SunnyMotion.tweenShort())
        }
    }
    content(modifier.graphicsLayer { this.alpha = alpha.value })
}

// ---------------------------------------------------------------- SUCCESS

/**
 * One-shot celebration: 1 → 1.06 → 1 on [SunnyMotion.springStandard].
 *
 * [trigger] is the thing whose arrival is being celebrated (the confirmed plan revision); passing
 * the same trigger twice does not replay it. Under reduced motion nothing plays and the caller's
 * haptic still fires, so the confirmation is acknowledged either way. No confetti, by design.
 */
@Composable
internal fun CelebrationScale(trigger: Any?, modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
    val motion = LocalMotionSettings.current
    val scale = remember { Animatable(1f) }
    LaunchedEffect(trigger, motion.reduceMotion) {
        if (trigger == null || motion.reduceMotion) return@LaunchedEffect
        scale.snapTo(1f)
        scale.animateTo(SunnyMotion.CelebrationScale, animationSpec = SunnyMotion.springStandard())
        scale.animateTo(1f, animationSpec = SunnyMotion.springStandard())
    }
    content(
        modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
    )
}

// ---------------------------------------------------- FAIL / RULE_FALLBACK

/**
 * A single horizontal shake of [SunnyMotion.ShakeDistance], played once per [trigger].
 *
 * The amplitude is deliberately tiny: it marks *which* element failed. A large shake reads as the
 * whole screen rejecting the user, which is not what "the model returned a bad reply" deserves.
 */
@Composable
internal fun ShakeOnce(trigger: Any?, modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
    val motion = LocalMotionSettings.current
    val offset = remember { Animatable(0f) }
    val distance = with(LocalDensity.current) { SunnyMotion.ShakeDistance.toPx() }
    LaunchedEffect(trigger, motion.reduceMotion) {
        if (trigger == null || motion.reduceMotion) return@LaunchedEffect
        offset.snapTo(0f)
        offset.animateTo(-distance, animationSpec = SunnyMotion.tweenShort())
        offset.animateTo(distance, animationSpec = SunnyMotion.tweenShort())
        offset.animateTo(0f, animationSpec = SunnyMotion.tweenShort())
    }
    content(modifier.graphicsLayer { translationX = offset.value })
}

// ---------------------------------------------------------------- RISK_BLOCKED

/**
 * The serious card: a plain fade on the standard curve.
 *
 * No spring, no overshoot, no loop, and the caller fires one `reject()` haptic. Risk is the state
 * where motion must not look playful — a bouncing card next to "请停止训练并及时就医" would be
 * actively wrong.
 */
@Composable
internal fun RiskCardEntrance(modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
    val motion = LocalMotionSettings.current
    val alpha = remember { Animatable(if (motion.reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!motion.reduceMotion) alpha.animateTo(1f, animationSpec = SunnyMotion.tweenStandard())
    }
    content(modifier.graphicsLayer { this.alpha = alpha.value })
}
