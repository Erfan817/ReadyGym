package com.readygym.core.exercise

import com.readygym.core.Equipment
import com.readygym.core.Location
import com.readygym.core.profile.Experience
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ExerciseLibraryTest {
    private val library = ExerciseLibrary.parse(File("../app/src/main/assets/exercise-library.json").readText())

    @Test fun bundledLibraryParsesWithUniqueIdsAndChineseContent() {
        assertTrue(library.exercises.size >= 200)
        library.exercises.forEach {
            assertTrue(it.id, it.name.isNotBlank() && it.tips.size >= 2)
            // Every exercise shows a demo from exactly one source: bundled asset or server path.
            assertTrue(it.id, it.gifAsset != null || it.gifPath != null)
            it.gifAsset?.let { a -> assertEquals(it.id, "exercises/${it.id}.gif", a) }
            it.gifPath?.let { p -> assertTrue(it.id, p.endsWith(".gif") && !p.startsWith("/") && ".." !in p) }
        }
        assertEquals("哑铃高脚杯深蹲", library["goblet-squat"]?.name)
        assertEquals(287, library.exercises.size)
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

    @Test fun barbellCompoundsAreMarkedHighRisk() {
        // Library v2: the original five plus the heavy barbell leg compounds added with the
        // 287-exercise expansion. Novices and returning users never see any of these.
        val highRisk = library.exercises.filter { it.risk == ExerciseRisk.HIGH_RISK }.map { it.id }.toSet()
        assertEquals(setOf(
            "barbell-bench-press", "barbell-squat", "barbell-deadlift", "barbell-rdl", "barbell-overhead-press",
            "barbell-front-squat", "barbell-hack-squat", "barbell-sumo-deadlift", "barbell-rack-pull",
            "barbell-overhead-squat", "barbell-narrow-stance-squat", "barbell-wide-squat"), highRisk)
        // Dumbbell and trap-bar versions stay available: lower absolute load, friendlier failure.
        assertEquals(ExerciseRisk.NORMAL, library["dumbbell-deadlift"]?.risk)
    }

    @Test fun beginnersNeverGetHighRiskOrAdvancedMoves() {
        val all = setOf(Equipment.BODYWEIGHT, Equipment.DUMBBELL, Equipment.BARBELL, Equipment.MACHINE)
        val beginner = library.availableFor(Location.GYM, all, Experience.BEGINNER)
        assertFalse(beginner.any { it.risk == ExerciseRisk.HIGH_RISK || it.level == ExerciseLevel.ADVANCED })
        assertFalse("barbell-deadlift" in beginner.map { it.id })
        assertFalse("pull-up" in beginner.map { it.id })
        // Normal-risk intermediate moves and beginner machines stay available.
        assertTrue(beginner.any { it.id == "push-up" })
        assertTrue(beginner.any { it.id == "lat-pulldown" })
    }

    @Test fun returningUsersKeepAdvancedButLoseHighRisk() {
        val all = setOf(Equipment.BODYWEIGHT, Equipment.BARBELL)
        val returning = library.availableFor(Location.GYM, all, Experience.RETURNING)
        assertTrue(returning.any { it.id == "pull-up" })
        assertFalse(returning.any { it.risk == ExerciseRisk.HIGH_RISK })
        assertTrue(library.availableFor(Location.GYM, all, Experience.EXPERIENCED).any { it.id == "barbell-deadlift" })
    }
}
