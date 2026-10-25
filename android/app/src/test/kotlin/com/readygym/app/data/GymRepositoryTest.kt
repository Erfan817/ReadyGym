package com.readygym.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.readygym.core.plan.PlanDay
import com.readygym.core.plan.PlanItem
import com.readygym.core.plan.WorkoutPlan
import com.readygym.core.workout.BodyArea
import com.readygym.core.workout.Effort
import com.readygym.core.workout.PostWorkoutFeedback
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GymRepositoryTest {
    private var now = 1_000_000L
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GymDatabase::class.java)
        .allowMainThreadQueries().build()
    private val repo = GymRepository(db) { now }

    private val plan = WorkoutPlan(0, 0, "初始计划", "两天轮换", 2, listOf(
        PlanDay("下肢", items = listOf(PlanItem("goblet-squat", 2, 10, 10, loadKg = 12.0), PlanItem("plank", 2, seconds = 30))),
        PlanDay("上肢", items = listOf(PlanItem("push-up", 2, 8, 12), PlanItem("dumbbell-row", 2, 10, 10, loadKg = 10.0))),
    ))

    @After fun close() = db.close()

    @Test fun plansAreVersionedAndHistoryIsKept() = runTest {
        assertEquals(1, repo.savePlan(plan).version)
        now += 1000
        val v2 = repo.savePlan(plan.copy(reason = "练后调整"))
        assertEquals(2, v2.version)
        assertEquals(now, v2.createdAtMillis)
        assertEquals("练后调整", repo.activePlan.first()?.reason)
        assertEquals(listOf(2, 1), repo.planHistory.first().map { it.version })
    }

    @Test fun sessionLifecycleLogsSetsAndRotatesDays() = runTest {
        val p = repo.savePlan(plan)
        assertEquals(0, repo.nextDayIndex(p))
        val s = repo.startSession(p, 0)
        assertEquals(4, s.sets.size)
        assertEquals(s.id, repo.openSession.first()?.id)

        val logged = s.sets.mapIndexed { i, l -> if (i == 0) l.copy(done = true, loadKg = 14.0) else l }
        repo.updateSets(s.id, logged)
        now += 40 * 60_000
        val done = repo.finishSession(s.id)!!
        assertEquals(40, done.durationMinutes)
        assertEquals(140.0, done.volumeKg, 0.0)
        assertNull(repo.openSession.first())
        assertEquals(1, repo.history.first().size)
        assertEquals(1, repo.nextDayIndex(p))

        // The next session of the same day pre-fills the load the user actually lifted.
        assertEquals(14.0, repo.lastSets(listOf("goblet-squat"))["goblet-squat"]!!.single().loadKg)
        assertEquals(14.0, repo.startSession(p, 0).sets.first().loadKg)
        // A newly confirmed plan version prescribes its own load again.
        val v2 = repo.savePlan(plan.copy(days = listOf(plan.days[0].copy(items = listOf(plan.days[0].items[0].copy(loadKg = 16.0), plan.days[0].items[1])))))
        assertEquals(16.0, repo.startSession(v2, 0).sets.first().loadKg)
    }

    @Test fun discardedSessionLeavesNoTrace() = runTest {
        val s = repo.startSession(repo.savePlan(plan), 1)
        repo.discardSession(s.id)
        assertNull(repo.openSession.first())
        assertNull(repo.session(s.id))
    }

    @Test fun feedbackRoundTrips() = runTest {
        val s = repo.startSession(repo.savePlan(plan), 0)
        repo.finishSession(s.id)
        val f = PostWorkoutFeedback(s.id, Effort.TIRING, setOf(BodyArea.SHOULDER), note = "右肩刺痛")
        repo.saveFeedback(f)
        assertEquals(f, repo.feedback(s.id))
        assertEquals(f, repo.allFeedback.first()[s.id])
    }
}
