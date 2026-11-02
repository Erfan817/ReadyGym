package com.readygym.app.data

import androidx.room.withTransaction
import com.readygym.core.exercise.ExerciseLibrary
import com.readygym.core.exercise.MuscleExercisePicker
import com.readygym.core.exercise.MusclePlanEdit
import com.readygym.core.plan.PlanValidator
import com.readygym.core.plan.WorkoutPlan
import com.readygym.core.profile.UserProfile
import com.readygym.core.workout.PostWorkoutFeedback
import com.readygym.core.workout.SetLog
import com.readygym.core.workout.WorkoutSession
import com.readygym.core.workout.withUpdatedPlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId

sealed interface ConversationPlanSaveResult {
    data class Saved(val plan: WorkoutPlan) : ConversationPlanSaveResult
    data class Rejected(val message: String) : ConversationPlanSaveResult
}

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

    /** A reviewed chat option is a compare-and-save operation, never an unconditional replacement. */
    suspend fun applyConversationPlan(
        expectedPlan: WorkoutPlan,
        expectedProfile: UserProfile,
        proposal: WorkoutPlan,
        library: ExerciseLibrary,
    ): ConversationPlanSaveResult = db.withTransaction {
        val profile = dao.profileNow()?.let { UserProfile.fromJson(it.json) }
            ?: return@withTransaction ConversationPlanSaveResult.Rejected("先填写档案，再和教练调整计划。")
        if (profile != expectedProfile) {
            return@withTransaction ConversationPlanSaveResult.Rejected("档案刚刚更新了，请让教练按新档案重新给出选项。")
        }
        val current = dao.activePlanNow()?.toPlan()?.withDefaultRest()
            ?: return@withTransaction ConversationPlanSaveResult.Rejected("当前还没有计划，请先生成并采用一份计划。")
        if (current != expectedPlan.withDefaultRest()) {
            return@withTransaction ConversationPlanSaveResult.Rejected("计划刚刚更新了，请让教练按当前计划重新给出选项。")
        }
        if (proposal.daysPerWeek != current.daysPerWeek || proposal.days.size != current.days.size) {
            return@withTransaction ConversationPlanSaveResult.Rejected("这次选项改变了训练天数，请先确认新的训练档案再重新排计划。")
        }
        val normalized = proposal.withDefaultRest()
        val allowed = library.availableFor(profile.location, profile.equipment, profile.experience).map { it.id }.toSet()
        val problems = PlanValidator(library, allowed).problems(normalized.days)
        val nonFiniteLoad = normalized.days.any { day -> day.items.any { it.loadKg?.isFinite() == false } }
        if (problems.isNotEmpty() || nonFiniteLoad) {
            return@withTransaction ConversationPlanSaveResult.Rejected("这个选项不适合当前器械或训练要求，请让教练换一组选项。")
        }
        if (normalized.days == current.days) {
            return@withTransaction ConversationPlanSaveResult.Rejected("这个选项和当前安排一样，请让教练给出具体改动。")
        }
        val stored = storePlan(normalized.copy(
            reason = normalized.reason.ifBlank { "教练聊天调整" }.take(240),
            progressionBaseVersion = current.progressionBaseVersion ?: current.version,
        ), preserveProgression = true)
        syncOpenSession(stored)
        ConversationPlanSaveResult.Saved(stored)
    }

    /** Called inside the plan transaction, so a restart cannot observe half of an adjustment. */
    private suspend fun syncOpenSession(plan: WorkoutPlan) {
        val entity = dao.openSessionNow() ?: return
        val open = entity.toSession()
        val previousDay = dao.planVersionNow(open.planVersion)?.toPlan()?.days?.getOrNull(open.dayIndex)
        val updated = open.withUpdatedPlan(plan, previousDay)
        if (updated == open) return
        dao.updateSession(entity.session.copy(planVersion = updated.planVersion, dayTitle = updated.dayTitle))
        dao.deleteSets(open.id)
        dao.insertSets(updated.sets.map { it.toEntity(open.id) })
    }

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

    /** Serialize taps and enforce the daily gate even while UI flows are still catching up. */
    suspend fun startSessionIfAvailable(plan: WorkoutPlan, dayIndex: Int): WorkoutSession? = db.withTransaction {
        dao.openSessionNow()?.let { return@withTransaction it.toSession() }
        if (dayIndex !in plan.days.indices || dao.activePlanNow()?.version != plan.version) return@withTransaction null
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        if (dao.completedBetween(start, end) != null) return@withTransaction null
        startSession(plan, dayIndex)
    }

    suspend fun startSession(plan: WorkoutPlan, dayIndex: Int): WorkoutSession = db.withTransaction {
        val day = plan.days[dayIndex]
        val last = lastSets(day.items.map { it.exerciseId }, plan.version)
        val draft = WorkoutSession.start(plan.version, dayIndex, day, clock(), last)
        val id = dao.insertSession(SessionEntity(0, draft.planVersion, dayIndex, draft.dayTitle, draft.startedAtMillis, null))
        dao.insertSets(draft.sets.map { it.toEntity(id) })
        draft.copy(id = id)
    }

    /** Replaces the logged sets of an in-progress session (called on every edit, so a crash loses nothing). */
    suspend fun updateSets(sessionId: Long, sets: List<SetLog>, expectedPlanVersion: Int? = null) = db.withTransaction {
        val current = dao.session(sessionId)?.session ?: return@withTransaction
        if (current.endedAt != null || (expectedPlanVersion != null && current.planVersion != expectedPlanVersion)) return@withTransaction
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
