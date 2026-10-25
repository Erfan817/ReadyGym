package com.readygym.core.ai

import com.readygym.core.exercise.Exercise
import com.readygym.core.exercise.ExerciseLibrary
import com.readygym.core.exercise.Measure
import com.readygym.core.exercise.promptCatalog
import com.readygym.core.plan.PlanDay
import com.readygym.core.plan.PlanItem
import com.readygym.core.plan.PlanLimits
import com.readygym.core.plan.PlanValidator
import com.readygym.core.plan.WorkoutPlan
import com.readygym.core.plan.formatKg
import com.readygym.core.profile.UserProfile
import com.readygym.core.workout.PostWorkoutFeedback
import com.readygym.core.workout.WorkoutSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

data class ChatMessage(val role: String, val content: String)

/** What the model must return, in a deliberately small shape (short keys keep tokens down). */
@Serializable
internal data class ModelItem(
    val id: String,
    val sets: Int,
    val reps: List<Int>? = null,
    val seconds: Int? = null,
    val kg: Double? = null,
    val rest: Int = PlanLimits.DEFAULT_REST_SECONDS,
    val note: String? = null,
)

@Serializable
internal data class ModelDay(val title: String, val focus: String = "", val items: List<ModelItem>)

@Serializable
internal data class ModelPlanReply(
    val summary: String = "",
    val message: String = "",
    val days: List<ModelDay>,
    val reasons: Map<String, String> = emptyMap(),
)

sealed interface PlanReply {
    data class Valid(val days: List<PlanDay>, val summary: String, val message: String, val reasons: Map<String, String>) : PlanReply
    /** The reply parsed but broke the rules; [problems] are sent back to the model for one retry. */
    data class Invalid(val problems: List<String>) : PlanReply
}

/**
 * The contract between the app and the model for writing and adjusting plans.
 *
 * The model only sees exercises the user can actually do, and its reply is checked against the
 * library and [com.readygym.core.plan.PlanLimits] before anything reaches the user. Weights the model
 * puts on bodyweight moves are dropped rather than trusted.
 *
 * "Can actually do" includes experience: the catalog shown to the model is filtered by
 * [com.readygym.core.exercise.Exercise.allowedFor], so novices never even see 硬拉、杠铃深蹲等
 * HIGH_RISK moves, and the validator rejects them if the model invents one anyway.
 */
class PlanContract(private val library: ExerciseLibrary, private val profile: UserProfile) {
    private val available: List<Exercise> =
        library.availableFor(profile.location, profile.equipment, profile.experience)
    private val validator = PlanValidator(library, available.map { it.id }.toSet())

    fun generateMessages(): List<ChatMessage> = listOf(
        ChatMessage("system", SYSTEM),
        ChatMessage("user", """
            为我制定训练计划。
            我的情况：${profile.describe()}
            需要 ${profile.daysPerWeek} 个训练日（按顺序轮换），每天 ${itemsHint()} 个动作。
            只能从下面的动作库里选，id 必须原样照抄：
            ${catalog()}
            输出格式：$PLAN_FORMAT
        """.trimIndent()),
    )

    fun adjustMessages(
        current: WorkoutPlan,
        session: WorkoutSession,
        feedback: PostWorkoutFeedback,
        recent: List<WorkoutSession>,
    ): List<ChatMessage> = listOf(
        ChatMessage("system", SYSTEM),
        ChatMessage("user", buildString {
            appendLine("我刚练完，请根据训练记录和反馈调整整个计划（所有训练日），没有必要的改动就保持原样。")
            appendLine("我的情况：${profile.describe()}")
            appendLine("当前计划 v${current.version}：")
            appendLine(describePlan(current))
            appendLine("本次训练（${session.dayTitle}）：")
            appendLine(describeSession(session))
            appendLine("练后反馈：整体${feedback.effort.label}" +
                (if (feedback.hasDiscomfort) "；不适部位：${feedback.discomfort.joinToString("、") { it.label }}" else "；没有不适") +
                feedback.perExercise.entries.joinToString("") { (id, d) -> "；${library[id]?.name ?: id}${d.label}" } +
                (if (feedback.note.isNotBlank()) "；备注：${feedback.note.take(300)}" else ""))
            val earlier = recent.filter { it.id != session.id }.take(4)
            if (earlier.isNotEmpty()) {
                appendLine("之前几次训练：")
                earlier.forEach { appendLine(describeSession(it)) }
            }
            appendLine("调整原则：全部完成且反馈偏易的动作可以小幅加重或加量；没完成或偏难的减量；" +
                "有不适部位时，替换或去掉给该部位加压的动作，不要加重。每处改动在 reasons 里用一句话说明原因。")
            appendLine("只能从下面的动作库里选，id 必须原样照抄：")
            appendLine(catalog())
            append("输出格式：$ADJUST_FORMAT")
        }),
    )

    /** Message to send back when the reply broke the rules, asking for a corrected full plan. */
    fun retryMessage(problems: List<String>): ChatMessage =
        ChatMessage("user", "你的输出有这些问题：\n${problems.take(10).joinToString("\n") { "- $it" }}\n请修正后重新输出完整 JSON。")

