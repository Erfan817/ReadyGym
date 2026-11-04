package com.readygym.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.readygym.app.data.GymDatabase
import com.readygym.app.data.GymRepository
import com.readygym.app.data.ConversationPlanSaveResult
import com.readygym.core.ai.ChatModel
import com.readygym.core.ai.PlanCoach
import com.readygym.core.ai.PlanProposal
import com.readygym.core.ai.PlanRejectedException
import com.readygym.core.exercise.ExerciseLibrary
import com.readygym.core.exercise.MusclePlanEdit
import com.readygym.core.plan.PlanChange
import com.readygym.core.plan.PlanDiff
import com.readygym.core.plan.WorkoutPlan
import com.readygym.core.profile.UserProfile
import com.readygym.core.workout.PostWorkoutFeedback
import com.readygym.core.workout.SetLog
import com.readygym.core.workout.WorkoutSession
import com.readygym.core.workout.latestWorkoutOnDate
import com.readygym.core.workout.recoveryCheckInDue
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the app is in loading Room: the screens must not flash "no plan" before the first read. */
sealed interface Loaded<out T> {
    data object Loading : Loaded<Nothing>
    data class Ready<T>(val value: T) : Loaded<T>
}

/** The AI's proposed change to [base], waiting for the user to accept or keep the old plan. */
data class PlanAdjustment(val base: WorkoutPlan, val proposal: PlanProposal, val changes: List<PlanChange>, val discomfort: Boolean) {
    val changed: List<PlanChange> get() = changes.filter { it.kind != com.readygym.core.plan.ChangeKind.KEPT }
}

/**
 * State for the training side of the app: the stored profile and plan, plan generation, and the
 * workout in progress. Everything durable goes through [GymRepository]; AI proposals stay in memory
 * until the user accepts them.
 */
