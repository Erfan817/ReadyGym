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
import com.readygym.core.ai.ChatModel
import com.readygym.core.ai.PlanCoach
import com.readygym.core.ai.PlanProposal
import com.readygym.core.ai.PlanRejectedException
import com.readygym.core.exercise.ExerciseLibrary
import com.readygym.core.plan.PlanChange
import com.readygym.core.plan.PlanDiff
import com.readygym.core.plan.WorkoutPlan
import com.readygym.core.profile.UserProfile
import com.readygym.core.workout.PostWorkoutFeedback
import com.readygym.core.workout.SetLog
import com.readygym.core.workout.WorkoutSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * workout in progress. Everything durable goes through [GymRepository]; only the AI proposal and the
 * rest timer live in memory, because neither should survive a decision the user has not made.
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
) : ViewModel() {

    val profile: StateFlow<Loaded<UserProfile?>> = repo.profile.loaded()
    val activePlan: StateFlow<Loaded<WorkoutPlan?>> = repo.activePlan.loaded()
    val openSession: StateFlow<Loaded<WorkoutSession?>> = repo.openSession.loaded()
    val history: StateFlow<List<WorkoutSession>> =
        repo.history.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    var proposal by mutableStateOf<PlanProposal?>(null)
        private set
    var generating by mutableStateOf(false)
        private set
    var planError by mutableStateOf<String?>(null)
        private set

    /** Day the next workout should use; recomputed whenever the plan or history changes. */
    var nextDay by mutableStateOf(0)
        private set

    /** The previous session's completed sets per exercise, for the "上次" column. */
    var lastSets by mutableStateOf<Map<String, List<SetLog>>>(emptyMap())
        private set

    /** Wall-clock end of the current rest, or null when not resting. */
    var restEndsAt by mutableStateOf<Long?>(null)
        private set
    var restTotalSeconds by mutableStateOf(0)
        private set

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
                if (open?.id != workout?.id) {
                    workout = open
                    restEndsAt = null
                    lastSets = open?.let { s -> repo.lastSets(s.sets.map { it.exerciseId }) } ?: emptyMap()
                }
            }
        }
    }

    fun saveProfile(p: UserProfile) {
        viewModelScope.launch { repo.saveProfile(p) }
    }

    fun generatePlan() {
        val p = profile.value.valueOrNull() ?: return
        val key = apiKey()
        if (generating) return
        if (key.isNullOrBlank()) { planError = NOT_SIGNED_IN; return }
        generating = true; planError = null
        viewModelScope.launch {
            try {
                proposal = withContext(io) { PlanCoach(modelFor(key), library).generate(p) }
            } catch (e: PlanRejectedException) {
                planError = "AI 两次给出的计划都不符合要求，请重试。（${e.problems.first()}）"
            } catch (e: CoachException) {
                planError = planErrorText(e)
            } finally {
                generating = false
            }
        }
    }

    fun discardProposal() { proposal = null }

    fun confirmProposal() {
        val p = proposal ?: return
        viewModelScope.launch {
            repo.savePlan(p.plan)
            proposal = null
        }
    }

    fun startWorkout(dayIndex: Int = nextDay) {
        val plan = activePlan.value.valueOrNull() ?: return
        if (workout != null || dayIndex !in plan.days.indices) return
        viewModelScope.launch { repo.startSession(plan, dayIndex) }
    }

    /** Edits one set; marking a set done starts the rest timer for that exercise. */
    fun updateSet(index: Int, set: SetLog) {
        val s = workout ?: return
        val before = s.sets.getOrNull(index) ?: return
        val updated = s.copy(sets = s.sets.toMutableList().also { it[index] = set })
        workout = updated
        if (set.done && !before.done) startRest(restFor(updated, set.exerciseId))
        viewModelScope.launch { repo.updateSets(s.id, updated.sets) }
    }

    fun skipRest() { restEndsAt = null }
    fun addRest(seconds: Int) { restEndsAt = restEndsAt?.plus(seconds * 1000L)?.also { restTotalSeconds += seconds } }

    /** Ends the workout and hands the stored session to [onFinished] (the feedback screen). */
    fun finishWorkout(onFinished: (WorkoutSession) -> Unit = {}) {
        val s = workout ?: return
        viewModelScope.launch {
            repo.updateSets(s.id, s.sets)
            val finished = repo.finishSession(s.id)
            restEndsAt = null
            finished?.let(onFinished)
            // Best effort: a failed upload is retried the next time the leaderboard opens.
            finished?.let { runCatching { upload(listOf(it)) } }
        }
    }

    fun discardWorkout() {
        val s = workout ?: return
        workout = null; restEndsAt = null
        viewModelScope.launch { repo.discardSession(s.id) }
    }

    /** Loads the session to review; the id survives process death, the object does not. */
    fun openReview(sessionId: Long) {
        if (reviewing?.id == sessionId) return
        adjustment = null; adjustError = null
        viewModelScope.launch { reviewing = repo.session(sessionId) }
    }

    fun saveFeedbackOnly(f: PostWorkoutFeedback, onDone: () -> Unit) {
        viewModelScope.launch { repo.saveFeedback(f); closeReview(); onDone() }
    }

    /** Saves the feedback, then asks the coach to adjust the whole plan from it. */
    fun requestAdjustment(f: PostWorkoutFeedback) {
        val s = reviewing ?: return
        val p = profile.value.valueOrNull() ?: return
        val plan = activePlan.value.valueOrNull() ?: return
        val key = apiKey()
        if (adjusting) return
        if (key.isNullOrBlank()) { adjustError = NOT_SIGNED_IN; return }
        adjusting = true; adjustError = null
        viewModelScope.launch {
            try {
                repo.saveFeedback(f)
                val recent = repo.recentSessions(5)
                val proposal = withContext(io) { PlanCoach(modelFor(key), library).adjust(p, plan, s, f, recent) }
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

    private fun startRest(seconds: Int) {
        restTotalSeconds = seconds
        restEndsAt = clock() + seconds * 1000L
    }

    private fun restFor(s: WorkoutSession, exerciseId: String): Int =
        activePlan.value.valueOrNull()?.takeIf { it.version == s.planVersion }
            ?.days?.getOrNull(s.dayIndex)?.items?.firstOrNull { it.exerciseId == exerciseId }?.restSeconds ?: 60

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
                    modelFor = { server.chatModel(maxTokens = 4000, readTimeoutMs = 120_000) }, server = server) as T
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
