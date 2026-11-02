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
import com.readygym.core.ai.ChatModel
import com.readygym.core.exercise.ExerciseLibrary
import com.readygym.core.plan.PlanDay
import com.readygym.core.plan.PlanItem
import com.readygym.core.plan.WorkoutPlan
import com.readygym.core.profile.Experience
import com.readygym.core.profile.UserProfile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GymChatPlanViewModelTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GymDatabase::class.java)
        .allowMainThreadQueries().build()
    private val repo = GymRepository(db)
    private val library = ExerciseLibrary.parse(File("src/main/assets/exercise-library.json").readText())
    private val store = ViewModelStore()
    private val profile = UserProfile(24, 175.0, 70.0, Gender.MALE, Goal.MUSCLE, Location.HOME,
        setOf(Equipment.DUMBBELL), 2, 45, Experience.BEGINNER)
    private val template = WorkoutPlan(0, 0, "原计划", "两天轮换", 2, listOf(
        PlanDay("下肢", items = listOf(PlanItem("goblet-squat", 2, 8, 12), PlanItem("plank", 2, seconds = 20))),
        PlanDay("上肢", items = listOf(PlanItem("push-up", 2, 8, 12), PlanItem("dumbbell-row", 2, 8, 12))),
    ))
    private fun vm(): GymViewModel = ViewModelProvider(store, object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            GymViewModel(repo, library, apiKey = { null }, modelFor = {
                ChatModel { error("Confirmation of a selected plan option must not call AI") }
            }) as T
    })[GymViewModel::class.java]

    @After fun close() {
        store.clear()
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(150)
        shadowOf(Looper.getMainLooper()).idle()
        db.close()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for chat plan confirmation" }
            Thread.sleep(5)
        }
    }

    @Test fun confirmationReturnsOnlyAfterTheNewPlanIsObservable() {
        val base = runBlocking { repo.saveProfile(profile); repo.savePlan(template) }
        val vm = vm()
        await { vm.activePlan.value.valueOrNull() != null && vm.profile.value.valueOrNull() != null }
        val option = base.copy(days = base.days.map { day -> day.copy(items = day.items.map { it.copy(sets = 3) }) })
        var finished = false
        var error: String? = "not completed"
        var observed: WorkoutPlan? = null
        CoroutineScope(Dispatchers.Main.immediate).launch {
            error = vm.confirmChatPlan(base, profile, option)
            observed = vm.activePlan.value.valueOrNull()
            finished = true
        }
        await { finished }
        assertNull(error)
        assertEquals(2, observed?.version)
        assertEquals(option.days, observed?.days)
        assertEquals(profile, vm.profile.value.valueOrNull())
    }

    @Test fun staleProfileShowsActionableErrorWithoutWritingAnOption() {
        val base = runBlocking { repo.saveProfile(profile); repo.savePlan(template) }
        val vm = vm()
        await { vm.activePlan.value.valueOrNull() != null && vm.profile.value.valueOrNull() != null }
        runBlocking { repo.saveProfile(profile.copy(weightKg = 71.0)) }
        val option = base.copy(days = base.days.map { day -> day.copy(items = day.items.map { it.copy(sets = 3) }) })
        var finished = false
        var error: String? = null
        CoroutineScope(Dispatchers.Main.immediate).launch {
            error = vm.confirmChatPlan(base, profile, option)
            finished = true
        }
        await { finished }
        assertTrue(error!!.contains("档案刚刚更新"))
        assertEquals(base, vm.activePlan.value.valueOrNull())
    }

    @Test fun sameSessionAndSetCountRefreshImmediatelyAndKeepQueuedCompletion() {
        val base = runBlocking { repo.saveProfile(profile); repo.savePlan(template) }
        val session = runBlocking { repo.startSession(base, 0) }
        val vm = vm()
        await { vm.workout != null && vm.profile.value.valueOrNull() != null && vm.activePlan.value.valueOrNull() != null }
        val option = base.copy(days = base.days.mapIndexed { i, day ->
            if (i == 0) day.copy(items = listOf(PlanItem("dumbbell-lunge", 2, 8, 12), day.items[1])) else day
        })
        var finished = false
        var error: String? = "pending"
        CoroutineScope(Dispatchers.Main.immediate).launch {
            val plank = vm.workout!!.sets[2]
            vm.updateSet(2, plank.copy(done = true, seconds = 31))
            error = vm.confirmChatPlan(base, profile, option)
            assertEquals(2, vm.workout!!.planVersion)
            assertEquals(2, vm.openSession.value.valueOrNull()!!.planVersion)
            finished = true
        }
        await { finished }
        assertNull(error)
        assertEquals(session.id, vm.workout!!.id)
        assertEquals(session.sets.size, vm.workout!!.sets.size)
        assertEquals("dumbbell-lunge", vm.workout!!.sets.first().exerciseId)
        assertEquals(31, vm.workout!!.completedSets.single().seconds)
        // A callback captured by the old composition must not overwrite the replacement.
        vm.updateSet(0, session.sets.first().copy(done = true))
        assertEquals("dumbbell-lunge", vm.workout!!.sets.first().exerciseId)
        assertFalse(vm.workout!!.sets.first().done)
    }
}
