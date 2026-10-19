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
import com.readygym.core.workout.BodyArea
import com.readygym.core.workout.Difficulty
import com.readygym.core.workout.Effort
import com.readygym.core.workout.PostWorkoutFeedback
import com.readygym.core.workout.SetLog
import com.readygym.core.workout.WorkoutSession
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PlanCoachTest {
    private val library = ExerciseLibrary.parse(File("../app/src/main/assets/exercise-library.json").readText())
    private val profile = UserProfile(24, 175.0, 70.0, Gender.MALE, Goal.MUSCLE, Location.HOME,
        setOf(Equipment.DUMBBELL), 2, 45, Experience.BEGINNER, limitations = "右膝旧伤")

    private val goodReply = """
        ```json
        {"summary":"上下肢分化","message":"先从保守重量开始。","days":[
          {"title":"下肢 + 核心","focus":"腿","items":[
            {"id":"goblet-squat","sets":3,"reps":[8,12],"kg":10,"rest":90},
            {"id":"glute-bridge","sets":3,"reps":[12],"kg":5},
            {"id":"plank","sets":3,"seconds":30,"reps":[10]}]},
          {"title":"上肢","items":[
            {"id":"push-up","sets":3,"reps":[8,12]},
            {"id":"dumbbell-row","sets":3,"reps":[10,10],"kg":10,"note":"背挺直"}]}
        ]}
        ```
    """.trimIndent()

    private class Scripted(vararg replies: String) : ChatModel {
        private val queue = ArrayDeque(replies.toList())
        val calls = mutableListOf<List<ChatMessage>>()
        override fun complete(messages: List<ChatMessage>): String { calls += messages; return queue.removeFirst() }
    }

    @Test fun promptOnlyOffersAvailableExercisesAndProfile() {
        val prompt = PlanContract(library, profile).generateMessages().last().content
        assertTrue("goblet-squat|" in prompt)
        assertFalse("leg-press|" in prompt)
        assertFalse("barbell-squat|" in prompt)
        assertTrue("右膝旧伤" in prompt)
        assertTrue("2 个训练日" in prompt)
    }

    @Test fun parsesValidReplyAndSanitizesFields() {
        val model = Scripted(goodReply)
        val p = PlanCoach(model, library).generate(profile)
        assertEquals(1, model.calls.size)
        assertEquals(2, p.plan.days.size)
        val legs = p.plan.days[0].items
        assertEquals(PlanItem("goblet-squat", 3, 8, 12, loadKg = 10.0, restSeconds = 90), legs[0])
        assertNull("bodyweight load dropped", legs[1].loadKg)
        assertEquals(12, legs[1].repsMax)
        assertEquals(30, legs[2].seconds)
        assertNull("timed move keeps no reps", legs[2].repsMin)
        assertEquals("背挺直", p.plan.days[1].items[1].note)
        assertEquals("先从保守重量开始。", p.message)
    }

    @Test fun retriesOnceWithProblemsThenSucceeds() {
        val bad = """{"days":[{"title":"x","items":[{"id":"leg-press","sets":3,"reps":[10]},{"id":"made-up","sets":3,"reps":[10]}]}]}"""
        val model = Scripted(bad, goodReply)
        val p = PlanCoach(model, library).generate(profile)
        assertEquals(2, model.calls.size)
        val retry = model.calls[1]
        assertEquals("assistant", retry[retry.size - 2].role)
        val feedback = retry.last().content
        assertTrue(feedback, "倒蹬 不符合" in feedback)
        assertTrue(feedback, "made-up 不在动作库" in feedback)
        assertTrue(feedback, "需要 2 个训练日" in feedback)
        assertEquals(2, p.plan.days.size)
    }

    @Test fun failsAfterSecondBadReply() {
        val e = assertThrows(PlanRejectedException::class.java) {
            PlanCoach(Scripted("not json", "{\"days\":[]}"), library).generate(profile)
        }
        assertTrue(e.problems.isNotEmpty())
    }

    @Test fun adjustSendsSessionAndFeedbackAndKeepsVersion() {
        val current = WorkoutPlan(3, 0, "初始计划", "上下肢分化", 2, listOf(
            PlanDay("下肢", items = listOf(PlanItem("goblet-squat", 3, 10, 10, loadKg = 12.0), PlanItem("plank", 3, seconds = 30))),
            PlanDay("上肢", items = listOf(PlanItem("push-up", 3, 8, 12), PlanItem("dumbbell-row", 3, 10, 10, loadKg = 10.0))),
        ))
        val session = WorkoutSession(7, 3, 0, "下肢", 0, 1, listOf(
            SetLog("goblet-squat", 0, 10, loadKg = 12.0, done = true),
            SetLog("goblet-squat", 1, 10, loadKg = 12.0, done = true),
            SetLog("plank", 0, seconds = 30, done = false),
        ))
        val fb = PostWorkoutFeedback(7, Effort.TOO_EASY, setOf(BodyArea.KNEE), mapOf("goblet-squat" to Difficulty.EASY), "膝盖有点酸")
        val reply = goodReply.replace("{\"summary\"", "{\"reasons\":{\"goblet-squat\":\"偏易，加 2kg\",\"ghost\":\"x\"},\"summary\"")
        val model = Scripted(reply)
        val p = PlanCoach(model, library).adjust(profile, current, session, fb)

        val prompt = model.calls.single().last().content
        assertTrue(prompt, "goblet-squat 完成2/2组（12kg×10,12kg×10）" in prompt)
        assertTrue(prompt, "plank 完成0/1组" in prompt)
        assertTrue(prompt, "不适部位：膝盖" in prompt)
        assertTrue(prompt, "哑铃高脚杯深蹲偏易" in prompt)
        assertTrue(prompt, "膝盖有点酸" in prompt)
        assertEquals(3, p.plan.version)
        assertEquals("练后调整：下肢", p.plan.reason)
        assertEquals(mapOf("goblet-squat" to "偏易，加 2kg"), p.reasons)
    }
}
