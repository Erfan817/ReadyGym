package com.readygym.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.readygym.core.MuscleGroup
import com.readygym.core.MuscleTaxonomy
import com.readygym.core.exercise.label
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native graphics keeps the path/Region hit test real rather than relying on semantic clicks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MuscleBodyMapTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val selections = mutableListOf<MuscleGroup>()

    private fun show() {
        compose.runOnUiThread {
            compose.activity.setContent {
                ReadyGymTheme {
                    var selected by remember { mutableStateOf<MuscleGroup?>(null) }
                    MuscleBodyMap(selected, onSelect = {
                        selected = it
                        selections += it
                    }, modifier = Modifier.width(280.dp))
                }
            }
        }
    }

    /** Coordinates are in the artwork's 380-wide viewbox, independent of density and viewport. */
    private fun tapArtwork(x: Float, y: Float) {
        compose.onNodeWithTag("muscle-body-map").performTouchInput {
            val scale = width / 380f
            click(Offset(x * scale, y * scale))
        }
        compose.waitForIdle()
    }

    @Test fun directTapsSelectFrontChestAndBackGlutesButIgnoreBlankSpaceAndNeutralHead() {
        show()

        tapArtwork(75f, 85f)
        compose.runOnIdle { assertEquals(listOf(MuscleGroup.CHEST), selections) }
        compose.onNodeWithTag("muscle-map-chest").assertIsSelected()

        tapArtwork(275f, 195f)
        compose.runOnIdle { assertEquals(listOf(MuscleGroup.CHEST, MuscleGroup.GLUTES), selections) }
        compose.onNodeWithTag("muscle-map-glutes").assertIsSelected()

        tapArtwork(190f, 200f) // The gap between the front and back figures.
        tapArtwork(90f, 25f) // The head is decorative, not a muscle shortcut.
        compose.runOnIdle { assertEquals(listOf(MuscleGroup.CHEST, MuscleGroup.GLUTES), selections) }
    }

    @Test fun allEighteenMusclesExposeNamedAccessibleSelectionActions() {
        show()

        MuscleTaxonomy.DRAWABLE.forEach { muscle ->
            val node = compose.onNodeWithTag("muscle-map-${muscle.name.lowercase()}")
            node.assertContentDescriptionEquals(muscle.label).assertHasClickAction()
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            node.assertIsSelected()
        }
        compose.runOnIdle { assertEquals(MuscleTaxonomy.DRAWABLE, selections) }
    }
}
