package com.readygym.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
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
private enum class Overlay { NONE, PROFILE_FORM, WORKOUT, REVIEW, RENAME }

/**
 * App shell: nickname sign-up → tabs (教练 / 训练 / 记录 / 我的) plus full-screen tasks.
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
    var tab by rememberSaveable { mutableStateOf(AppTab.TRAIN) }
    var overlay by rememberSaveable { mutableStateOf(Overlay.NONE) }
    var reviewSessionId by rememberSaveable { mutableLongStateOf(0L) }
    val gym: GymViewModel = viewModel(factory = GymViewModel.factory(activity))

    val actualClient = remember(client, account?.token) {
        client ?: account?.let { DeepSeekCoachClient(server.chatModel()) }
    }
    val coach = actualClient?.let { c ->
        remember(activity, c) {
            ViewModelProvider(activity, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CoachConversation(c) as T
            })[coachViewModelKey(client, c, account?.token), CoachConversation::class.java]
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

    BackHandler(overlay == Overlay.PROFILE_FORM || overlay == Overlay.RENAME || (overlay == Overlay.NONE && tab != AppTab.TRAIN)) {
        if (overlay != Overlay.NONE) overlay = Overlay.NONE else tab = AppTab.TRAIN
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
            ProfileFormScreen(profile.valueOrNull(), onSave = { gym.saveProfile(it); overlay = Overlay.NONE },
                onCancel = { overlay = Overlay.NONE })
        }
        overlay == Overlay.WORKOUT -> WorkoutScreen(gym, onClose = { overlay = Overlay.NONE },
            onFinished = { reviewSessionId = it.id; overlay = Overlay.REVIEW })
        overlay == Overlay.REVIEW -> {
            LaunchedEffect(reviewSessionId) { gym.openReview(reviewSessionId) }
            ReviewScreen(gym, onDone = { overlay = Overlay.NONE; tab = AppTab.TRAIN })
        }
        else -> ReadyGymScaffold(
            tab = tab,
            onTabChange = { tab = it },
            coach = {
                val c = requireNotNull(coach)
                val saved by gym.profile.collectAsState()
                CoachChatScreen(c, serviceConfigured = true, onConfirmed = { candidate ->
                    candidate.toUserProfile(gym.profile.value.valueOrNull())?.let(gym::saveProfile)
                    tab = AppTab.TRAIN
                }, savedGender = saved.valueOrNull()?.gender,
                    consented = aiConsented, onConsent = { consents.accept(); aiConsented = true })
            },
            train = {
                TrainHome(gym,
                    onCreateProfile = { overlay = Overlay.PROFILE_FORM },
                    onOpenCoach = { tab = AppTab.COACH },
                    onOpenWorkout = { overlay = Overlay.WORKOUT })
            },
            records = { RecordsScreen(gym) },
            me = {
                MeScreen(gym, account?.nickname,
                    onEditProfile = { overlay = Overlay.PROFILE_FORM },
                    onRename = { accountError = null; overlay = Overlay.RENAME })
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
        daysPerWeek = preferences.daysPerWeek.coerceIn(1, 6),
        minutesPerSession = preferences.minutesPerSession.coerceIn(15, 120),
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
 * starts a fresh one.
 */
internal fun coachViewModelKey(injected: CoachClient?, actual: CoachClient, token: String?): String =
    if (injected == null) "coach-default-${token.hashCode()}"
    else "coach-test-${System.identityHashCode(actual)}"
