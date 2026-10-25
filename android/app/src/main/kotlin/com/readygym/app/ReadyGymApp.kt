package com.readygym.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.readygym.core.TrainingExperience
import com.readygym.core.profile.Experience
import com.readygym.core.profile.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Full-screen tasks that sit above the tabs; each owns its own way out. */
private enum class Overlay { NONE, PROFILE_FORM, WORKOUT, REVIEW, RENAME, COACH, EQUIPMENT, FEEDBACK, DONATE, MUSCLE_PICKER, COMMUNITY, RECORDS }

/**
 * App shell: nickname sign-up → tabs (首页 / 计划 / 开始 / 统计 / 我的) plus full-screen tasks.
 *
 * 首页 is the coach: the conversation itself is a full-screen task ([Overlay.COACH]) launched from
 * the home card, the bottom-bar start button, or 计划's generate action. The middle tab button is
 * not a destination — it opens the workout.
 *
 * [client] lets tests inject a chat transport (and skips sign-up); production talks to the
 * ReadyGym server with the stored account token. All navigation state is saveable, and data lives
 * in Room, so rotation and process death land the user back where they were.
 */
@Composable
fun ReadyGymApp(client: CoachClient? = null) {
    val activity = LocalContext.current as ComponentActivity
    val accounts = remember(activity) { AccountStore(activity) }
    var account by remember(activity) { mutableStateOf(accounts.load()) }
    val consents = remember(activity) { AiConsentStore(activity) }
    var aiConsented by remember(activity) { mutableStateOf(consents.accepted()) }
    val server = remember(activity) { ServerApi(BuildConfig.SERVICE_BASE_URL) { accounts.load()?.token } }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var accountError by remember { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(AppTab.HOME) }
    var overlay by rememberSaveable { mutableStateOf(Overlay.NONE) }
    var reviewSessionId by rememberSaveable { mutableLongStateOf(0L) }
    var profileUpdatedForPlan by rememberSaveable { mutableStateOf(false) }
    val gym: GymViewModel = viewModel(factory = GymViewModel.factory(activity))
    val openSession by gym.openSession.collectAsState()

    // The saved profile is captured when the client is (re)built: a fresh coach conversation gets
    // seeded with it, so a returning user is not re-interviewed and no confirmation card shows.
    // Both the remember and the VM key below are keyed on the profile — saving a new one (in chat
    // or via the profile form) therefore starts the next conversation fresh with that profile.
    val savedProfile = gym.profile.value.valueOrNull()
    val actualClient = remember(client, account?.token, savedProfile) {
        client ?: account?.let { DeepSeekCoachClient(DeepSeekApi.bundled() ?: server.chatModel(), savedProfile) }
    }
    val coach = actualClient?.let { c ->
        remember(activity, c) {
            ViewModelProvider(activity, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CoachConversation(c) as T
            })[coachViewModelKey(client, c, account?.token, savedProfile), CoachConversation::class.java]
        }
    }

    /** Runs a server call for the account screens, turning failures into the screen's error text. */
    fun accountCall(block: suspend () -> Unit) {
        if (busy) return
        busy = true; accountError = null
        scope.launch {
            try { block() } catch (e: CoachException) { accountError = e.detail ?: "操作失败（${e.code}），请重试。" }
            finally { busy = false }
        }
    }

    // Silent daily update check against PGYER: feeds the "有新版本" hint on 我的, never pops up.
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        val store = PgyerUpdateStore(appContext)
        val today = java.time.LocalDate.now().toString()
        if (store.lastCheckDay() == today) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) { PgyerUpdate.check() }
        store.record(result, today)
    }

    /** Opens the workout, starting today's session first when nothing is in progress. */
    fun openWorkout(dayIndex: Int? = null) {
        val inProgress = openSession.valueOrNull()
        if (inProgress == null) gym.startWorkout(dayIndex ?: gym.nextDay)
        overlay = Overlay.WORKOUT
    }

    BackHandler(overlay != Overlay.NONE || tab != AppTab.HOME) {
        if (overlay != Overlay.NONE) overlay = Overlay.NONE else tab = AppTab.HOME
    }

    when {
        actualClient == null -> WelcomeScreen(
            onRegister = { name ->
                accountCall {
                    val created = withContext(Dispatchers.IO) { server.register(name) }
                    accounts.save(created)
                    account = created
                }
            },
            busy = busy, error = accountError,
        )
        overlay == Overlay.RENAME -> WelcomeScreen(
            onRegister = { name ->
                accountCall {
                    val saved = withContext(Dispatchers.IO) { server.rename(name) }
                    accounts.rename(saved)
                    account = account?.copy(nickname = saved)
                    overlay = Overlay.NONE
                }
            },
            busy = busy, error = accountError, initialNickname = account?.nickname.orEmpty(),
            title = "修改昵称", confirmLabel = "保存", onCancel = { overlay = Overlay.NONE; accountError = null },
        )
        overlay == Overlay.PROFILE_FORM -> {
            val profile by gym.profile.collectAsState()
            ProfileFormScreen(profile.valueOrNull(), onSave = {
                gym.saveProfile(it) {
                    profileUpdatedForPlan = true
                    overlay = Overlay.NONE; tab = AppTab.PLAN
                }
            },
                onCancel = { overlay = Overlay.NONE })
        }
        overlay == Overlay.WORKOUT -> WorkoutScreen(gym, onClose = { overlay = Overlay.NONE },
            onFinished = { reviewSessionId = it.id; overlay = Overlay.REVIEW })
        overlay == Overlay.REVIEW -> {
            LaunchedEffect(reviewSessionId) { gym.openReview(reviewSessionId) }
            ReviewScreen(gym, onDone = { overlay = Overlay.NONE; tab = AppTab.STATS })
        }
        overlay == Overlay.COACH -> {
            val c = requireNotNull(coach)
            val saved by gym.profile.collectAsState()
            val voiceInput = remember { VoiceInputController(activity) }
            androidx.compose.runtime.DisposableEffect(voiceInput) { onDispose { voiceInput.dispose() } }
            CompositionLocalProvider(LocalVoiceInput provides voiceInput) {
                CoachChatScreen(c, serviceConfigured = BuildConfig.SERVICE_BASE_URL.isNotBlank(), onConfirmed = { candidate ->
                    candidate.toUserProfile(gym.profile.value.valueOrNull())?.let {
                        gym.saveProfile(it) {
                            profileUpdatedForPlan = true
                            overlay = Overlay.NONE; tab = AppTab.PLAN
                        }
                    }
                }, savedGender = saved.valueOrNull()?.gender,
                    consented = aiConsented, onConsent = { consents.accept(); aiConsented = true },
                    onClose = { overlay = Overlay.NONE })
            }
        }
        overlay == Overlay.EQUIPMENT -> EquipmentGuideScreen(
            gym.library,
            mediaBaseUrl = BuildConfig.SERVICE_BASE_URL.ifBlank { null },
            vision = remember { DeepSeekApi.bundled() },
            onClose = { overlay = Overlay.NONE })
        overlay == Overlay.MUSCLE_PICKER -> {
            val profile by gym.profile.collectAsState()
            val plan by gym.activePlan.collectAsState()
            MuscleExerciseScreen(
                library = gym.library, profile = profile.valueOrNull(), plan = plan.valueOrNull(),
                nextDay = gym.nextDay,
                onClose = { overlay = Overlay.NONE },
                onCreateProfile = { overlay = Overlay.PROFILE_FORM },
                onOpenPlan = { overlay = Overlay.NONE; tab = AppTab.PLAN },
                onAddExercise = gym::addExerciseToPlan,
                workoutInProgress = gym.workout != null,
            )
        }
        overlay == Overlay.DONATE -> DonateScreen(onClose = { overlay = Overlay.NONE })
        overlay == Overlay.COMMUNITY -> GroupChatScreen(onClose = { overlay = Overlay.NONE })
        overlay == Overlay.RECORDS -> TrainingRecordsScreen(gym, onClose = { overlay = Overlay.NONE })
        overlay == Overlay.FEEDBACK -> FeedbackFormScreen(
            onSubmit = { content, contact, appVersion, model, os ->
                server.submitFeedback(content, contact, appVersion, model, os)
            },
            onClose = { overlay = Overlay.NONE })
        else -> ReadyGymScaffold(
            tab = tab,
            onTabChange = { tab = it },
            home = {
                CoachHomeScreen(
                    vm = gym,
                    nickname = account?.nickname,
                    consented = aiConsented,
                    serviceConfigured = BuildConfig.SERVICE_BASE_URL.isNotBlank(),
                    onOpenCoach = { overlay = Overlay.COACH },
                    onOpenPlan = { tab = AppTab.PLAN },
                    onOpenEquipment = { overlay = Overlay.EQUIPMENT },
                    coachGender = coach?.statedGender,
                    onStartTraining = { openWorkout() },
                )
            },
            plan = {
                PlanScreen(
                    gym,
                    onCreateProfile = { overlay = Overlay.PROFILE_FORM },
                    onOpenCoach = { overlay = Overlay.COACH },
                    onStartDay = { openWorkout(it) },
                    onPickMuscle = { overlay = Overlay.MUSCLE_PICKER },
                    profileUpdated = profileUpdatedForPlan,
                    onProfilePlanConfirmed = { profileUpdatedForPlan = false },
                )
            },
            stats = { StatsScreen(gym, onOpenRecords = { overlay = Overlay.RECORDS }) },
            start = {
                StartScreen(
                    gym,
                    onCreateProfile = { overlay = Overlay.COACH },
                    onOpenPlan = { tab = AppTab.PLAN },
                    onStartDay = { openWorkout(it) },
                )
            },
            me = {
                MeScreen(gym, account?.nickname,
                    onEditProfile = { overlay = Overlay.PROFILE_FORM },
                    onRename = { accountError = null; overlay = Overlay.RENAME },
                    onOpenFeedback = { overlay = Overlay.FEEDBACK },
                    onOpenGroup = { overlay = Overlay.COMMUNITY },
                    onOpenDonate = { overlay = Overlay.DONATE })
            },
        )
    }
}

