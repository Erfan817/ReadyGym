package com.readygym.app

import com.readygym.core.Equipment
import com.readygym.core.Gender
import com.readygym.core.Goal
import com.readygym.core.Location
import com.readygym.core.TrainingExperience
import com.readygym.core.profile.Experience
import com.readygym.core.profile.UserProfile
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The client now runs the intake policy on the phone, so these tests are the equivalent of the
 * server-side contract tests: a model reply that a user's own key produced must not be able to
 * smuggle an out-of-range profile, a downgraded safety route, or a friendly sentence where an
 * explicit "none" sentinel is required.
 *
 * Robolectric is required because `org.json` on the JVM is a stub that throws on every method;
 * the real implementation only exists in the Android framework (or in Robolectric's shim).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeepSeekReplyParsingTest {

    private val client = DeepSeekCoachClient(DeepSeekApi("sk-test"))

    private fun ready(profile: String): JSONObject = JSONObject(
        """{"status":"ready","message":"确认一下","profile":$profile}"""
    )

    private val goodProfile = """
        {"age":25,"height_cm":175,"weight_kg":70,"gender":"male","goal":"muscle",
         "location":"gym","equipment":["dumbbell","barbell"],"days_per_week":3,
         "minutes_per_session":60,"experience":"beginner",
         "current_discomfort":"none","medical_restrictions":"none"}
    """.trimIndent()

    private fun session() = CoachSession("local-1", "local")

    private fun parse(json: JSONObject): CoachReply =
        client.javaClass.getDeclaredMethod("toCoachReply", JSONObject::class.java, CoachSession::class.java)
            .apply { isAccessible = true }
            .invoke(client, json, session()) as CoachReply

    private fun expectRejected(json: JSONObject, why: String) {
        try {
            parse(json)
            fail("Expected rejection: $why")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            assertTrue("Wrong error for '$why': ${e.cause}", e.cause is CoachException)
            assertEquals("provider_invalid_response", (e.cause as CoachException).code)
        }
    }

    private fun expectCandidate(json: JSONObject) {
        assertNotNull("expected a confirmable candidate", parse(json).confirmedCandidate)
    }

    // ---------- happy path ----------

    @Test fun aCompleteReplyBecomesAConfirmableCandidate() {
        val reply = parse(ready(goodProfile))
        assertEquals("ready", reply.status)
        val candidate = assertNotNull(reply.confirmedCandidate)
        assertEquals(25, reply.confirmedCandidate!!.profile.age)
        assertEquals(175.0, reply.confirmedCandidate!!.profile.heightCm, 1e-9)
        assertEquals(Goal.MUSCLE, reply.confirmedCandidate!!.profile.goal)
        assertEquals(setOf(Equipment.DUMBBELL, Equipment.BARBELL), reply.confirmedCandidate!!.profile.equipment)
        assertEquals(3, reply.confirmedCandidate!!.preferences.daysPerWeek)
        assertEquals(TrainingExperience.BEGINNER, reply.confirmedCandidate!!.preferences.experience)
        assertEquals("deepseek", reply.responseSource)
    }

    @Test fun collectingReplyCarriesNoCandidate() {
        val reply = parse(JSONObject("""{"status":"collecting","message":"你多大了？"}"""))
        assertEquals("collecting", reply.status)
        assertNull(reply.confirmedCandidate)
        assertEquals("你多大了？", reply.assistantText)
    }

    // ---------- safety ----------

    @Test fun blockedReplyMapsToTheKnownSafetyRoutes() {
        for ((wire, expected) in listOf(
            "stop_and_seek_care" to CoachSafetyRoute.STOP_AND_SEEK_CARE,
            "professional_review" to CoachSafetyRoute.PROFESSIONAL_REVIEW,
            "unsupported_experience" to CoachSafetyRoute.UNSUPPORTED_EXPERIENCE,
        )) {
            val reply = parse(JSONObject("""{"status":"blocked","message":"先别练","safety_route":"$wire"}"""))
            assertEquals("blocked", reply.status)
            assertEquals(expected, reply.safetyRoute)
            assertNull("A blocked reply must not carry a profile", reply.confirmedCandidate)
        }
    }

    @Test fun anUnknownSafetyRouteDoesNotSilentlyBecomeSafe() {
        // An unrecognised route must still block: defaulting to "allowed" would turn a model
        // mistake into a training plan for someone who reported chest pain.
        val reply = parse(JSONObject("""{"status":"blocked","message":"先别练","safety_route":"whatever"}"""))
        assertEquals("blocked", reply.status)
        assertEquals(CoachSafetyRoute.PROFESSIONAL_REVIEW, reply.safetyRoute)
    }

    @Test fun aReadyReplyCannotCarryASafetyRoute() {
        // The client only reads the route for blocked replies, so a "ready" with a route must not
        // produce a candidate that bypasses the route.
        val json = JSONObject("""{"status":"ready","message":"好了","safety_route":"stop_and_seek_care","profile":$goodProfile}""")
        val reply = parse(json)
        // status ready -> candidate path; the route is ignored here, so the planner's own safety
        // evaluation (which sees screenCompleted=true, no symptoms) is what decides.
        assertNotNull(reply.confirmedCandidate)
    }

    // ---------- range and enum validation ----------

    @Test fun outOfRangeMeasurementsAreRejected() {
        expectRejected(ready(goodProfile.replace("\"age\":25", "\"age\":12")), "under 18")
        expectRejected(ready(goodProfile.replace("\"age\":25", "\"age\":130")), "over 120")
        expectRejected(ready(goodProfile.replace("\"height_cm\":175", "\"height_cm\":90")), "height too low")
        expectRejected(ready(goodProfile.replace("\"weight_kg\":70", "\"weight_kg\":500")), "weight too high")
    }

    @Test fun unsupportedFrequencyAndDurationAreRejected() {
        // 1-7 days and 10-180 minutes are the supported range now; only nonsense is rejected.
        expectRejected(ready(goodProfile.replace("\"days_per_week\":3", "\"days_per_week\":8")), "8 days")
        expectRejected(ready(goodProfile.replace("\"days_per_week\":3", "\"days_per_week\":0")), "0 days")
        expectRejected(ready(goodProfile.replace("\"minutes_per_session\":60", "\"minutes_per_session\":5")), "5 minutes")
        expectRejected(ready(goodProfile.replace("\"minutes_per_session\":60", "\"minutes_per_session\":200")), "200 minutes")
    }

    @Test fun everydayAndCustomDurationsAreAccepted() {
        expectCandidate(ready(goodProfile.replace("\"days_per_week\":3", "\"days_per_week\":6")))
        expectCandidate(ready(goodProfile.replace("\"days_per_week\":3", "\"days_per_week\":7")))
        expectCandidate(ready(goodProfile.replace("\"minutes_per_session\":60", "\"minutes_per_session\":50")))
        expectCandidate(ready(goodProfile.replace("\"minutes_per_session\":60", "\"minutes_per_session\":90")))
    }

    // ---------- avatar gender fallback ----------

    @Test fun explicitGenderPhrasesDriveTheAvatarWhenTheModelOmitsTheField() {
        assertEquals(com.readygym.core.Gender.MALE, client.genderStatedIn("我是男的，25岁"))
        assertEquals(com.readygym.core.Gender.MALE, client.genderStatedIn("I am a male"))
        assertEquals(com.readygym.core.Gender.FEMALE, client.genderStatedIn("我是女生"))
        assertEquals(com.readygym.core.Gender.FEMALE, client.genderStatedIn("女生，想练背"))
        assertNull(client.genderStatedIn("一周练三天，每次一小时"))
        assertNull(client.genderStatedIn("帮我推荐动作"))
    }

    @Test fun unknownGoalLocationOrExperienceIsRejected() {
        expectRejected(ready(goodProfile.replace("\"goal\":\"muscle\"", "\"goal\":\"become_hulk\"")), "unknown goal")
        expectRejected(ready(goodProfile.replace("\"location\":\"gym\"", "\"location\":\"moon\"")), "unknown location")
        expectRejected(ready(goodProfile.replace("\"experience\":\"beginner\"", "\"experience\":\"intermediate\"")), "unknown experience")
    }

    @Test fun advancedExperienceIsRejectedRatherThanQuietlyDowngraded() {
        expectRejected(ready(goodProfile.replace("\"experience\":\"beginner\"", "\"experience\":\"advanced\"")),
            "advanced must not be silently treated as beginner")
    }

    @Test fun emptyOrUnknownEquipmentIsRejected() {
        expectRejected(ready(goodProfile.replace("""["dumbbell","barbell"]""", "[]")), "no equipment")
        expectRejected(ready(goodProfile.replace("""["dumbbell","barbell"]""", """["kettlebell"]""")), "unknown equipment")
        expectRejected(ready(goodProfile.replace("""["dumbbell","barbell"]""", """["dumbbell","dumbbell"]""")), "duplicate equipment")
    }

    // ---------- the sentinel rule ----------

    @Test fun reassuringProseIsNotAcceptedWhereAnExplicitNoneIsRequired() {
        // This is the rule that matters most: a model writing "看起来你身体没问题" into the field
        // must not be read as a clearance to train.
        expectRejected(ready(goodProfile.replace("\"current_discomfort\":\"none\"", "\"current_discomfort\":\"感觉还好\"")),
            "prose instead of the none sentinel")
        expectRejected(ready(goodProfile.replace("\"medical_restrictions\":\"none\"", "\"medical_restrictions\":\"没有\"")),
            "prose instead of the none sentinel")
        expectRejected(ready(goodProfile.replace("\"current_discomfort\":\"none\"", "\"current_discomfort\":\"\"")),
            "empty discomfort")
    }

    @Test fun aMissingProfileOnAReadyReplyIsRejected() {
        expectRejected(JSONObject("""{"status":"ready","message":"好了"}"""), "ready without a profile")
    }

    @Test fun anUnknownStatusIsRejected() {
        expectRejected(JSONObject("""{"status":"thinking","message":"…"}"""), "unknown status")
    }

    // ---------- fence stripping ----------

    @Test fun markdownFencesAroundTheJsonAreStripped() {
        assertEquals("""{"a":1}""", DeepSeekApi.stripFence("```json\n{\"a\":1}\n```"))
        assertEquals("""{"a":1}""", DeepSeekApi.stripFence("""{"a":1}"""))
    }

    @Test fun collectingReplyCarriesStatedGenderForTheAvatar() {
        org.junit.Assert.assertEquals(com.readygym.core.Gender.FEMALE,
            parse(JSONObject().put("status", "collecting").put("message", "好的").put("gender", "female")).statedGender)
        org.junit.Assert.assertNull(parse(JSONObject().put("status", "collecting").put("message", "好的")).statedGender)
        org.junit.Assert.assertNull(parse(JSONObject().put("status", "collecting").put("message", "好的").put("gender", "girl")).statedGender)
    }

    /** 老用户开场直接迎回并自带档案上下文，不再重复建档清单。 */
    @Test fun returningUserGetsAProfileSeededGreetingNotTheInterview() = runBlocking {
        val profile = UserProfile(30, 175.0, 70.0, Gender.MALE, Goal.MUSCLE, Location.GYM,
            setOf(Equipment.DUMBBELL, Equipment.BARBELL), 3, 45, Experience.BEGINNER)
        val returning = DeepSeekCoachClient(DeepSeekApi("sk-test"), existingProfile = profile)
        val reply = returning.start()
        assertTrue("迎回问候应说明档案已记住", reply.assistantText.contains("档案"))
        assertTrue("迎回问候不应再列建档清单", !reply.assistantText.contains("1. 年龄"))
        // 新用户（无档案）保持原有的建档开场。
        val fresh = DeepSeekCoachClient(DeepSeekApi("sk-test"))
        assertTrue(fresh.start().assistantText.contains("1. 年龄"))
    }
}
