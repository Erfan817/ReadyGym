package com.readygym.app

import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView

/**
 * Neutral-first palette with one saturated accent.
 *
 * Deliberately not the previous warm-cream scheme: the surface is a cool off-white, panels are
 * pure white and separated by tone rather than borders, and green is the only colour carrying
 * meaning (selected, active, primary action). Text is near-black; secondary text is a real grey
 * rather than a tinted one, so contrast stays high on both white and the page background.
 *
 * Colour is never the only signal for state: selected items also carry weight, a check mark or a
 * label, because a green dot alone is unreadable for a colour-blind user and in greyscale.
 */
internal object ReadyGymColors {
    /** Page background: cool off-white. */
    val Background = Color(0xFFF3F2F7)
    /** Panels and cards. */
    val Surface = Color(0xFFFFFFFF)
    /** Inset blocks (a card inside a card), dividers, disabled fills. */
    val Sunken = Color(0xFFECECEE)
    /** Very light accent wash for selected rows. */
    val AccentWash = Color(0xFFE8F8ED)

    val Text = Color(0xFF111114)
    /** 5.96:1 on white, 5.42:1 on the accent wash, 5.05:1 on the inset block. */
    val MutedText = Color(0xFF63636A)
    /**
     * Decorative only: 3.25:1 on white, so it must never be used for an interactive edge or for
     * text that carries meaning. The theme test asserts only MutedText and above.
     */
    val FaintText = Color(0xFF8E8E95)

    /** The one saturated colour: selection, active navigation, primary action. */
    val Accent = Color(0xFF34C85A)
    val OnAccent = Color(0xFF06340F)
    /** Accent that passes text contrast on white, the page and the wash (6.19 / 5.56 / 5.62). */
    val AccentText = Color(0xFF16702F)

    val Action = Color(0xFF111114)
    val OnAction = Color(0xFFFFFFFF)

    /**
     * Control outlines: 3.71:1 on white, 3.33:1 on the page background, 3.14:1 on the inset block.
     *
     * A decorative hairline is drawn at low alpha instead — this token is only for the edges that
     * carry meaning (an input box, an unselected radio), so it has to clear 3:1 everywhere.
     */
    val Outline = Color(0xFF84848D)
    val Error = Color(0xFFC22B26)
    val ErrorSurface = Color(0xFFFDECEC)
    val Warning = Color(0xFF9A5B00)
    val WarningSurface = Color(0xFFFFF4E0)

    // Legacy aliases kept so existing screens compile unchanged during the migration.
    val SelectedSurface = AccentWash
    val AccentSurface = AccentWash
}

@Composable
fun ReadyGymTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    // `Animator duration scale = 0` is the accessibility "remove animations" switch. Reading it
    // once here means every motion component gets the same answer without each one querying
    // Settings; when the setting changes the activity is recreated by the system.
    val reduceMotion = remember(view) {
        Settings.Global.getFloat(view.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    CompositionLocalProvider(LocalMotionSettings provides MotionSettings(reduceMotion)) {
        MaterialTheme(
        colorScheme = lightColorScheme(
            // `primary` is the brand green but it is a *fill* colour: at 2.2:1 it is unreadable as
            // text or as an outline on white, so `primary` is only used behind dark content. Any
            // accent used for text or a control edge goes through `tertiary` (6.19:1).
            primary = ReadyGymColors.AccentText,
            onPrimary = ReadyGymColors.OnAction,
            primaryContainer = ReadyGymColors.AccentWash,
            onPrimaryContainer = ReadyGymColors.AccentText,
            secondary = ReadyGymColors.Text,
            onSecondary = ReadyGymColors.OnAction,
            secondaryContainer = ReadyGymColors.Sunken,
            onSecondaryContainer = ReadyGymColors.Text,
            tertiary = ReadyGymColors.AccentText,
            onTertiary = ReadyGymColors.Surface,
            tertiaryContainer = ReadyGymColors.AccentWash,
            onTertiaryContainer = ReadyGymColors.AccentText,
            background = ReadyGymColors.Background,
            onBackground = ReadyGymColors.Text,
            surface = ReadyGymColors.Surface,
            onSurface = ReadyGymColors.Text,
            surfaceVariant = ReadyGymColors.Sunken,
            onSurfaceVariant = ReadyGymColors.MutedText,
            surfaceTint = ReadyGymColors.Accent,
            surfaceBright = ReadyGymColors.Surface,
            surfaceDim = ReadyGymColors.Background,
            surfaceContainerLowest = ReadyGymColors.Surface,
            surfaceContainerLow = ReadyGymColors.Surface,
            surfaceContainer = ReadyGymColors.Surface,
            surfaceContainerHigh = ReadyGymColors.Sunken,
            surfaceContainerHighest = ReadyGymColors.Sunken,
            outline = ReadyGymColors.Outline,
            outlineVariant = ReadyGymColors.Outline,
            inverseSurface = ReadyGymColors.Text,
            inverseOnSurface = ReadyGymColors.Surface,
            inversePrimary = ReadyGymColors.Accent,
            error = ReadyGymColors.Error,
            onError = ReadyGymColors.Surface,
            errorContainer = ReadyGymColors.ErrorSurface,
            onErrorContainer = ReadyGymColors.Error,
            scrim = ReadyGymColors.Text,
        ),
        content = content,
        )
    }
}
