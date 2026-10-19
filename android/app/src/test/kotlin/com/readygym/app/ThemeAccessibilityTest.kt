package com.readygym.app

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xmlpull.v1.XmlPullParser

/**
 * Locks the palette's *accessibility*, not its taste.
 *
 * The earlier version of this file also asserted the specific hues — warm background, orange
 * primary — which meant a deliberate restyle failed as if it were a defect. Hue is a design
 * decision that belongs to the product; contrast, legibility and "the scheme stays light in night
 * mode" are the invariants that must not regress, and those are what is asserted here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun inputAndUnselectedCardOutlinesRemainVisible() {
        lateinit var palette: ColorScheme
        compose.setContent { ReadyGymTheme { palette = MaterialTheme.colorScheme } }
        compose.waitForIdle()
        for (background in listOf(palette.surface, palette.background)) {
            val ratio = contrast(palette.outline, background)
            assertTrue("Control outline contrast must be at least 3:1, was $ratio", ratio >= 3f)
        }
    }

    @Test fun lightThemeKeepsTextAndSelectionReadable() {
        lateinit var palette: ColorScheme
        compose.setContent { ReadyGymTheme { palette = MaterialTheme.colorScheme } }
        compose.waitForIdle()

        // The scheme is light and neutral: a pale page, white cards, and a single saturated accent.
        assertTrue("Page background must be light", palette.background.luminance() >= .85f)
        assertTrue("Cards must be white", palette.surface.luminance() >= .98f)
        // `primary` is the accent as text/edge, so it must be the legible dark green, not the
        // 2.2:1 brand fill. The fill lives in ReadyGymColors.Accent and is checked separately.
        assertTrue("The primary accent must be green",
            palette.primary.green > palette.primary.red && palette.primary.green > palette.primary.blue)
        assertTrue("The primary accent must be dark enough to carry text",
            contrast(palette.primary, palette.surface) >= 4.5f)
        assertTrue("The accent wash must stay pale", palette.primaryContainer.luminance() >= .85f)
        assertTrue("The brand fill must stay a saturated green",
            ReadyGymColors.Accent.green > ReadyGymColors.Accent.red &&
                ReadyGymColors.Accent.green > ReadyGymColors.Accent.blue)

        val textPairs = linkedMapOf(
            "body/background" to (palette.onBackground to palette.background),
            "body/card" to (palette.onSurface to palette.surface),
            "body/selected card" to (palette.onSurface to palette.surfaceVariant),
            "secondary/background" to (palette.onSurfaceVariant to palette.background),
            "secondary/card" to (palette.onSurfaceVariant to palette.surface),
            "secondary/selected card" to (palette.onSurfaceVariant to palette.surfaceVariant),
            "primary button" to (palette.onPrimary to palette.primary),
            "accent label/background" to (palette.tertiary to palette.background),
            "accent label/card" to (palette.tertiary to palette.surface),
            "accent label/selected card" to (palette.tertiary to palette.surfaceVariant),
            "error/background" to (palette.error to palette.background),
        )
        textPairs.forEach { (role, pair) ->
            val ratio = contrast(pair.first, pair.second)
            assertTrue("$role text contrast must be at least 4.5:1, was $ratio", ratio >= 4.5f)
            println("CONTRAST text $role=$ratio")
        }
        for (background in listOf(palette.background, palette.surface, palette.surfaceVariant)) {
            for (outline in listOf(palette.outline, palette.primary, palette.tertiary)) {
                val ratio = contrast(outline, background)
                assertTrue("Control/selected outline contrast must be at least 3:1, was $ratio", ratio >= 3f)
                println("CONTRAST control=$ratio")
            }
        }
    }

    @Test
    @Config(qualifiers = "night")
    fun systemNightModeDoesNotReintroduceCharcoalTheme() {
        lateinit var palette: ColorScheme
        compose.setContent { ReadyGymTheme { palette = MaterialTheme.colorScheme } }
        compose.waitForIdle()
        assertTrue("The accepted light theme must stay light even in system night mode",
            palette.background.luminance() >= .85f)
        assertTrue("Night mode must keep text readable", contrast(palette.onSurface, palette.surface) >= 4.5f)
    }

    @Test fun launcherAndEquipmentMatchLightSurfaces() {
        // The launcher background is the icon artwork's own orange backdrop, so the adaptive mask
        // never shows a seam between the art and the padding around it.
        val background = vectorColors(R.drawable.ic_launcher_background).single()
        assertTrue("Launcher background must be the artwork's saturated orange",
            background.red > .9f && background.green in .35f..0.55f && background.blue < .1f)
        for (drawable in listOf(R.drawable.equipment_bodyweight, R.drawable.equipment_dumbbell, R.drawable.equipment_barbell, R.drawable.equipment_machine)) {
            val colors = vectorColors(drawable)
            assertTrue("Each equipment illustration must contain recognizable geometry", colors.size >= 6)
            for (color in colors) {
                val ratio = contrast(color, ReadyGymColors.SelectedSurface)
                assertTrue("Equipment lines/fills must remain visible on light cards, was $ratio", ratio >= 3f)
            }
        }
    }

    private fun vectorColors(drawable: Int): List<Color> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val colors = mutableListOf<Color>()
        context.resources.getXml(drawable).use { parser ->
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "path") {
                    for (attribute in listOf("fillColor", "strokeColor")) {
                        val value = parser.getAttributeValue("http://schemas.android.com/apk/res/android", attribute)
                        if (value != null && value.startsWith("#")) {
                            val color = Color(android.graphics.Color.parseColor(value))
                            if (color.alpha > 0f) colors.add(color)
                        }
                    }
                }
                parser.next()
            }
        }
        return colors
    }

    private fun contrast(a: Color, b: Color): Float {
        val first = a.luminance()
        val second = b.luminance()
        return (maxOf(first, second) + .05f) / (minOf(first, second) + .05f)
    }
}
