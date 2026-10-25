package com.readygym.core.exercise

import com.readygym.core.Equipment
import com.readygym.core.Location
import com.readygym.core.MuscleGroup
import com.readygym.core.profile.Experience
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class ExercisePattern(val label: String) {
    SQUAT("蹲"), HINGE("髋铰链"), LUNGE("弓步/单腿"), HORIZONTAL_PUSH("水平推"), VERTICAL_PUSH("垂直推"),
    HORIZONTAL_PULL("水平拉"), VERTICAL_PULL("垂直拉"), CORE("核心"), ISOLATION("孤立"), CARDIO("有氧"),
}
enum class ExerciseLevel(val label: String) { BEGINNER("入门"), INTERMEDIATE("进阶"), ADVANCED("高阶") }
/** Whether a set is counted in repetitions or held for seconds. */
enum class Measure { REPS, SECONDS }

/**
 * Injury-risk tier, separate from [ExerciseLevel]: a movement can be technically intermediate and
 * still low risk, while barbell deadlifts are marked HIGH_RISK because a fatigued or untrained
 * user failing one loads the lumbar spine unprotected. Beginners and returning users never see
 * HIGH_RISK items; the plan validator rejects them too, so the model cannot talk its way past it.
 */
enum class ExerciseRisk(val label: String) { NORMAL("常规"), HIGH_RISK("高风险") }

@Serializable
data class Exercise(
    val id: String,
    val name: String,
    val nameEn: String,
    val primary: MuscleGroup,
    val secondary: List<MuscleGroup> = emptyList(),
    val equipment: Equipment,
    val locations: Set<Location>,
    val pattern: ExercisePattern,
    val level: ExerciseLevel,
    val measure: Measure,
    val tips: List<String>,
    val mistakes: List<String> = emptyList(),
    /** Bundled offline GIF (the original 60); takes precedence over [gifPath]. */
    val gifAsset: String? = null,
    val risk: ExerciseRisk = ExerciseRisk.NORMAL,
    /**
     * GIF path on the ReadyGym server (`/gifs/<gifPath>`), for exercises added in library v2 —
     * their media is streamed instead of bloating the APK. Null together with [gifAsset] means
     * no honest demo exists.
     */
    val gifPath: String? = null,
) {
    /** Only external load can be tracked in kg; bodyweight moves log reps or seconds. */
    val loadable: Boolean get() = equipment != Equipment.BODYWEIGHT

    /**
     * Whether this exercise may appear in this user's plan. Novices never get HIGH_RISK moves
     * (杠铃硬拉、杠铃深蹲、大重量卧推等) nor ADVANCED ones (引体向上等)；returning users have the
     * technique but a deconditioned back, so they keep every level except HIGH_RISK.
     */
    fun allowedFor(experience: Experience): Boolean = when (experience) {
        Experience.BEGINNER -> risk == ExerciseRisk.NORMAL && level != ExerciseLevel.ADVANCED
        Experience.RETURNING -> risk == ExerciseRisk.NORMAL
        Experience.EXPERIENCED -> true
    }
}

@Serializable
data class ExerciseLibrary(val version: Int, val notice: String, val exercises: List<Exercise>) {
    init {
        val dupes = exercises.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(dupes.isEmpty()) { "duplicate exercise ids: $dupes" }
    }

    private val byId = exercises.associateBy { it.id }

    operator fun get(id: String): Exercise? = byId[id]
    operator fun contains(id: String): Boolean = id in byId

    /** Exercises the user can actually do at [location] with [equipment]; bodyweight is always available. */
    fun available(location: Location, equipment: Set<Equipment>): List<Exercise> =
        exercises.filter { location in it.locations && (it.equipment == Equipment.BODYWEIGHT || it.equipment in equipment) }

    /** [available] narrowed by what [experience] is allowed to train; this is what a plan may contain. */
    fun availableFor(location: Location, equipment: Set<Equipment>, experience: Experience): List<Exercise> =
        available(location, equipment).filter { it.allowedFor(experience) }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): ExerciseLibrary = json.decodeFromString(serializer(), text)
    }
}

/** One compact line per exercise, so the model can only pick from ids it was shown. */
fun List<Exercise>.promptCatalog(): String = joinToString("\n") {
    listOf(it.id, it.name, it.primary.label, it.equipment.label, it.pattern.label, it.level.label,
        if (it.measure == Measure.SECONDS) "计时" else "计次",
        if (it.risk == ExerciseRisk.HIGH_RISK) "高风险" else "").joinToString("|")
}

val MuscleGroup.label: String get() = when (this) {
    MuscleGroup.TRAPEZIUS -> "斜方肌"; MuscleGroup.SHOULDERS -> "肩部"; MuscleGroup.CHEST -> "胸部"
    MuscleGroup.UPPER_BACK -> "背部"; MuscleGroup.SERRATUS -> "前锯肌"; MuscleGroup.BICEPS -> "肱二头肌"
    MuscleGroup.TRICEPS -> "肱三头肌"; MuscleGroup.FOREARMS -> "前臂"; MuscleGroup.ABS -> "腹肌"
    MuscleGroup.OBLIQUES -> "腹斜肌"; MuscleGroup.LOWER_BACK -> "下背"; MuscleGroup.GLUTES -> "臀部"
    MuscleGroup.QUADRICEPS -> "股四头肌"; MuscleGroup.HAMSTRINGS -> "腘绳肌"; MuscleGroup.ADDUCTORS -> "内收肌"
    MuscleGroup.HIP_FLEXORS -> "髋屈肌"; MuscleGroup.CALVES -> "小腿"; MuscleGroup.TIBIALIS -> "胫骨前肌"
}
