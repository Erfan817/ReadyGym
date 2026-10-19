package com.readygym.core.exercise

import com.readygym.core.Equipment
import com.readygym.core.Location
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ExerciseLibraryTest {
    private val library = ExerciseLibrary.parse(File("../app/src/main/assets/exercise-library.json").readText())

    @Test fun bundledLibraryParsesWithUniqueIdsAndChineseContent() {
        assertTrue(library.exercises.size >= 40)
        library.exercises.forEach {
            assertTrue(it.id, it.name.isNotBlank() && it.tips.size >= 2)
            assertEquals("exercises/${it.id}.gif", it.gifAsset)
        }
        assertEquals("哑铃高脚杯深蹲", library["goblet-squat"]?.name)
        assertFalse("not-an-exercise" in library)
    }

    @Test fun homeBodyweightUserOnlySeesHomeBodyweightMoves() {
        val home = library.available(Location.HOME, setOf(Equipment.BODYWEIGHT))
        assertTrue(home.isNotEmpty())
        assertTrue(home.all { it.equipment == Equipment.BODYWEIGHT && Location.HOME in it.locations })
        assertFalse(home.any { it.id == "pull-up" })
    }

    @Test fun gymWithDumbbellsIncludesBodyweightButNotMachines() {
        val gym = library.available(Location.GYM, setOf(Equipment.DUMBBELL))
        assertTrue(gym.any { it.equipment == Equipment.DUMBBELL })
        assertTrue(gym.any { it.equipment == Equipment.BODYWEIGHT })
        assertFalse(gym.any { it.equipment == Equipment.MACHINE || it.equipment == Equipment.BARBELL })
    }

    @Test fun promptCatalogHasOneLinePerExerciseStartingWithId() {
        val lines = library.exercises.take(3).promptCatalog().lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("${library.exercises[0].id}|"))
    }

    @Test fun duplicateIdsAreRejected() {
        val ex = library.exercises.first()
        assertThrows(IllegalArgumentException::class.java) { ExerciseLibrary(1, "", listOf(ex, ex)) }
    }

    @Test fun onlyExternalLoadIsLoadable() {
        assertTrue(library["goblet-squat"]!!.loadable)
        assertFalse(library["plank"]!!.loadable)
        assertEquals(Measure.SECONDS, library["plank"]!!.measure)
    }
}
