package com.readygym.app.data

import androidx.room.withTransaction
import com.readygym.core.exercise.ExerciseLibrary
import com.readygym.core.exercise.MuscleExercisePicker
import com.readygym.core.exercise.MusclePlanEdit
import com.readygym.core.plan.WorkoutPlan
import com.readygym.core.profile.UserProfile
import com.readygym.core.workout.PostWorkoutFeedback
import com.readygym.core.workout.SetLog
import com.readygym.core.workout.WorkoutSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** The single source of truth for everything the app stores on the phone. */
class GymRepository(private val db: GymDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = db.dao()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val profile: Flow<UserProfile?> = dao.profile().map { it?.let { e -> UserProfile.fromJson(e.json) } }
    suspend fun saveProfile(p: UserProfile) = dao.saveProfile(ProfileEntity(json = p.toJson(), updatedAt = clock()))

    // Old installations saved 60/90-second rests. Apply the current setting as soon as the
    // existing plan is read, retaining its version, rotation and the archived original JSON.
    val activePlan: Flow<WorkoutPlan?> = dao.activePlan().map { it?.toPlan()?.withDefaultRest() }
    val planHistory: Flow<List<WorkoutPlan>> = dao.planVersions().map { list -> list.map { it.toPlan() } }

    /** Stores [plan] as the next version (the version field is assigned here) and returns it. */
    suspend fun savePlan(plan: WorkoutPlan): WorkoutPlan = storePlan(plan, preserveProgression = false)

    private suspend fun storePlan(plan: WorkoutPlan, preserveProgression: Boolean): WorkoutPlan = db.withTransaction {
        val next = (dao.activePlanNow()?.version ?: 0) + 1
        val stored = plan.withDefaultRest().copy(version = next, createdAtMillis = clock(),
            progressionBaseVersion = plan.progressionBaseVersion.takeIf { preserveProgression })
        dao.insertPlan(PlanVersionEntity(next, stored.createdAtMillis, stored.reason, stored.toJson()))
        stored
    }

    suspend fun planVersion(version: Int): WorkoutPlan? = dao.planVersionNow(version)?.toPlan()

    /** Compare and append in one transaction so two taps cannot overwrite one another. */
    suspend fun addExerciseToPlan(
        exerciseId: String,
        dayIndex: Int,
        expectedVersion: Int,
        expectedProfile: UserProfile,
        library: ExerciseLibrary,
    ): MusclePlanEdit = db.withTransaction {
        val profile = dao.profileNow()?.let { UserProfile.fromJson(it.json) }
            ?: return@withTransaction MusclePlanEdit.Rejected("先去填写档案，再添加动作。")
        if (profile != expectedProfile) {
            return@withTransaction MusclePlanEdit.Rejected("档案刚刚更新了，请返回后重新选择动作。")
        }
        val plan = dao.activePlanNow()?.toPlan()?.withDefaultRest()
            ?: return@withTransaction MusclePlanEdit.Rejected("先在计划页生成并采用计划，再来添加动作。")
        if (plan.version != expectedVersion) {
            return@withTransaction MusclePlanEdit.Rejected("计划刚刚更新了，请重新选择训练日。")
        }
        when (val edit = MuscleExercisePicker.addToPlan(library, profile, plan, exerciseId, dayIndex)) {
            is MusclePlanEdit.Rejected -> edit
            is MusclePlanEdit.Added -> MusclePlanEdit.Added(storePlan(edit.plan, preserveProgression = true))
        }
    }

    val openSession: Flow<WorkoutSession?> = dao.openSession().map { it?.toSession() }
    val history: Flow<List<WorkoutSession>> = dao.finishedSessions().map { list -> list.map { it.toSession() } }

    suspend fun startSession(plan: WorkoutPlan, dayIndex: Int): WorkoutSession = db.withTransaction {
        val day = plan.days[dayIndex]
        val last = lastSets(day.items.map { it.exerciseId }, plan.version)
        val draft = WorkoutSession.start(plan.version, dayIndex, day, clock(), last)
        val id = dao.insertSession(SessionEntity(0, draft.planVersion, dayIndex, draft.dayTitle, draft.startedAtMillis, null))
        dao.insertSets(draft.sets.map { it.toEntity(id) })
        draft.copy(id = id)
    }

    /** Replaces the logged sets of an in-progress session (called on every edit, so a crash loses nothing). */
    suspend fun updateSets(sessionId: Long, sets: List<SetLog>) = db.withTransaction {
        dao.deleteSets(sessionId)
        dao.insertSets(sets.map { it.toEntity(sessionId) })
    }

    suspend fun finishSession(sessionId: Long): WorkoutSession? = db.withTransaction {
        val s = dao.session(sessionId) ?: return@withTransaction null
        dao.updateSession(s.session.copy(endedAt = clock()))
        dao.session(sessionId)?.toSession()
    }

    suspend fun discardSession(sessionId: Long) = dao.deleteSession(sessionId)

    suspend fun session(id: Long): WorkoutSession? = dao.session(id)?.toSession()
    suspend fun recentSessions(limit: Int): List<WorkoutSession> = dao.recentFinished(limit).map { it.toSession() }

    /** Last completed sets per exercise, used for the "上次" column and to pre-fill loads. */
    suspend fun lastSets(exerciseIds: List<String>, planVersion: Int? = null): Map<String, List<SetLog>> =
        exerciseIds.distinct().associateWith { id -> dao.lastSetsFor(id, planVersion).map { it.toModel() } }.filterValues { it.isNotEmpty() }

    /** Rotate through the plan's days: the day after the last finished session of this plan version. */
    suspend fun nextDayIndex(plan: WorkoutPlan): Int {
        val last = dao.recentFinished(1).firstOrNull()?.session ?: return 0
        val lastPlan = dao.planVersionNow(last.planVersion)?.toPlan() ?: return 0
        val sameRotation = (lastPlan.progressionBaseVersion ?: lastPlan.version) ==
            (plan.progressionBaseVersion ?: plan.version)
        return if (sameRotation && plan.days.isNotEmpty()) (last.dayIndex + 1) % plan.days.size else 0
    }

    suspend fun saveFeedback(f: PostWorkoutFeedback) =
        dao.saveFeedback(FeedbackEntity(f.sessionId, json.encodeToString(PostWorkoutFeedback.serializer(), f)))
    suspend fun feedback(sessionId: Long): PostWorkoutFeedback? =
        dao.feedback(sessionId)?.let { json.decodeFromString(PostWorkoutFeedback.serializer(), it.json) }
    val allFeedback: Flow<Map<Long, PostWorkoutFeedback>> = dao.allFeedback().map { list ->
        list.associate { it.sessionId to json.decodeFromString(PostWorkoutFeedback.serializer(), it.json) }
    }
}

private fun PlanVersionEntity.toPlan() = WorkoutPlan.fromJson(json).copy(version = version, createdAtMillis = createdAt, reason = reason)
private fun SetLog.toEntity(sessionId: Long) = SetLogEntity(0, sessionId, exerciseId, setIndex, reps, seconds, loadKg, done)
private fun SetLogEntity.toModel() = SetLog(exerciseId, setIndex, reps, seconds, loadKg, done)
private fun SessionWithSets.toSession() = WorkoutSession(session.id, session.planVersion, session.dayIndex, session.dayTitle,
    session.startedAt, session.endedAt, sets.sortedBy { it.id }.map { it.toModel() })