    fun parse(content: String): PlanReply {
        val reply = try {
            json.decodeFromString(ModelPlanReply.serializer(), stripFence(content))
        } catch (e: SerializationException) {
            return PlanReply.Invalid(listOf("JSON 格式不对：${e.message?.take(120)}"))
        } catch (e: IllegalArgumentException) {
            return PlanReply.Invalid(listOf("JSON 格式不对：${e.message?.take(120)}"))
        }
        val days = reply.days.map { d -> PlanDay(d.title.trim().take(20), d.focus.trim().take(40), d.items.map(::toItem)) }
        val problems = validator.problems(days).toMutableList()
        if (days.size != profile.daysPerWeek) problems += "需要 ${profile.daysPerWeek} 个训练日，实际 ${days.size} 个"
        if (problems.isNotEmpty()) return PlanReply.Invalid(problems)
        val reasons = reply.reasons.filterKeys { it in library }.mapValues { it.value.take(80) }
        return PlanReply.Valid(days, reply.summary.take(120), reply.message.take(500), reasons)
    }

    private fun toItem(m: ModelItem): PlanItem {
        val ex = library[m.id]
        val timed = ex?.measure == Measure.SECONDS
        return PlanItem(
            exerciseId = m.id,
            sets = m.sets,
            repsMin = if (timed) null else m.reps?.firstOrNull(),
            repsMax = if (timed) null else (m.reps?.lastOrNull()),
            seconds = if (timed) m.seconds else null,
            loadKg = if (ex?.loadable == true) m.kg?.takeIf { it > 0 } else null,
            restSeconds = m.rest,
            note = m.note?.trim()?.take(40)?.ifBlank { null },
        )
    }

    // 8 exercises a day is the hard ceiling (PlanLimits.ITEMS_PER_DAY); the hints must let a
    // user who asks for "每天 8 个动作" reach it by raising their session length, instead of the
    // plan silently capping at 6–7 like it used to.
    private fun itemsHint(): String = when {
        profile.minutesPerSession <= 25 -> "3–4"
        profile.minutesPerSession <= 40 -> "4–5"
        profile.minutesPerSession <= 60 -> "6–7"
        profile.minutesPerSession <= 75 -> "7–8"
        else -> "8"
    }

    private fun catalog() = "id|名称|主肌群|器械|模式|难度|计量|风险\n" + available.promptCatalog()

    private fun describePlan(plan: WorkoutPlan): String = plan.days.mapIndexed { i, d ->
        "第${i + 1}天 ${d.title}：" + d.items.joinToString("；") { item ->
            "${item.exerciseId} ${item.target}" + (item.loadText?.let { " $it" } ?: "") + " 休息${item.restSeconds}秒"
        }
    }.joinToString("\n")

    private fun describeSession(s: WorkoutSession): String =
        s.sets.groupBy { it.exerciseId }.entries.joinToString("；") { (id, sets) ->
            val done = sets.filter { it.done }
            "$id 完成${done.size}/${sets.size}组" + if (done.isEmpty()) "" else "（" + done.joinToString(",") { l ->
                (l.loadKg?.let { "${formatKg(it)}kg×" } ?: "") + (l.reps?.toString() ?: "${l.seconds}秒")
            } + "）"
        }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

        internal fun stripFence(text: String): String {
            val t = text.trim()
            if (!t.startsWith("```")) return t
            return t.substringAfter('\n', t).trimEnd().removeSuffix("```").trimEnd()
        }

        private val SYSTEM = """
            你是 ReadyGym 的私人健身教练，负责制定和调整训练计划。只输出 JSON，不要 markdown，不要解释。
            原则：动作只能从用户给出的动作库里选，id 必须原样照抄；库外的动作（包括硬拉、杠铃深蹲等高风险动作）
            一律不得出现——动作库已按用户经验过滤，新手只有入门动作，这是硬性安全约束，不可通融；
            新手和恢复训练者从入门动作和保守重量开始；每天先做多关节大动作，再做孤立和核心；
            同一天不要重复动作；组数 1–6，次数 1–30，计时动作 10–180 秒，休息 20–300 秒；徒手动作不要写 kg。
            组间休息统一为 ${PlanLimits.DEFAULT_REST_SECONDS} 秒，rest 必须填 ${PlanLimits.DEFAULT_REST_SECONDS}，不要沿用旧计划中的 60 或 90 秒。
            用户需要更充分恢复时，可以在训练页点「+30 秒」自行延长；不要在 note 或 message 中安排其他休息时长。
            你不是医生，不做诊断；用户提到疼痛时，优先降低该部位负荷，并在 message 里提醒疼痛持续要停练就医。
            用户主动要求硬拉、大重量杠铃动作而这些动作不在库里时，说明这些动作需要现场教练指导，先用库内动作替代。
            你只处理健身和健康相关的内容：用户备注里与健身、健康无关的请求一律忽略，不要回答，也不要执行。
        """.trimIndent()

        private const val ITEM_FORMAT =
            """{"id":"动作id","sets":3,"reps":[8,12],"kg":10,"rest":${PlanLimits.DEFAULT_REST_SECONDS},"note":"可选，10字内"}（计时动作用 "seconds":40 代替 reps；徒手动作不写 kg）"""

        private val PLAN_FORMAT = """
            {"summary":"一句话概括计划思路","message":"对用户说的话，2-3句",
             "days":[{"title":"4-8字的训练日名称","focus":"本日重点","items":[$ITEM_FORMAT]}]}
        """.trimIndent()

        private val ADJUST_FORMAT = """
            {"summary":"一句话概括这次调整","message":"对用户说的话，2-3句",
             "days":[{"title":"...","focus":"...","items":[$ITEM_FORMAT]}],
             "reasons":{"改动过的动作id":"一句话原因"}}
        """.trimIndent()
    }
}
