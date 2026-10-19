package com.readygym.app

import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.readygym.app.data.GymDatabase
import com.readygym.app.data.GymRepository
import com.readygym.core.Equipment
import com.readygym.core.Gender
import com.readygym.core.Goal
import com.readygym.core.Location
import com.readygym.core.ai.ChatMessage
import com.readygym.core.ai.ChatModel
import com.readygym.core.exercise.ExerciseLibrary
import com.readygym.core.plan.ChangeKind
import com.readygym.core.profile.Experience
import com.readygym.core.profile.UserProfile
import com.readygym.core.workout.BodyArea
import com.readygym.core.workout.Effort
import com.readygym.core.workout.PostWorkoutFeedback
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** The whole loop the product promises: plan → train → feedback → adjusted plan, confirmed by the user. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GymViewModelTest {
    private var now = 1_000_000L
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GymDatabase::class.java)
        .build()
    private val library = ExerciseLibrary.parse(File("src/main/assets/exercise-library.json").readText())
    private val prompts = mutableListOf<List<ChatMessage>>()
    private val replies = ArrayDeque<String>()
    private val model = ChatModel { prompts += it; replies.removeFirst() }
    private var key: String? = "sk-test"
    private val vm = GymViewModel(GymRepository(db) { now }, library, apiKey = { key },
        modelFor = { model }, io = Dispatchers.IO, clock = { now })

    private val profile = UserProfile(24, 175.0, 70.0, Gender.MALE, Goal.MUSCLE, Location.HOME,
        setOf(Equipment.DUMBBELL), 2, 45, Experience.BEGINNER)

    private val initial = """{"summary":"上下肢分化","message":"先从保守重量开始。","days":[
        {"title":"下肢","items":[{"id":"goblet-squat","sets":2,"reps":[10],"kg":10,"rest":60},{"id":"plank","sets":2,"seconds":30}]},
        {"title":"上肢","items":[{"id":"push-up","sets":2,"reps":[8,12]},{"id":"dumbbell-row","sets":2,"reps":[10],"kg":10}]}]}"""

    private val adjusted = """{"summary":"下肢加重","message":"深蹲完成得很好，加一点重量。","days":[
        {"title":"下肢","items":[{"id":"goblet-squat","sets":2,"reps":[10],"kg":12,"rest":60},{"id":"plank","sets":2,"seconds":30}]},
        {"title":"上肢","items":[{"id":"push-up","sets":2,"reps":[8,12]},{"id":"dumbbell-row","sets":2,"reps":[10],"kg":10}]}],
        "reasons":{"goblet-squat":"全部完成且反馈偏易，加 2kg"}}"""

    @After fun close() = db.close()

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(5)
        }
    }

    private fun planAndFinishOneWorkout() {
        vm.saveProfile(profile)
        await("profile") { vm.profile.value.valueOrNull() != null }
        replies += initial
        vm.generatePlan()
        await("proposal") { vm.proposal != null }
        vm.confirmProposal()
        await("proposal cleared") { vm.proposal == null }
        await("plan v1") { vm.activePlan.value.valueOrNull()?.version == 1 }

        vm.startWorkout(0)
        await("workout") { vm.workout != null }
        vm.workout!!.sets.forEachIndexed { i, s -> vm.updateSet(i, s.copy(done = true)) }
        assertNotNull("ticking a set starts the rest timer", vm.restEndsAt)
        now += 30 * 60_000
        var finished: Long? = null
        vm.finishWorkout { finished = it.id }
        await("finish") { finished != null }
        vm.openReview(finished!!)
        await("review") { vm.reviewing != null }
    }

    @Test fun feedbackProducesADiffThatOnlyBecomesThePlanWhenAccepted() {
        planAndFinishOneWorkout()
        assertEquals(4, vm.reviewing!!.completedSets.size)

        replies += adjusted
        vm.requestAdjustment(PostWorkoutFeedback(vm.reviewing!!.id, Effort.TOO_EASY))
        await("adjustment") { vm.adjustment != null }
        val a = vm.adjustment!!
        assertEquals(listOf(ChangeKind.LOAD_UP), a.changed.map { it.kind })
        assertEquals("全部完成且反馈偏易，加 2kg", a.proposal.reasons["goblet-squat"])
        assertTrue("the prompt carries the feedback", "太轻松" in prompts.last().last().content)
        assertEquals("nothing is saved before the user accepts", 1, vm.activePlan.value.valueOrNull()?.version)

        var done = false
        vm.acceptAdjustment { done = true }
        await("accept") { done }
        await("plan v2") { vm.activePlan.value.valueOrNull()?.version == 2 }
        val v2 = vm.activePlan.value.valueOrNull()!!
        assertEquals(2, v2.version)
        assertEquals(12.0, v2.days[0].items[0].loadKg)
        assertNull(vm.reviewing)
    }

    @Test fun keepingTheOldPlanLeavesItUntouchedButStoresFeedback() {
        planAndFinishOneWorkout()
        replies += adjusted
        vm.requestAdjustment(PostWorkoutFeedback(vm.reviewing!!.id, Effort.TIRING, discomfort = setOf(BodyArea.KNEE)))
        await("adjustment") { vm.adjustment != null }
        assertTrue(vm.adjustment!!.discomfort)
        assertTrue("不适部位：膝盖" in prompts.last().last().content)
        vm.closeReview()
        assertEquals(1, vm.activePlan.value.valueOrNull()?.version)
        assertEquals(1, vm.history.value.size)
    }

    @Test fun missingKeyExplainsInsteadOfCallingTheModel() {
        planAndFinishOneWorkout()
        val calls = prompts.size
        key = null
        vm.requestAdjustment(PostWorkoutFeedback(vm.reviewing!!.id, Effort.JUST_RIGHT))
        assertTrue(vm.adjustError!!.contains("登录"))
        assertEquals(calls, prompts.size)
    }
}
