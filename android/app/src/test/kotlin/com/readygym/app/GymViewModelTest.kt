package com.readygym.app

import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The whole loop the product promises: plan → train → feedback → adjusted plan, confirmed by the user. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GymViewModelTest {
    private var now = 1_000_000L
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GymDatabase::class.java)
        .build()
    private val library = ExerciseLibrary.parse(File("src/main/assets/exercise-library.json").readText())
    private val prompts = CopyOnWriteArrayList<List<ChatMessage>>()
    private val replies = ArrayDeque<String>()
    private class DelayedReply {
        val started = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val response = CompletableFuture<String>()
    }
    private val delayedReplies = ConcurrentLinkedQueue<DelayedReply>()
    private val outstandingReplies = mutableListOf<DelayedReply>()
    private val model = ChatModel {
        prompts += it
        val delayed = delayedReplies.poll()
        if (delayed == null) replies.removeFirst() else {
            delayed.started.countDown()
            // The real HTTP model is blocking and cannot be assumed to honour Job.cancel().
            try { delayed.response.get(5, TimeUnit.SECONDS) } finally { delayed.returned.countDown() }
        }
    }
    private var key: String? = "sk-test"
    private val store = ViewModelStore()
    private val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            GymViewModel(GymRepository(db) { now }, library, apiKey = { key },
                modelFor = { model }, io = Dispatchers.IO, clock = { now }) as T
    })[GymViewModel::class.java]

    private val profile = UserProfile(24, 175.0, 70.0, Gender.MALE, Goal.MUSCLE, Location.HOME,
        setOf(Equipment.DUMBBELL), 2, 45, Experience.BEGINNER)

    private val initial = """{"summary":"上下肢分化","message":"先从保守重量开始。","days":[
        {"title":"下肢","items":[{"id":"goblet-squat","sets":2,"reps":[10],"kg":10,"rest":60},{"id":"plank","sets":2,"seconds":30}]},
        {"title":"上肢","items":[{"id":"push-up","sets":2,"reps":[8,12]},{"id":"dumbbell-row","sets":2,"reps":[10],"kg":10}]}]}"""

    private val adjusted = """{"summary":"下肢加重","message":"深蹲完成得很好，加一点重量。","days":[
        {"title":"下肢","items":[{"id":"goblet-squat","sets":2,"reps":[10],"kg":12,"rest":60},{"id":"plank","sets":2,"seconds":30}]},
        {"title":"上肢","items":[{"id":"push-up","sets":2,"reps":[8,12]},{"id":"dumbbell-row","sets":2,"reps":[10],"kg":10}]}],
        "reasons":{"goblet-squat":"全部完成且反馈偏易，加 2kg"}}"""

    @After fun close() {
        outstandingReplies.forEach { it.response.complete(initial) }
        store.clear()
        shadowOf(Looper.getMainLooper()).idle()
        // A canceled Room collector may still have a query completing on its own executor.
        Thread.sleep(150)
        shadowOf(Looper.getMainLooper()).idle()
        db.close()
    }

    private fun delayNextReply() = DelayedReply().also { delayedReplies += it; outstandingReplies += it }

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
        assertEquals("the final plank uses the default rest", 30, vm.restTotalSeconds)
        assertEquals(now + 30_000L, vm.restEndsAt)
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

    @Test fun editingProfileImmediatelyDiscardsDraftWithoutChangingPlanHistoryOrWorkout() {
        planAndFinishOneWorkout()
        vm.closeReview()
        await("finished workout and history") { vm.workout == null && vm.history.value.size == 1 && vm.nextDay == 1 }
        vm.startWorkout()
        await("next workout") { vm.workout != null }
        val beforePlan = vm.activePlan.value.valueOrNull()
        val beforeHistory = vm.history.value
        val beforeWorkout = vm.workout
        val beforeRotation = vm.nextDay
        replies += initial
        vm.generatePlan()
        await("new draft") { vm.proposal != null }

        val updated = profile.copy(minutesPerSession = 50)
        vm.saveProfile(updated)
        assertNull("the stale card disappears before the database write finishes", vm.proposal)
        assertFalse(vm.generating)
        vm.confirmProposal()
        await("updated profile") { vm.profile.value.valueOrNull() == updated }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(beforePlan, vm.activePlan.value.valueOrNull())
        assertEquals(beforeHistory, vm.history.value)
        assertEquals(beforeWorkout, vm.workout)
        assertEquals(beforeRotation, vm.nextDay)
    }

    @Test fun canceledBlockingGenerationCannotRestoreDraftOrStopTheNewerRequest() {
        vm.saveProfile(profile)
        await("profile") { vm.profile.value.valueOrNull() == profile }
        val old = delayNextReply()
        vm.generatePlan()
        await("old model request") { old.started.count == 0L }
        assertTrue(vm.generating)

        val updated = profile.copy(weightKg = 72.0)
        var saved = false
        vm.saveProfile(updated) { saved = true }
        assertFalse("cancellation must stop showing a loading state immediately", vm.generating)
        assertNull(vm.proposal)
        await("new profile visible") { saved }
        val fresh = delayNextReply()
        vm.generatePlan()
        await("new model request") { fresh.started.count == 0L }

        old.response.complete(initial)
        await("old HTTP call returned") { old.returned.count == 0L }
        // Let the blocking worker leave PlanCoach and dispatch its canceled continuation.
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("the late response must not revive the old draft", vm.proposal)
        assertTrue("the canceled request must not clear the newer request's spinner", vm.generating)

        fresh.response.complete(adjusted)
        await("fresh draft") { vm.proposal != null && !vm.generating }
        assertEquals(12.0, vm.proposal!!.plan.days[0].items[0].loadKg)
        assertEquals(updated, vm.profile.value.valueOrNull())
    }

    @Test fun profileSaveCallbackRunsOnlyAfterNewProfileIsVisible() {
        vm.saveProfile(profile)
        await("original profile") { vm.profile.value.valueOrNull() == profile }
        val updated = profile.copy(minutesPerSession = 60, weightKg = 71.0)
        var profileAtCallback: UserProfile? = null
        vm.saveProfile(updated) { profileAtCallback = vm.profile.value.valueOrNull() }
        // The previous profile is still loaded during the write, so starting now must be ignored.
        vm.generatePlan()
        await("save callback") { profileAtCallback != null }
        assertEquals(updated, profileAtCallback)
        assertTrue("a pending profile write must not generate from the old profile", prompts.isEmpty())
        assertNull(vm.proposal)
        assertFalse(vm.generating)
    }
}