class GymViewModel(
    private val repo: GymRepository,
    val library: ExerciseLibrary,
    /** The account TOKEN; null until the user has registered. */
    private val apiKey: () -> String?,
    private val modelFor: (String) -> ChatModel,
    /** Workout upload and leaderboard; null in tests and when no server is configured. */
    private val server: ServerApi? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val recoveryStore: RecoveryCheckInStore? = null,
) : ViewModel() {

    val profile: StateFlow<Loaded<UserProfile?>> = repo.profile.loaded()
    val activePlan: StateFlow<Loaded<WorkoutPlan?>> = repo.activePlan.loaded()
    val openSession: StateFlow<Loaded<WorkoutSession?>> = repo.openSession.loaded()
    val history: StateFlow<List<WorkoutSession>> =
        repo.history.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val savedFeedback: StateFlow<Loaded<Map<Long, PostWorkoutFeedback>>> = repo.allFeedback.loaded()

    var proposal by mutableStateOf<PlanProposal?>(null)
        private set
    var generating by mutableStateOf(false)
        private set
    var planError by mutableStateOf<String?>(null)
        private set

    // A blocking model may return after cancellation. The epoch also guards its late result and
    // finally block, so neither can resurrect a stale draft or stop a newer request's spinner.
    private var draftEpoch = 0L
    private var generationJob: Job? = null
    private var confirmationJob: Job? = null
    private var profileSaveJob: Job? = null
    private var profileSaveEpoch = 0L
    private var savingProfile = false
    private var savingChatPlan = false
    private val workoutWrites = Mutex()
    private var pendingSetWrite: Job? = null
    private var proposalProfile: UserProfile? = null

    /** Day the next workout should use; recomputed whenever the plan or history changes. */
    var nextDay by mutableStateOf(0)
        private set

    /** The previous session's completed sets per exercise, for the "上次" column. */
    var lastSets by mutableStateOf<Map<String, List<SetLog>>>(emptyMap())
        private set

    var today by mutableStateOf(localDate())
        private set
    var dateZone by mutableStateOf(ZoneId.systemDefault())
        private set

    val completedToday: WorkoutSession?
        get() = latestWorkoutOnDate(history.value, today, dateZone)

    private var recoveryHandledThroughId by mutableStateOf(recoveryStore?.handledThroughId() ?: 0L)
    val recoveryDue: WorkoutSession?
        get() {
            val feedback = savedFeedback.value.valueOrNull() ?: return null
            val checkpoint = maxOf(recoveryHandledThroughId, feedback.keys.maxOrNull() ?: 0L)
            return recoveryCheckInDue(history.value, checkpoint, today, dateZone)
        }

    fun skipRecoveryCheckIn() {
        markRecoveryHandled(history.value.maxOfOrNull { it.id } ?: 0L)
    }

    private fun markRecoveryHandled(sessionId: Long) {
        recoveryHandledThroughId = maxOf(recoveryHandledThroughId, sessionId)
        recoveryStore?.markHandled(recoveryHandledThroughId)
    }

    fun refreshDate(date: LocalDate = localDate()) {
        dateZone = ZoneId.systemDefault()
        today = date
    }

    private fun localDate(): LocalDate = Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault()).toLocalDate()

    /** Local copy of the open session so typing in a field does not wait for a Room round trip. */
    var workout by mutableStateOf<WorkoutSession?>(null)
        private set

    /** The finished session the user is giving feedback on. */
    var reviewing by mutableStateOf<WorkoutSession?>(null)
        private set
    var adjustment by mutableStateOf<PlanAdjustment?>(null)
        private set
    var adjusting by mutableStateOf(false)
        private set
    var adjustError by mutableStateOf<String?>(null)
        private set

    var leaderboard by mutableStateOf<Leaderboard?>(null)
        private set
    var leaderboardError by mutableStateOf<String?>(null)
        private set
    var leaderboardLoading by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            repo.activePlan.collect { plan -> nextDay = plan?.let { repo.nextDayIndex(it) } ?: 0 }
        }
        viewModelScope.launch {
            repo.history.collect { activePlan.value.valueOrNull()?.let { nextDay = repo.nextDayIndex(it) } }
        }
        viewModelScope.launch {
            repo.openSession.collect { open ->
                if (open?.id != workout?.id || open?.planVersion != workout?.planVersion) {
                    workout = open
                    lastSets = open?.let { s -> repo.lastSets(s.sets.map { it.exerciseId }) } ?: emptyMap()
                }
            }
        }
    }

    fun saveProfile(p: UserProfile, onSaved: () -> Unit = {}) {
        invalidateDraft()
        savingProfile = true
        val saveEpoch = ++profileSaveEpoch
        profileSaveJob?.cancel()
        profileSaveJob = viewModelScope.launch {
            try {
                repo.saveProfile(p)
                // The next screen reads this StateFlow, not the repository's completed write.
                profile.first { it is Loaded.Ready && it.value == p }
                if (saveEpoch == profileSaveEpoch) {
                    savingProfile = false
                    profileSaveJob = null
                    onSaved()
                }
            } finally {
                if (saveEpoch == profileSaveEpoch) { savingProfile = false; profileSaveJob = null }
            }
        }
    }

    fun generatePlan() {
        if (savingProfile || savingChatPlan || generating || confirmationJob?.isActive == true) return
        val p = profile.value.valueOrNull() ?: return
        val key = apiKey()
        if (key.isNullOrBlank()) { planError = NOT_SIGNED_IN; return }
        val epoch = ++draftEpoch
        proposal = null; proposalProfile = null
        generating = true; planError = null
        generationJob = viewModelScope.launch {
            try {
                val generated = withContext(io) { PlanCoach(modelFor(key), library).generate(p) }
                if (epoch == draftEpoch && !savingProfile && profile.value.valueOrNull() == p) {
                    proposal = generated
                    proposalProfile = p
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PlanRejectedException) {
                if (epoch == draftEpoch) planError = "AI 两次给出的计划都不符合要求，请重试。（${e.problems.first()}）"
            } catch (e: CoachException) {
                if (epoch == draftEpoch) planError = planErrorText(e)
            } finally {
                if (epoch == draftEpoch) { generating = false; generationJob = null }
            }
        }
    }

    private fun invalidateDraft() {
        draftEpoch++
        generationJob?.cancel(); generationJob = null
        confirmationJob?.cancel(); confirmationJob = null
        generating = false
        proposal = null; proposalProfile = null; planError = null
    }

    fun discardProposal() { invalidateDraft() }

    fun confirmProposal() {
        if (savingProfile || savingChatPlan || generating || confirmationJob?.isActive == true) return
        val p = proposal ?: return
        val source = proposalProfile
        if (source == null || source != profile.value.valueOrNull()) {
            invalidateDraft()
            planError = "档案刚刚更新了，请重新生成计划。"
            return
        }
        val epoch = draftEpoch
        confirmationJob = viewModelScope.launch {
            try {
                if (epoch != draftEpoch || savingProfile || proposal != p || profile.value.valueOrNull() != source) return@launch
                repo.savePlan(p.plan)
                if (epoch == draftEpoch) { proposal = null; proposalProfile = null }
            } finally {
                if (epoch == draftEpoch) confirmationJob = null
            }
        }
    }

    /** Null means the append is committed and observable; failures leave the current plan intact. */
    suspend fun addExerciseToPlan(exerciseId: String, dayIndex: Int, expectedVersion: Int): String? {
        val p = profile.value.valueOrNull() ?: return "先去填写档案，再添加动作。"
        if (savingChatPlan || generating || adjusting || proposal != null || adjustment != null) {
            return "请先处理完计划草稿，再来添加动作。"
        }
        return try {
            when (val result = repo.addExerciseToPlan(exerciseId, dayIndex, expectedVersion, p, library)) {
                is MusclePlanEdit.Rejected -> result.message
                is MusclePlanEdit.Added -> {
                    activePlan.first { (it.valueOrNull()?.version ?: 0) >= result.plan.version }
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            "这次没能保存，请留在这里重试。"
        }
    }

    /** The selected option becomes durable only here, after the explicit in-chat confirmation. */
    suspend fun confirmChatPlan(base: WorkoutPlan, expectedProfile: UserProfile, plan: WorkoutPlan): String? {
        if (savingProfile) return "档案正在保存，请稍等，再让教练按新档案给出选项。"
        if (savingChatPlan || generating || adjusting || confirmationJob?.isActive == true || proposal != null || adjustment != null) {
            return "请先处理完当前计划草稿，再来确认这个选项。"
        }
        savingChatPlan = true
        return try {
            // A tap may have updated local state before its launched Room write even starts.
            pendingSetWrite?.join()
            when (val result = workoutWrites.withLock { repo.applyConversationPlan(base, expectedProfile, plan, library) }) {
                is ConversationPlanSaveResult.Rejected -> result.message
                is ConversationPlanSaveResult.Saved -> {
                    activePlan.first { (it.valueOrNull()?.version ?: 0) >= result.plan.version }
                    val updated = repo.openSession.first()
                    workout = updated
                    lastSets = updated?.let { repo.lastSets(it.sets.map { set -> set.exerciseId }) } ?: emptyMap()
                    openSession.first { it is Loaded.Ready && it.value == updated }
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            "这次没能保存，当前计划还在，请留在这里重试。"
        } finally {
            savingChatPlan = false
        }
    }

    fun startWorkout(dayIndex: Int = nextDay, onStarted: (WorkoutSession?) -> Unit = {}) {
        refreshDate()
        workout?.let { onStarted(it); return }
        val plan = activePlan.value.valueOrNull()
        if (plan == null || completedToday != null || dayIndex !in plan.days.indices) {
            onStarted(null)
            return
        }
        viewModelScope.launch {
            val started = repo.startSessionIfAvailable(plan, dayIndex)
            if (started != null) {
                workout = started
                lastSets = repo.lastSets(started.sets.map { it.exerciseId })
            }
            onStarted(started)
        }
    }

    /** Log or undo a set without starting a rest timer. */
    fun updateSet(index: Int, set: SetLog) {
        if (savingChatPlan) return
        val s = workout ?: return
        val old = s.sets.getOrNull(index) ?: return
        if (old.exerciseId != set.exerciseId || old.setIndex != set.setIndex) return
        val updated = s.copy(sets = s.sets.toMutableList().also { it[index] = set })
        workout = updated
        val previousWrite = pendingSetWrite
        pendingSetWrite = viewModelScope.launch {
            previousWrite?.join()
            workoutWrites.withLock { repo.updateSets(s.id, updated.sets, s.planVersion) }
        }
    }

    /** Ends the workout and hands the stored session to [onFinished] (the feedback screen). */
    fun finishWorkout(onFinished: (WorkoutSession) -> Unit = {}) {
        if (savingChatPlan) return
        val s = workout ?: return
        viewModelScope.launch {
            pendingSetWrite?.join()
            val finished = workoutWrites.withLock {
                repo.updateSets(s.id, s.sets, s.planVersion)
                repo.finishSession(s.id)
            }
            if (finished != null) {
                // Home must observe today's completion before returning from the feedback screen.
                history.first { sessions -> sessions.any { it.id == finished.id } }
                openSession.first { it is Loaded.Ready && it.value == null }
                workout = null
                refreshDate()
            }
            finished?.let(onFinished)
            // Best effort: a failed upload is retried the next time the leaderboard opens.
            finished?.let { runCatching { upload(listOf(it)) } }
        }
    }

    fun discardWorkout() {
        if (savingChatPlan) return
        val s = workout ?: return
        workout = null
        viewModelScope.launch { repo.discardSession(s.id) }
    }

    /** Loads the session to review; the id survives process death, the object does not. */
    fun openReview(sessionId: Long) {
        if (reviewing?.id == sessionId) return
        adjustment = null; adjustError = null
        viewModelScope.launch { reviewing = repo.session(sessionId) }
    }

    fun saveFeedbackOnly(f: PostWorkoutFeedback, onDone: () -> Unit) {
        viewModelScope.launch {
            repo.saveFeedback(f.copy(reportedAtMillis = clock()))
            markRecoveryHandled(f.sessionId)
            closeReview(); onDone()
        }
    }

    /** Saves the feedback, then asks the coach to adjust the whole plan from it. */
    fun requestAdjustment(f: PostWorkoutFeedback) {
        val s = reviewing ?: return
        val p = profile.value.valueOrNull() ?: return
        val plan = activePlan.value.valueOrNull() ?: return
        val key = apiKey()
        if (adjusting || savingChatPlan) return
        if (key.isNullOrBlank()) { adjustError = NOT_SIGNED_IN; return }
        adjusting = true; adjustError = null
        viewModelScope.launch {
            try {
                val reported = f.copy(reportedAtMillis = clock())
                repo.saveFeedback(reported)
                markRecoveryHandled(f.sessionId)
                val recent = repo.recentSessions(5)
                val proposal = withContext(io) { PlanCoach(modelFor(key), library).adjust(p, plan, s, reported, recent) }
                adjustment = PlanAdjustment(plan, proposal, PlanDiff.between(plan, proposal.plan), f.hasDiscomfort)
            } catch (e: PlanRejectedException) {
                adjustError = "AI 两次给出的调整都不符合要求，请重试。（${e.problems.first()}）"
            } catch (e: CoachException) {
                adjustError = planErrorText(e)
            } finally {
                adjusting = false
            }
        }
    }

    /** Stores the adjusted plan as a new version; the old one stays in the history. */
    fun acceptAdjustment(onDone: () -> Unit) {
        if (savingChatPlan) return
        val a = adjustment ?: return
        viewModelScope.launch { repo.savePlan(a.proposal.plan); closeReview(); onDone() }
    }

    fun closeReview() { reviewing = null; adjustment = null; adjustError = null }

    /** Uploads this week's workouts (idempotent on the server), then loads the weekly board. */
    fun refreshLeaderboard() {
        val api = server ?: run { leaderboardError = "还没有配置服务器"; return }
        if (leaderboardLoading) return
        leaderboardLoading = true; leaderboardError = null
        viewModelScope.launch {
            try {
                val weekStart = java.time.LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                    .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                upload(history.value.filter { it.startedAtMillis >= weekStart })
                leaderboard = withContext(io) { api.leaderboard() }
            } catch (e: CoachException) {
                leaderboardError = e.detail ?: planErrorText(e)
            } finally {
                leaderboardLoading = false
            }
        }
    }

    /** Returns the board from the last upload, so state is written back on the main thread. */
    private suspend fun upload(sessions: List<WorkoutSession>) {
        val api = server ?: return
        val days = profile.value.valueOrNull()?.daysPerWeek ?: 3
        val board = withContext(io) {
            sessions.filter { it.endedAtMillis != null && it.completedSets.isNotEmpty() }
                .map { api.uploadWorkout(it, days) }.lastOrNull()
        }
        board?.let { leaderboard = it }
    }

    private fun <T> kotlinx.coroutines.flow.Flow<T>.loaded(): StateFlow<Loaded<T>> =
        kotlinx.coroutines.flow.flow<Loaded<T>> { collect { emit(Loaded.Ready(it)) } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, Loaded.Loading)

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = context.applicationContext
                val library = ExerciseLibrary.parse(app.assets.open("exercise-library.json").bufferedReader().use { it.readText() })
                val account = AccountStore(app)
                val server = ServerApi(BuildConfig.SERVICE_BASE_URL) { account.load()?.TOKEN }
                return GymViewModel(GymRepository(GymDatabase.get(app)), library, apiKey = { account.load()?.TOKEN },
                    modelFor = { DeepSeekApi.bundled(maxTokens = 4000, readTimeoutMs = 120_000)
                        ?: server.chatModel(maxTokens = 4000, readTimeoutMs = 120_000) },
                    server = server, recoveryStore = RecoveryCheckInStore(app)) as T
            }
        }
    }
}

fun <T> Loaded<T>.valueOrNull(): T? = (this as? Loaded.Ready<T>)?.value

private const val NOT_SIGNED_IN = "还没有登录，请重新打开 App 输入昵称。"

/** Short Chinese explanation for a failure; the server's own message wins when it sent one. */
internal fun planErrorText(e: CoachException): String = e.detail ?: when (e.code) {
    "unauthorized" -> "登录已失效，请到「我的」里重新登录。"
    "daily_limit" -> "今天的 AI 次数用完了，明天再来吧。"
    "upstream_rate_limited" -> "AI 服务繁忙，稍等一会儿再试。"
    "network_error" -> "连不上服务器，检查网络后重试。"
    "ai_not_configured", "upstream_unavailable" -> "AI 服务暂时不可用，稍后再试。"
    else -> "AI 服务出错（${e.code}），请重试。"
}
