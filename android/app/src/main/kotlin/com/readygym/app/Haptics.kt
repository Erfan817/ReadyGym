package com.readygym.app

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Haptics, chosen by meaning rather than by feel-of-the-day.
 *
 * The rule that matters: fire at the **start** of the interaction (on the tap), not when its
 * animation ends. A confirmation that buzzes 300 ms later reads as latency, and it would land after
 * `reduceMotion` had already skipped the animation — so it would disappear exactly for the users
 * who asked for less motion.
 *
 * Why the platform constants instead of `HapticFeedbackType.Confirm/DoubleTick`: those two only
 * exist from Compose UI 1.8, and this app is on BOM 2024.12.01 (Compose 1.7.6), whose
 * `HapticFeedbackType` exposes only `LongPress` and `TextHandleMove`. The semantic types the plan
 * asks for (`CONFIRM` / `REJECT`) do exist in the Android platform, from API 30, so they are used
 * there and mapped down to the closest type on API 26–29. `TextHandleMove` is never used as a tap
 * acknowledgement.
 */
interface Haptics {
    /** A commit that succeeded: profile saved, plan version adopted. */
    fun confirm()

    /** A refusal or a failure the user has to read. */
    fun reject()

    /** Ordinary selection / toggle. */
    fun tick()

    companion object {
        /** No-op: for tests, and for the first frame of the risk card. */
        val None: Haptics = object : Haptics {
            override fun confirm() = Unit
            override fun reject() = Unit
            override fun tick() = Unit
        }
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { ViewHaptics(view) }
}

internal class ViewHaptics(private val view: View) : Haptics {
    override fun confirm() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.VIRTUAL_KEY,
    )

    override fun reject() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT
        else HapticFeedbackConstants.LONG_PRESS,
    )

    override fun tick() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CLOCK_TICK
        else HapticFeedbackConstants.KEYBOARD_TAP,
    )

    /** `FLAG_IGNORE_GLOBAL_SETTING` is deliberately not passed: the system setting always wins. */
    private fun perform(type: Int) { view.performHapticFeedback(type) }
}
