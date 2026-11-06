package com.readygym.app

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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the actual gateway/client/plan-contract boundary with in-memory HTTP only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationPlanClientTest {
    private val library = ExerciseLibrary.parse(File("src/main/assets/exercise-library.json").readText())
    private val profile = UserProfile(24, 175.0, 70.0, Gender.MALE, Goal.MUSCLE, Location.HOME,
        setOf(Equipment.DUMBBELL), 2, 45, Experience.BEGINNER)
    private val plan = WorkoutPlan(7, 123L, "原计划", "两天轮换", 2, listOf(
        PlanDay("下肢", items = listOf(PlanItem("goblet-squat", 2, 8, 12, loadKg = 12.0), PlanItem("plank", 2, seconds = 20))),
        PlanDay("上肢", items = listOf(PlanItem("push-up", 2, 8, 12), PlanItem("dumbbell-row", 2, 8, 12))),
    ))
    private val collecting = """{"status":"collecting","message":"可以，先确认你能用哪些器械。"}"""
    private val options = """{"status":"collecting","message":"选一种调整，确认后才保存。","plan_options":[
        {"title":"弓步替代","detail":"保留其余安排","replacements":[{"day":0,"from":"goblet-squat","to":"dumbbell-lunge"}]},
        {"title":"侧撑替代","detail":"换一个核心动作","replacements":[{"day":0,"from":"plank","to":"side-plank"}]}]}"""

    private class Connection(reply: String) : HttpURLConnection(URL("https://test.invalid/v1/chat/completions")) {
        val sent = ByteArrayOutputStream()
        private val response = JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("finish_reason", "stop").put("message", JSONObject().put("content", reply)))).toString()
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() = Unit
        override fun getResponseCode() = 200
        override fun getOutputStream() = sent
        override fun getInputStream() = ByteArrayInputStream(response.toByteArray(Charsets.UTF_8))
        fun body() = JSONObject(sent.toString("UTF-8"))
    }

    private class Fixture(vararg replies: String) {
        val connections = replies.map { Connection(it) }
        private val pending = connections.toMutableList()
        val api = DeepSeekApi("test-only-key", baseUrl = "https://test.invalid/v1", maxTokens = 1500,
            openConnection = { pending.removeAt(0) }, retryDelay = {})
        fun messages(index: Int) = connections[index].body().getJSONArray("messages")
        fun systemContext(index: Int): String = messages(index).let { messages ->
            (0 until messages.length()).map { messages.getJSONObject(it) }
                .filter { it.getString("role") == "system" }.joinToString("\n") { it.getString("content") }
        }
    }

    @Test fun currentPlanEnablesAuthoritativeContextAndTheLargerPlanBudget() = runBlocking {
        val fixture = Fixture(collecting)
        val client = DeepSeekCoachClient(fixture.api, profile, library) { plan }
        client.message(client.start().session, "没有这台器械，帮我换一下")
        assertEquals(6000, fixture.connections.single().body().getInt("max_tokens"))
        assertEquals(listOf("system", "system", "user"), roles(fixture.messages(0)))
        val context = fixture.systemContext(0)
        assertTrue(context.contains("权威版本 v7"))
        assertTrue(context.contains("goblet-squat"))
        assertTrue(context.contains("plan_options"))
        assertTrue(context.contains("dumbbell-lunge"))
    }

    @Test fun noCurrentPlanKeepsOrdinaryChatWithoutInventingAPlanContext() = runBlocking {
        val fixture = Fixture(collecting)
        val client = DeepSeekCoachClient(fixture.api, profile, library)
        client.message(client.start().session, "我想了解怎么开始训练")
        assertEquals(1500, fixture.connections.single().body().getInt("max_tokens"))
        assertEquals(listOf("system", "user"), roles(fixture.messages(0)))
        assertFalse(fixture.systemContext(0).contains("当前整周计划的权威版本"))
    }

    @Test fun twoAlternativesAreValidatedButDoNotMutateOrSaveTheReviewedBase() = runBlocking {
        val fixture = Fixture(options)
        var active = plan
        val before = active.toJson()
        val client = DeepSeekCoachClient(fixture.api, profile, library) { active }
        val result = client.message(client.start().session, "帮我给两个可选调整方案")
        val draft = requireNotNull(result.conversationPlan)
        assertEquals(2, draft.options.size)
        assertEquals(plan, draft.base)
        assertEquals(profile, draft.profile)
        assertEquals("dumbbell-lunge", draft.options[0].plan.days[0].items[0].exerciseId)
        assertNull("a different exercise cannot inherit the old load", draft.options[0].plan.days[0].items[0].loadKg)
        assertEquals("side-plank", draft.options[1].plan.days[0].items[1].exerciseId)
        assertEquals(before, active.toJson())
        assertEquals(7, active.version)
        assertEquals(plan.version, draft.options[0].plan.progressionBaseVersion)
    }

    @Test fun theNextMessageUsesTheLatestPlanProviderWithoutRestartingTheConversation() = runBlocking {
        val fixture = Fixture(collecting, collecting)
        var active = plan
        val client = DeepSeekCoachClient(fixture.api, profile, library) { active }
        val session = client.start().session
        client.message(session, "先看看当前计划")
        active = plan.copy(version = 8, days = plan.days.mapIndexed { index, day ->
            if (index == 0) day.copy(title = "确认后新下肢安排") else day
        })
        client.message(session, "再根据现在的计划调整")
        assertTrue(fixture.systemContext(0).contains("权威版本 v7"))
        assertTrue(fixture.systemContext(1).contains("权威版本 v8"))
        assertTrue(fixture.systemContext(1).contains("确认后新下肢安排"))
        assertFalse("stale context must not be accumulated", fixture.systemContext(1).contains("权威版本 v7"))
        assertEquals(listOf("system", "system", "user", "assistant", "user"), roles(fixture.messages(1)))
    }

    @Test fun highRiskOptionsAreRejectedWithoutPollutingTheRetryHistory() = runBlocking {
        val invalid = options.replace("dumbbell-lunge", "barbell-bench-press")
        val fixture = Fixture(invalid, invalid, options, collecting)
        val gym = profile.copy(location = Location.GYM, equipment = Equipment.entries.toSet())
        val client = DeepSeekCoachClient(fixture.api, gym, library) { plan }
        val session = client.start().session
        try {
            client.message(session, "换一个动作")
            fail("High-risk alternatives must be rejected for beginners")
        } catch (error: CoachException) {
            assertEquals("plan_options_invalid", error.code)
            assertTrue(error.retryable)
        }
        val retry = client.message(session, "换一个动作")
        assertNotNull(retry.conversationPlan)
        assertEquals(listOf("system", "system", "user"), roles(fixture.messages(2)))
        client.message(session, "先不保存，再问问")
        val messages = fixture.messages(3)
        assertEquals(1, (0 until messages.length()).count {
            messages.getJSONObject(it).optString("content") == "换一个动作"
        })
        assertFalse((0 until messages.length()).any {
            messages.getJSONObject(it).getString("role") == "assistant" &&
                messages.getJSONObject(it).getString("content").contains("barbell-bench-press")
        })
    }

    @Test fun malformedOptionsRejectTheTurnAndKeepTheCurrentPlanUnchanged() = runBlocking {
        val malformed = """{"status":"collecting","message":"替换","plan_options":[{"title":"缺少动作"}]}"""
        val fixture = Fixture(malformed, malformed, collecting)
        val client = DeepSeekCoachClient(fixture.api, profile, library) { plan }
        val session = client.start().session
        try {
            client.message(session, "没有腿弯举器械")
            fail("Malformed alternatives must not become a draft")
        } catch (error: CoachException) {
            assertEquals("plan_options_invalid", error.code)
        }
        client.message(session, "没有腿弯举器械")
        assertEquals(3, fixture.messages(2).length())
        assertEquals(7, plan.version)
    }

    @Test fun oneInvalidOptionIsCorrectedAutomaticallyAndOnlyTheValidatedTurnIsCommitted() = runBlocking {
        val invalid = options.replace("dumbbell-lunge", "not-an-exercise")
        val fixture = Fixture(invalid, options, collecting)
        val client = DeepSeekCoachClient(fixture.api, profile, library) { plan }
        val session = client.start().session
        val corrected = client.message(session, "换一个能做的动作")
        assertNotNull(corrected.conversationPlan)
        assertEquals(2, corrected.conversationPlan!!.options.size)
        client.message(session, "先不保存，再聊聊")
        val nextMessages = fixture.messages(2)
        assertEquals(listOf("system", "system", "user", "assistant", "user"), roles(nextMessages))
        assertEquals(1, (0 until nextMessages.length()).count {
            nextMessages.getJSONObject(it).optString("content") == "换一个能做的动作"
        })
        assertFalse((0 until nextMessages.length()).any {
            nextMessages.getJSONObject(it).getString("content").contains("not-an-exercise")
        })
        assertEquals(7, plan.version)
    }

    @Test fun planOptionsWithoutAStoredPlanCannotPretendToBeApplicable() = runBlocking {
        val fixture = Fixture(options)
        val client = DeepSeekCoachClient(fixture.api, profile, library)
        try {
            client.message(client.start().session, "改计划")
            fail("No plan has been reviewed")
        } catch (error: CoachException) {
            assertEquals("provider_invalid_response", error.code)
        }
    }

    @Test fun blockedReplyAlwaysWinsOverAttachedPlanOptions() = runBlocking {
        val blocked = JSONObject(options).put("status", "blocked")
            .put("safety_route", "professional_review").put("message", "先暂停训练，咨询专业人士。")
        val fixture = Fixture(blocked.toString())
        val client = DeepSeekCoachClient(fixture.api, profile, library) { plan }
        val reply = client.message(client.start().session, "膝盖疼，帮我换个动作")
        assertEquals("blocked", reply.status)
        assertEquals(CoachSafetyRoute.PROFESSIONAL_REVIEW, reply.safetyRoute)
        assertNull(reply.conversationPlan)
        assertNull(reply.confirmedCandidate)
    }

    @Test fun explicitSafetyRouteWinsWhenTheModelIncorrectlyUsesCollectingWithOptions() = runBlocking {
        val replies = listOf("professional_review", "stop_and_seek_care").map { route ->
            JSONObject(options).put("safety_route", route).toString()
        }
        for ((index, route) in listOf(CoachSafetyRoute.PROFESSIONAL_REVIEW, CoachSafetyRoute.STOP_AND_SEEK_CARE).withIndex()) {
            val fixture = Fixture(replies[index])
            val client = DeepSeekCoachClient(fixture.api, profile, library) { plan }
            val reply = client.message(client.start().session, "膝盖疼，能不能换个动作")
            assertEquals("blocked", reply.status)
            assertEquals(route, reply.safetyRoute)
            assertNull(reply.conversationPlan)
            assertNull(reply.confirmedCandidate)
            assertTrue(reply.assistantText.contains("暂停"))
        }
    }

    private fun roles(messages: JSONArray): List<String> =
        (0 until messages.length()).map { messages.getJSONObject(it).getString("role") }
}
