package com.readygym.core.ai

import com.readygym.core.exercise.ExerciseLibrary
import com.readygym.core.exercise.Measure
import com.readygym.core.exercise.promptCatalog
import com.readygym.core.plan.PlanItem
import com.readygym.core.plan.PlanLimits
import com.readygym.core.plan.PlanValidator
import com.readygym.core.plan.WorkoutPlan
import com.readygym.core.profile.UserProfile
import kotlinx.serialization.json.*

/** An alternative for the whole current plan. Merely reading or selecting it never writes data. */
data class ConversationPlanOption(val title: String, val detail: String, val plan: WorkoutPlan)

/** The exact profile and plan reviewed by the coach; both must still match when confirmed. */
data class ConversationPlanDraft(
    val base: WorkoutPlan,
    val profile: UserProfile,
    val options: List<ConversationPlanOption>,
)

class ConversationPlanException(val problems: List<String>) : IllegalArgumentException(problems.joinToString("；"))

/**
 * Optional plan proposals inside the existing collecting/ready/blocked chat protocol.
 * Models may replace several exercises across the week or return an adjusted complete week. The
 * same eligibility and prescription limits apply to both representations and again at save time.
 */
class ConversationPlanContract(
    private val library: ExerciseLibrary,
    private val profile: UserProfile,
    private val current: WorkoutPlan,
) {
    private val available = library.availableFor(profile.location, profile.equipment, profile.experience)
    private val validator = PlanValidator(library, available.map { it.id }.toSet())
    private val existingProblems = validator.problems(current.days)

    fun prompt(): String = buildString {
        appendLine("\n聊天调整当前计划：用户可以换动作、反馈缺器械、修改组次/重量或调整整周动作安排。")
        appendLine("当前档案：${profile.describe()}")
        appendLine("下面是当前整周计划的权威版本 v${current.version}，训练日下标从0开始：")
        appendLine(currentAsModelDays().toString())
        if (existingProblems.isNotEmpty()) {
            appendLine("当前旧计划存在以下不适配项；历史原文保持不动，但你给出的每套新方案都必须一并修复，不能因用户说‘其他不变’而保留：")
            existingProblems.forEach { appendLine("- $it") }
            appendLine("不在可用动作库的旧动作必须在每套replacements中另加替换（包括其他训练日），或在完整days中换成可用动作；不能仅修用户点名的动作。")
            appendLine("旧计划徒手动作上不适用的kg会由App在新方案中去掉，不需把该动作换掉。所有额外修复都要在detail向用户说明，仍要等用户确认才保存。")
        }
        appendLine("只能使用下面可用动作；不可使用库外动作、缺少器械的动作或被经验规则排除的高风险动作：")
        appendLine("id|名称|主肌群|器械|模式|难度|计量|风险")
        appendLine(available.promptCatalog())
        appendLine("用户说没有某台具体器械时，不能仅因档案有固定器械就假定那台机器存在。优先同肌群且用户确认有条件的动作；不清楚就先追问。")
        appendLine("徒手只是负重方式，不等于没有辅助器械：反向划船仍需稳固低杠，引体需单杠，凳上动作需凳。每套detail须说明所需器械，标题必须与这些条件一致；需要杆、凳或机器时，不得写‘完全无器械’或‘只需地面’。未确认有辅助器械的方案必须明确条件，先让用户确认。")
        appendLine("有明确改计划意图时给1至3个可选方案，而不是声称不能换动作、编造界面入口或要求重新建档。")
        appendLine("用户不想做某动作时，不要所有方案都只换成名字相近的变式；优先至少给一套不同动作模式、条件可行的替代，并说明主要肌群和训练侧重点的差别。")
        appendLine("确认前只能说这是方案、等待选择与确认，不能说已经保存或已经调整生效。普通健身问答不附plan_options。")
        appendLine("安全规则继续优先：疼痛/身体风险用blocked，不附plan_options；不要以普通换动作绕过安全路线。")
        appendLine("仍只用collecting/ready/blocked三种status。只有collecting可附plan_options；不得同时附档案确认卡。")
        appendLine("换动作优先紧凑格式，一套方案可替换整周多个动作：")
        appendLine("""{"status":"collecting","message":"可以选下面的替代方案，确认后再保存。","plan_options":[{"title":"方案名称","detail":"适合原因及器械要求","replacements":[{"day":0,"from":"当前动作id","to":"替代动作id"}]}]}""")
        appendLine("每处from必须是该训练日现有动作id，to必须是可用库id；同日不可重复。不同动作不会继承旧重量，训练时从轻重量开始。")
        appendLine("需要修改组次/重量或整周结构时改用完整days，每项方案的days和replacements只能二选一：")
        appendLine("""{"status":"collecting","message":"选择后可预览整周变化。","plan_options":[{"title":"整周方案","detail":"调整说明","days":[{"title":"训练日名称","focus":"训练重点","items":[{"id":"动作id","sets":2,"reps":[8,12]},{"id":"计时动作id","sets":2,"seconds":30}]}]}]}""")
        appendLine("days必须完整包含原来的${current.days.size}个训练日，不能只给改过的一天；保留未提及部分，不擅自改变训练日先后含义。")
        appendLine("每天${PlanLimits.ITEMS_PER_DAY.first}至${PlanLimits.ITEMS_PER_DAY.last}个动作；组数${PlanLimits.SETS}、次数${PlanLimits.REPS}、计时${PlanLimits.SECONDS}秒，重量须有限且0至${PlanLimits.MAX_LOAD_KG}kg。")
        appendLine("计时动作使用seconds，不用reps；徒手动作不填kg。新换入的动作不继承旧动作重量。App没有组间休息功能，不推荐倒计时或延长/跳过休息。")
        appendLine("本协议不改每周训练天数；如果用户要改天数/身体资料，先用原ready档案卡确认，随后再生成匹配的新计划。")
    }

    /** Missing plan_options means an ordinary chat answer; malformed options reject the whole turn. */
    fun parseOptions(jsonText: String): List<ConversationPlanOption> {
        if (jsonText.length > 262_144) fail("计划回复太长，请缩小调整范围后重试。")
        val root = try {
            json.parseToJsonElement(PlanContract.stripFence(jsonText)) as? JsonObject
                ?: fail("计划回复不是有效对象。")
        } catch (e: ConversationPlanException) {
            throw e
        } catch (_: IllegalArgumentException) {
            fail("计划回复格式不正确，请重试。")
        }
        if ("plan_options" !in root) return emptyList()
        if ((root["status"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull != "collecting") {
            fail("计划选项只能在聊天建议中提供。")
        }
        if (root["profile"] != null && root["profile"] != JsonNull) fail("请先确认档案，再调整计划。")
        val options = root["plan_options"] as? JsonArray ?: fail("计划选项格式不正确。")
        if (options.size !in 1..3) fail("请提供1至3个计划方案。")
        val parsed = options.mapIndexed { index, element ->
            val option = element as? JsonObject ?: fail("第${index + 1}个方案格式不正确。")
            val title = text(option, "title").take(60)
            val detail = (option["detail"]?.let { string(it, "方案说明") } ?: "").trim().take(500)
            val hasDays = "days" in option
            val hasReplacements = "replacements" in option
            if (hasDays == hasReplacements) fail("每个方案必须只提供完整计划或动作替换其中一种。")
            val plan = if (hasDays) fullPlan(option) else replacePlan(option)
            val problems = validate(plan)
            if (problems.isNotEmpty()) throw ConversationPlanException(problems)
            val clearedLoads = current.days.mapIndexed { dayIndex, day ->
                day.items.filter { before ->
                    before.loadKg != null && library[before.exerciseId]?.loadable == false &&
                        plan.days[dayIndex].items.any { it.exerciseId == before.exerciseId && it.loadKg == null }
                }.map { library[it.exerciseId]?.name ?: it.exerciseId }
            }.flatten().distinct()
            val explainedDetail = if (clearedLoads.isEmpty()) detail else
                "$detail 此方案也会去掉旧计划中${clearedLoads.joinToString("、")}不适用的负重设置，确认后一起保存。".trim()
            ConversationPlanOption(title, explainedDetail, plan.copy(reason = "聊天调整：$title"))
        }
        if (parsed.map { it.plan.days }.distinct().size != parsed.size) fail("可选方案重复，请给出不同的替代安排。")
        return parsed
    }

    /** Public so the repository can repeat validation at the transactional write boundary. */
    fun validate(plan: WorkoutPlan): List<String> = buildList {
        if (plan.days.size != current.days.size || plan.daysPerWeek != current.daysPerWeek) {
            add("训练日数量刚刚改变，请先确认档案并重新生成计划。")
        }
        if (plan.version != current.version) add("计划版本已变化，请重新获取调整方案。")
        if (plan.progressionBaseVersion != (current.progressionBaseVersion ?: current.version)) {
            add("训练顺序不能在换动作时重置。")
        }
        addAll(validator.problems(plan.days))
        plan.days.forEach { day -> day.items.forEach { item ->
            if (item.loadKg?.isFinite() == false) add("${library[item.exerciseId]?.name ?: item.exerciseId} 重量必须是有效数值。")
        } }
        if (plan.withDefaultRest().days == current.withDefaultRest().days) add("这个方案没有实际调整，请重新选择。")
    }

    private fun fullPlan(option: JsonObject): WorkoutPlan {
        val days = option["days"] as? JsonArray ?: fail("完整计划缺少训练日。")
        if (days.size != current.days.size) fail("需要保留${current.days.size}个训练日。")
        // PlanContract intentionally sanitizes irrelevant fields; reject invalid raw numeric loads
        // here so NaN, Infinity or a negative load cannot disappear during that sanitization.
        days.forEach { dayElement ->
            val day = dayElement as? JsonObject ?: fail("训练日格式不正确。")
            val items = day["items"] as? JsonArray ?: fail("训练日缺少动作。")
            items.forEach { itemElement ->
                val item = itemElement as? JsonObject ?: fail("动作格式不正确。")
                item["kg"]?.takeUnless { it == JsonNull }?.let {
                    val value = it as? JsonPrimitive ?: fail("动作重量格式不正确。")
                    val load = value.takeUnless { p -> p.isString }?.doubleOrNull
                    if (load == null || !load.isFinite() || load !in 0.0..PlanLimits.MAX_LOAD_KG) fail("动作重量超出范围或不是有效数值。")
                }
            }
        }
        val matchingProfile = if (profile.daysPerWeek == current.days.size) profile else
            profile.copy(daysPerWeek = current.days.size, trainingDays = emptySet())
        val result = PlanContract(library, matchingProfile).parse(buildJsonObject {
            put("days", days)
            put("summary", current.summary)
        }.toString())
        val valid = result as? PlanReply.Valid ?: throw ConversationPlanException((result as PlanReply.Invalid).problems)
        val safeDays = valid.days.mapIndexed { index, day ->
            val existingIds = current.days[index].items.map { it.exerciseId }.toSet()
            day.copy(items = day.items.map { item -> if (item.exerciseId !in existingIds) item.copy(loadKg = null) else item })
        }
        return current.copy(days = safeDays, progressionBaseVersion = current.progressionBaseVersion ?: current.version).withDefaultRest()
    }

    private fun replacePlan(option: JsonObject): WorkoutPlan {
        val replacements = option["replacements"] as? JsonArray ?: fail("动作替换格式不正确。")
        if (replacements.size !in 1..56) fail("请至少选择一个需要替换的动作。")
        val changes = linkedMapOf<Pair<Int, String>, String>()
        replacements.forEach { element ->
            val replacement = element as? JsonObject ?: fail("动作替换格式不正确。")
            val dayField = replacement["day"] as? JsonPrimitive ?: fail("请明确要调整哪个训练日。")
            val dayIndex = dayField.takeUnless { it.isString }?.intOrNull ?: fail("训练日下标必须是整数。")
            val day = current.days.getOrNull(dayIndex) ?: fail("要调整的训练日已不存在。")
            val from = text(replacement, "from")
            val to = text(replacement, "to")
            if (day.items.none { it.exerciseId == from }) fail("要替换的动作不在该训练日中。")
            if (from == to) fail("替代动作与原动作相同。")
            if (changes.put(dayIndex to from, to) != null) fail("同一个动作不能在一套方案里重复替换。")
            if (available.none { it.id == to }) fail("替代动作不适合当前器械、场地或训练经验。")
        }
        val updated = current.days.mapIndexed { dayIndex, day -> day.copy(items = day.items.map { item ->
            val replacementId = changes[dayIndex to item.exerciseId]
            when {
                replacementId != null -> replacementItem(item, replacementId)
                library[item.exerciseId]?.loadable == false -> item.copy(loadKg = null)
                else -> item
            }
        }) }
        return current.copy(days = updated, progressionBaseVersion = current.progressionBaseVersion ?: current.version).withDefaultRest()
    }

    private fun replacementItem(before: PlanItem, replacementId: String): PlanItem {
        val target = library[replacementId] ?: fail("替代动作不在动作库中。")
        val timed = target.measure == Measure.SECONDS
        return PlanItem(
            exerciseId = replacementId,
            sets = before.sets,
            repsMin = if (timed) null else before.repsMin ?: 8,
            repsMax = if (timed) null else before.repsMax ?: before.repsMin ?: 12,
            seconds = if (timed) before.seconds ?: 20 else null,
            loadKg = null,
        )
    }

    private fun currentAsModelDays(): JsonArray = buildJsonArray {
        current.days.forEachIndexed { index, day -> add(buildJsonObject {
            put("day", index); put("title", day.title); put("focus", day.focus)
            putJsonArray("items") { day.items.forEach { item -> add(buildJsonObject {
                put("id", item.exerciseId); put("name", library[item.exerciseId]?.name ?: item.exerciseId)
                put("sets", item.sets)
                if (item.seconds != null) put("seconds", item.seconds) else putJsonArray("reps") {
                    item.repsMin?.let { add(it) }; item.repsMax?.takeIf { it != item.repsMin }?.let { add(it) }
                }
                item.loadKg?.let { put("kg", it) }
            }) } }
        }) }
    }

    private fun text(objectValue: JsonObject, key: String): String =
        objectValue[key]?.let { string(it, key) }?.trim()?.takeIf { it.isNotBlank() }
            ?: fail("方案缺少$key。")

    private fun string(element: JsonElement, label: String): String =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail("$label 必须是文字。")

    private fun fail(message: String): Nothing = throw ConversationPlanException(listOf(message))

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}
