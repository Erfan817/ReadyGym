package com.readygym.core.ai

import com.readygym.core.Equipment
import com.readygym.core.Gender
import com.readygym.core.Goal
import com.readygym.core.Location
import com.readygym.core.exercise.ExerciseLibrary
import com.readygym.core.plan.PlanDay
import com.readygym.core.plan.PlanItem
import com.readygym.core.plan.WorkoutPlan
import com.readygym.core.profile.Experience
import com.readygym.core.profile.UserProfile
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ConversationPlanContractTest {
    private val library = ExerciseLibrary.parse(File("../app/src/main/assets/exercise-library.json").readText())
    private val profile = UserProfile(24, 175.0, 70.0, Gender.MALE, Goal.MUSCLE, Location.HOME,
        setOf(Equipment.DUMBBELL), 2, 45, Experience.BEGINNER)
    private val current = WorkoutPlan(4, 123L, "原安排", "上下肢分化", 2, listOf(
        PlanDay("下肢", items = listOf(PlanItem("goblet-squat", 3, 8, 12, loadKg = 12.0),
            PlanItem("plank", 2, seconds = 30))),
        PlanDay("上肢", items = listOf(PlanItem("push-up", 2, 8, 12),
            PlanItem("dumbbell-row", 3, 10, 10, loadKg = 15.0))),
    ))
    private val contract = ConversationPlanContract(library, profile, current)
    private val fullDays = """[
        {"title":"下肢","items":[{"id":"goblet-squat","sets":3,"reps":[8,12],"kg":15},{"id":"plank","sets":2,"seconds":30}]},
        {"title":"上肢","items":[{"id":"push-up","sets":2,"reps":[8,12]},{"id":"dumbbell-curl","sets":3,"reps":[10],"kg":15}]}
    ]""".trimIndent()

    private fun replacement(day: Int, from: String, to: String): String =
        """{"day":$day,"from":"$from","to":"$to"}"""

    private fun options(vararg option: String, status: String = "collecting"): String =
        """{"status":"$status","message":"选择后确认。","plan_options":[${option.joinToString(",")}]}"""

    private fun replacements(vararg replacement: String, title: String = "替换方案") =
        """{"title":"$title","detail":"可使用现有器械。","replacements":[${replacement.joinToString(",")}]}"""

    private fun invalid(content: String): ConversationPlanException =
        assertThrows(ConversationPlanException::class.java) { contract.parseOptions(content) }

    @Test fun ordinaryChatDoesNotCreateAPlanDraft() {
        assertTrue(contract.parseOptions("""{"status":"collecting","message":"今天状态怎么样？"}""").isEmpty())
    }

    @Test fun promptIncludesCurrentWholeWeekAndOnlyEligibleAlternatives() {
        val prompt = contract.prompt()
        assertTrue(prompt.contains("权威版本 v4"))
        assertTrue(prompt.contains("goblet-squat"))
        assertTrue(prompt.contains("dumbbell-row"))
        assertTrue(prompt.contains("glute-bridge|"))
        assertFalse(prompt.contains("leg-press|"))
        assertFalse(prompt.contains("barbell-deadlift|"))
        assertTrue(prompt.contains("用户说没有某台具体器械"))
        assertTrue(prompt.contains("不能说已经保存"))
    }

    @Test fun oneOptionCanReplaceExercisesAcrossTheWholeWeekWithoutChangingTheBase() {
        val option = contract.parseOptions(options(replacements(
            replacement(0, "goblet-squat", "glute-bridge"),
            replacement(1, "dumbbell-row", "dumbbell-curl"),
        ))).single()
        assertEquals("glute-bridge", option.plan.days[0].items[0].exerciseId)
        assertEquals("dumbbell-curl", option.plan.days[1].items[1].exerciseId)
        assertEquals(3, option.plan.days[1].items[1].sets)
        assertEquals(10, option.plan.days[1].items[1].repsMin)
        assertNull("a replacement must never inherit 15kg from another exercise", option.plan.days[1].items[1].loadKg)
        assertEquals(current.days[0].items[1], option.plan.days[0].items[1])
        assertEquals(4, option.plan.version)
        assertEquals(4, option.plan.progressionBaseVersion)
        assertEquals("goblet-squat", current.days[0].items[0].exerciseId)
        assertEquals("聊天调整：替换方案", option.plan.reason)
    }

    @Test fun distinctAlternativesAreAvailableForUserSelectionWithoutWriting() {
        val alternatives = contract.parseOptions(options(
            replacements(replacement(0, "goblet-squat", "glute-bridge"), title = "臀桥"),
            replacements(replacement(0, "goblet-squat", "dumbbell-lunge"), title = "哑铃弓步"),
        ))
        assertEquals(listOf("臀桥", "哑铃弓步"), alternatives.map { it.title })
        assertEquals(4, current.version)
    }

    @Test fun replacementChangesTheMeasureSafelyInBothDirections() {
        val plan = contract.parseOptions(options(replacements(
            replacement(0, "goblet-squat", "side-plank"),
            replacement(0, "plank", "glute-bridge"),
        ))).single().plan
        val timed = plan.days[0].items[0]
        assertNull(timed.repsMin)
        assertNull(timed.repsMax)
        assertEquals(20, timed.seconds)
        val reps = plan.days[0].items[1]
        assertNull(reps.seconds)
        assertEquals(8, reps.repsMin)
        assertEquals(12, reps.repsMax)
    }

    @Test fun fullWeekCanChangePrescriptionsAndExercisesButNewExerciseStartsWithoutLoad() {
        val plan = contract.parseOptions(options("""{"title":"整周调整","detail":"改组次并换动作","days":$fullDays}""")).single().plan
        assertEquals(15.0, plan.days[0].items[0].loadKg)
        assertEquals("dumbbell-curl", plan.days[1].items[1].exerciseId)
        assertNull(plan.days[1].items[1].loadKg)
        assertEquals(4, plan.progressionBaseVersion)
        assertTrue(contract.validate(plan).isEmpty())
    }

    @Test fun editedPlanKeepsTheOriginalRotationAnchor() {
        val base = current.copy(version = 7, progressionBaseVersion = 2)
        val plan = ConversationPlanContract(library, profile, base).parseOptions(options(replacements(
            replacement(0, "goblet-squat", "glute-bridge"),
        ))).single().plan
        assertEquals(7, plan.version)
        assertEquals(2, plan.progressionBaseVersion)
    }

    @Test fun hiddenRiskMissingEquipmentWrongLocationAndUnknownAlternativesAreRejected() {
        listOf("barbell-deadlift", "leg-press", "not-in-library").forEach { unavailable ->
            invalid(options(replacements(replacement(0, "goblet-squat", unavailable))))
        }
    }

    @Test fun beginnerRiskGateStillAppliesWhenTheGymHasTheRequiredBarbell() {
        val equipped = profile.copy(location = Location.GYM, equipment = setOf(Equipment.DUMBBELL, Equipment.BARBELL))
        val guarded = ConversationPlanContract(library, equipped, current)
        assertFalse(guarded.prompt().contains("barbell-deadlift|"))
        assertThrows(ConversationPlanException::class.java) {
            guarded.parseOptions(options(replacements(replacement(0, "goblet-squat", "barbell-deadlift"))))
        }
    }

    @Test fun locationGateAppliesEvenIfTheMachineTypeExistsInTheProfile() {
        val homeMachine = profile.copy(equipment = setOf(Equipment.DUMBBELL, Equipment.MACHINE))
        val guarded = ConversationPlanContract(library, homeMachine, current)
        assertThrows(ConversationPlanException::class.java) {
            guarded.parseOptions(options(replacements(replacement(0, "goblet-squat", "leg-press"))))
        }
    }

    @Test fun staleTargetsRepeatedTargetsAndSameDayDuplicateMovesAreRejected() {
        invalid(options(replacements(replacement(2, "goblet-squat", "glute-bridge"))))
        invalid(options(replacements(replacement(1, "goblet-squat", "glute-bridge"))))
        invalid(options(replacements(replacement(0, "goblet-squat", "plank"))))
        invalid(options(replacements(replacement(0, "goblet-squat", "glute-bridge"), replacement(0, "goblet-squat", "dumbbell-lunge"))))
    }

    @Test fun invalidStatusAmbiguousShapeAndTooManyOptionsAreRejected() {
        val option = replacements(replacement(0, "goblet-squat", "glute-bridge"))
        invalid(options(option, status = "ready"))
        invalid(options(option, status = "blocked"))
        invalid(options(option, option, option, option))
        invalid(options("""{"title":"有歧义","days":$fullDays,"replacements":[]}"""))
        invalid(options("""{"title":"没有方案内容"}"""))
        invalid("""{"status":{},"plan_options":[]}""")
    }

    @Test fun duplicateOrUnchangedOptionsCannotBeConfirmed() {
        val option = replacements(replacement(0, "goblet-squat", "glute-bridge"))
        invalid(options(option, option))
        invalid(options(replacements(replacement(0, "goblet-squat", "goblet-squat"))))
        val unchanged = fullDays.replace("\"kg\":15", "\"kg\":12").replace("\"dumbbell-curl\"", "\"dumbbell-row\"")
        // Restore the existing row's actual 15kg after replacing the squat load.
        val root = Json.parseToJsonElement(unchanged).jsonArray.toMutableList()
        root[1] = Json.parseToJsonElement("""{"title":"上肢","items":[{"id":"push-up","sets":2,"reps":[8,12]},{"id":"dumbbell-row","sets":3,"reps":[10],"kg":15}]}""")
        invalid(options("""{"title":"无变化","days":${JsonArray(root)}}"""))
    }

    @Test fun fullPlanStillUsesAllExistingEligibilityAndPrescriptionLimits() {
        invalid(options("""{"title":"组数过多","days":${fullDays.replace("\"sets\":3", "\"sets\":7")}}"""))
        invalid(options("""{"title":"危险动作","days":${fullDays.replace("goblet-squat", "barbell-deadlift")}}"""))
        invalid(options("""{"title":"缺器械","days":${fullDays.replace("goblet-squat", "leg-press")}}"""))
        invalid(options("""{"title":"不完整","days":[]}"""))
    }

    @Test fun invalidNumericLoadsCannotDisappearThroughSanitization() {
        listOf("-1", "301", "\"NaN\"", "\"Infinity\"", "1e999").forEach { load ->
            invalid(options("""{"title":"重量无效","days":${fullDays.replace("\"kg\":15", "\"kg\":$load")}}"""))
        }
    }

    @Test fun writeBoundaryValidationRejectsTamperedMetadataUnsafeNumbersAndRiskMoves() {
        val plan = contract.parseOptions(options(replacements(replacement(0, "goblet-squat", "glute-bridge")))).single().plan
        assertTrue(contract.validate(plan.copy(version = 5)).isNotEmpty())
        assertTrue(contract.validate(plan.copy(progressionBaseVersion = null)).isNotEmpty())
        val unsafe = plan.copy(days = plan.days.mapIndexed { index, day -> if (index != 1) day else
            day.copy(items = day.items.map { it.copy(loadKg = Double.NaN) }) })
        assertTrue(contract.validate(unsafe).isNotEmpty())
        val forbidden = plan.copy(days = plan.days.mapIndexed { index, day -> if (index != 0) day else
            day.copy(items = day.items.map { if (it.exerciseId == "glute-bridge") it.copy(exerciseId = "barbell-deadlift") else it }) })
        assertTrue(contract.validate(forbidden).isNotEmpty())
    }

    private fun legacyIncompatiblePlan(): WorkoutPlan = current.copy(days = listOf(
        current.days[0].copy(items = listOf(current.days[0].items[0], PlanItem("walking-lunge", 2, 8, 12, loadKg = 8.0))),
        current.days[1].copy(items = listOf(PlanItem("pull-up", 2, 8, 12), current.days[1].items[1])),
    ))

    @Test fun promptExplicitlyListsLegacyIssuesAndRequiresRepairInEveryOption() {
        val legacy = legacyIncompatiblePlan()
        val prompt = ConversationPlanContract(library, profile, legacy).prompt()
        assertTrue(prompt.contains("当前旧计划存在以下不适配项"))
        assertTrue(prompt.contains("引体向上 不在当前可安排的动作里"))
        assertTrue(prompt.contains("行进弓步 是徒手动作，不应有重量"))
        assertTrue(prompt.contains("不能仅修用户点名的动作"))
        assertTrue(prompt.contains("不要所有方案都只换成名字相近的变式"))
    }

    @Test fun compactOptionRepairsLegacyIneligibleMoveAndUnsupportedBodyweightLoadTogether() {
        val legacy = legacyIncompatiblePlan()
        val guarded = ConversationPlanContract(library, profile, legacy)
        val option = guarded.parseOptions(options(replacements(
            replacement(0, "goblet-squat", "glute-bridge"),
            replacement(1, "pull-up", "push-up"),
        ))).single()
        assertTrue(guarded.validate(option.plan).isEmpty())
        assertEquals("push-up", option.plan.days[1].items[0].exerciseId)
        assertNull(option.plan.days[0].items[1].loadKg)
        assertEquals("walking-lunge", option.plan.days[0].items[1].exerciseId)
        assertTrue(option.detail.contains("行进弓步不适用的负重设置"))
        assertTrue(option.detail.contains("确认后一起保存"))
        assertEquals(8.0, legacy.days[0].items[1].loadKg)
        assertEquals("pull-up", legacy.days[1].items[0].exerciseId)
    }

    @Test fun legacyIneligibleMoveCannotBeGrandfatheredIntoAnOtherwiseValidOption() {
        val guarded = ConversationPlanContract(library, profile, legacyIncompatiblePlan())
        val failure = assertThrows(ConversationPlanException::class.java) {
            guarded.parseOptions(options(replacements(replacement(0, "goblet-squat", "glute-bridge"))))
        }
        assertTrue(failure.problems.any { it.contains("引体向上") })
    }
}
