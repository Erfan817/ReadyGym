package com.readygym.core.exercise

import com.readygym.core.Equipment
import com.readygym.core.Location
import com.readygym.core.MuscleGroup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class ExercisePattern(val label: String) {
    SQUAT("蹲"), HINGE("髋铰链"), LUNGE("弓步/单腿"), HORIZONTAL_PUSH("水平推"), VERTICAL_PUSH("垂直推"),
    HORIZONTAL_PULL("水平拉"), VERTICAL_PULL("垂直拉"), CORE("核心"), ISOLATION("孤立"), CARDIO("有氧"),
}
enum class ExerciseLevel(val label: String) { BEGINNER("入门"), INTERMEDIATE("进阶"), ADVANCED("高阶") }
/** Whether a set is counted in repetitions or held for seconds. */
enum class Measure { REPS, SECONDS }

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
    val gifAsset: String? = null,
) {
    /** Only external load can be tracked in kg; bodyweight moves log reps or seconds. */
    val loadable: Boolean get() = equipment != Equipment.BODYWEIGHT
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

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): ExerciseLibrary = json.decodeFromString(serializer(), text)
    }
}

/** One compact line per exercise, so the model can only pick from ids it was shown. */
fun List<Exercise>.promptCatalog(): String = joinToString("\n") {
    listOf(it.id, it.name, it.primary.label, it.equipment.label, it.pattern.label, it.level.label,
        if (it.measure == Measure.SECONDS) "计时" else "计次").joinToString("|")
}

val MuscleGroup.label: String get() = when (this) {
    MuscleGroup.TRAPEZIUS -> "斜方肌"; MuscleGroup.SHOULDERS -> "肩部"; MuscleGroup.CHEST -> "胸部"
    MuscleGroup.UPPER_BACK -> "背部"; MuscleGroup.SERRATUS -> "前锯肌"; MuscleGroup.BICEPS -> "肱二头肌"
    MuscleGroup.TRICEPS -> "肱三头肌"; MuscleGroup.FOREARMS -> "前臂"; MuscleGroup.ABS -> "腹肌"
    MuscleGroup.OBLIQUES -> "腹斜肌"; MuscleGroup.LOWER_BACK -> "下背"; MuscleGroup.GLUTES -> "臀部"
    MuscleGroup.QUADRICEPS -> "股四头肌"; MuscleGroup.HAMSTRINGS -> "腘绳肌"; MuscleGroup.ADDUCTORS -> "内收肌"
    MuscleGroup.HIP_FLEXORS -> "髋屈肌"; MuscleGroup.CALVES -> "小腿"; MuscleGroup.TIBIALIS -> "胫骨前肌"
}