/**
 * The chat collects the body and setting facts; the fields it doesn't ask about (limitations) are
 * kept from an existing profile. Null when the chat's values fall outside [UserProfile]'s bounds.
 */
internal fun CoachCandidate.toUserProfile(existing: UserProfile?): UserProfile? = runCatching {
    UserProfile(
        age = profile.age, heightCm = profile.heightCm, weightKg = profile.weightKg,
        gender = profile.gender, goal = profile.goal, location = profile.location,
        equipment = profile.equipment,
        daysPerWeek = preferences.daysPerWeek.coerceIn(1, 7),
        minutesPerSession = preferences.minutesPerSession.coerceIn(10, 180),
        experience = when (preferences.experience) {
            TrainingExperience.BEGINNER -> Experience.BEGINNER
            TrainingExperience.RETURNING -> Experience.RETURNING
            TrainingExperience.ADVANCED -> Experience.EXPERIENCED
        },
        limitations = listOf(preferences.safety.medicalRestrictions, preferences.safety.currentDiscomfort)
            .filter { it.isNotBlank() }.joinToString("；").ifBlank { existing?.limitations.orEmpty() }.take(200),
        // The chat does not ask for weekdays; keep the user's picks while they still fit.
        trainingDays = existing?.trainingDays?.takeIf { it.size == preferences.daysPerWeek.coerceIn(1, 6) }.orEmpty(),
    )
}.getOrNull()

/**
 * The ViewModel store key for the conversation. Internal so tests can reach the exact instance the
 * UI renders. Keyed by the account token so rotation keeps the conversation while a new account
 * starts a fresh one, and by the saved profile so a confirmed or edited profile starts the next
 * conversation fresh — seeded with it — instead of carrying a stale confirmed card around.
 */
internal fun coachViewModelKey(injected: CoachClient?, actual: CoachClient, token: String?, profile: Any? = null): String =
    if (injected == null) "coach-default-${token.hashCode()}-${profile.hashCode()}"
    else "coach-test-${System.identityHashCode(actual)}"
