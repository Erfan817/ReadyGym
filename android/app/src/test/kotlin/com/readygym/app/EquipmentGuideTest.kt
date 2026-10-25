package com.readygym.app

import com.readygym.core.exercise.ExerciseLibrary
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Catalog integrity and matching for the equipment guide. The library is parsed from the same
 * bundled asset the app ships, so a guide page can never link to an exercise id that does not
 * exist when the user taps it.
 */
class EquipmentGuideTest {
    private val library = ExerciseLibrary.parse(File("src/main/assets/exercise-library.json").readText())

    @Test fun everyGuideHasCompleteContentAndUniqueId() {
        assertEquals(EquipmentGuideCatalog.all.size, EquipmentGuideCatalog.all.map { it.id }.distinct().size)
        EquipmentGuideCatalog.all.forEach { item ->
            assertTrue(item.id, item.name.isNotBlank())
            assertTrue("${item.id} needs setup steps", item.setupSteps.isNotEmpty())
            assertTrue("${item.id} needs usage steps", item.usageSteps.size >= 3)
            assertTrue("${item.id} needs safety notes", item.safety.isNotEmpty())
            assertTrue("${item.id} needs common mistakes", item.mistakes.isNotEmpty())
        }
    }

    @Test fun relatedExercisesAllExistInTheBundledLibrary() {
        assertEquals(emptyList<String>(), EquipmentGuideCatalog.unknownExerciseIds(library))
    }

    @Test fun searchMatchesNameAliasAndCategoryCaseInsensitively() {
        assertTrue(EquipmentGuideCatalog.search("下拉").any { it.id == "lat-pulldown" })
        assertTrue(EquipmentGuideCatalog.search("lat pulldown").any { it.id == "lat-pulldown" })
        assertTrue(EquipmentGuideCatalog.search("倒蹬").any { it.id == "leg-press" })
        assertTrue(EquipmentGuideCatalog.search("跑步").any { it.id == "treadmill" })
        assertTrue(EquipmentGuideCatalog.search("背阔肌").any { it.id == "lat-pulldown" })
        assertEquals(EquipmentGuideCatalog.all.size, EquipmentGuideCatalog.search("  ").size)
    }

    @Test fun categoryFilterNarrowsWithinTheQuery() {
        val cardio = EquipmentGuideCatalog.search("", EquipmentCategory.CARDIO)
        assertTrue(cardio.all { it.category == EquipmentCategory.CARDIO })
        assertTrue(cardio.any { it.id == "treadmill" })
        assertFalse(EquipmentGuideCatalog.search("", EquipmentCategory.CABLE).any { it.id == "treadmill" })
    }

    @Test fun highRiskBarbellStationsSayWhyNovicePlansExcludeThem() {
        val rack = EquipmentGuideCatalog.find("barbell-rack")!!
        val bench = EquipmentGuideCatalog.find("bench-press")!!
        assertFalse(rack.beginnerFriendly)
        assertFalse(bench.beginnerFriendly)
        assertNotNull(rack.beginnerNote)
        assertNotNull(bench.beginnerNote)
        // Their related lifts are exactly the moves marked HIGH_RISK in the exercise library.
        listOf(rack, bench).forEach { station ->
            station.relatedExerciseIds.forEach { id ->
                val ex = library[id]!!
                if (ex.equipment == com.readygym.core.Equipment.BARBELL) {
                    assertEquals("${station.id} → $id", com.readygym.core.exercise.ExerciseRisk.HIGH_RISK, ex.risk)
                }
            }
        }
    }

    @Test fun everyCategoryIsRepresented() {
        EquipmentCategory.entries.forEach { category ->
            assertTrue("$category has no guide", EquipmentGuideCatalog.all.any { it.category == category })
        }
    }

    @Test fun demoGifsAreSafeRelativePathsAndCoverAlmostEveryStation() {
        val withDemo = EquipmentGuideCatalog.all.filter { it.demoGif != null }
        // Every station except 划船机 has an honest match in the dataset.
        assertEquals(EquipmentGuideCatalog.all.size - 1, withDemo.size)
        assertNull(EquipmentGuideCatalog.find("rowing-machine")!!.demoGif)
        withDemo.forEach { item ->
            val path = item.demoGif!!
            assertTrue("${item.id}: $path", path.endsWith(".gif"))
            assertFalse("${item.id}: $path", path.startsWith("/") || ".." in path || "\\" in path)
            assertTrue("${item.id}: $path", path.matches(Regex("[a-z0-9-]+/[a-z0-9-]+\\.gif")))
        }
    }

    /** The vision prompt must name every machine, and replies map back to exactly one station. */
    @Test fun recognitionPromptListsEveryMachineAndRepliesMatchThem() {
        val prompt = EquipmentGuideCatalog.recognitionPrompt()
        EquipmentGuideCatalog.all.forEach { assertTrue("${it.name} missing from prompt", it.name in prompt) }

        fun matched(reply: String) = EquipmentGuideCatalog.matchEquipment(reply)?.id
        assertEquals("chest-press", matched("这是一台坐姿推胸机，主要用于训练胸大肌。"))
        assertEquals("chest-press", matched("seated chest press machine"))
        assertEquals("leg-press", matched("看起来是倒蹬机"))
        assertEquals("lat-pulldown", matched("高位下拉机"))
        assertEquals("seated-row", matched("应该是坐姿划船机"))

        // 不确定 / off-list answers must not auto-select anything.
        assertNull(matched("不确定"))
        assertNull(matched("这张照片拍到了墙壁、地板和一面镜子，没有器械"))
        assertNull(matched(""))
    }

    /** Stations beyond the 16 guides still resolve to real library exercises with demos. */
    @Test fun extraMachinesMapToExistingLibraryExercises() {
        EquipmentGuideCatalog.extraMachines.forEach { machine ->
            assertTrue("${machine.label} needs keywords", machine.keywords.isNotEmpty())
            assertTrue("${machine.label} needs exercises", machine.exerciseIds.isNotEmpty())
            assertTrue("${machine.label} caps its suggestions", machine.exerciseIds.size <= 4)
            machine.exerciseIds.forEach { id ->
                assertTrue("${machine.label}: exercise $id not in library", library[id] != null)
            }
        }
        // And no two machines claim the same leading keyword: a reply must be unambiguous.
        val allKeywords = EquipmentGuideCatalog.extraMachines.flatMap { it.keywords }
        assertEquals(allKeywords.size, allKeywords.distinct().size)
    }

    @Test fun freeFormMachineRepliesMatchExtraMachines() {
        fun extra(reply: String) = EquipmentGuideCatalog.matchExtraMachine(reply)?.label
        assertEquals("蝴蝶机（夹胸器）", extra("这是一台蝴蝶机"))
        assertEquals("史密斯机", extra("史密斯深蹲架"))
        assertEquals("提踵机", extra("站姿提踵机"))
        assertEquals("辅助引体机", extra(" assisted pull-up machine "))
        assertNull(extra("不确定"))
        assertNull(extra("这是跑步机旁的储物柜"))
        assertNull(extra(""))
    }
}
