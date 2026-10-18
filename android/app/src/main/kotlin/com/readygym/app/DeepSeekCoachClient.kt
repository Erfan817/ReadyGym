package com.readygym.app

import com.readygym.core.Equipment
import com.readygym.core.Gender
import com.readygym.core.Goal
import com.readygym.core.Location
import com.readygym.core.PlanningPreferences
import com.readygym.core.SafetyFeedback
import com.readygym.core.TrainingExperience
import com.readygym.core.TrainingProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to DeepSeek directly from the phone, with the user's own API key.
 *
 * This replaces the self-hosted backend for the single-user case: the key never leaves the device
 * except to api.deepseek.com, and there is no server to deploy. The trade-off is that the intake
 * policy now runs on the client, so the validation below is deliberately as strict as the server's
 * was — a malformed reply must not be able to produce a profile the planner would then act on.
 *
 * Safety posture, unchanged from the server version:
 *  - the model proposes a structured profile; it never writes a plan and never sees a save API;
 *  - every field is range-checked here, not trusted from prose;
 *  - "no discomfort" must be the explicit sentinel the prompt asks for, never a reassuring sentence;
 *  - a blocked status and a safety route must agree, and neither can be inferred from text.
 */
class DeepSeekCoachClient(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val baseUrl: String = DEFAULT_BASE,
) : CoachClient {

    override val supportsFoundation: Boolean = false

    private var sessionCounter = 0
    private val transcripts = mutableMapOf<String, MutableList<JSONObject>>()
    private val candidates = mutableMapOf<String, CoachCandidate>()

    override suspend fun start(): CoachReply {
        val session = newSession()
        transcripts[session.id] = mutableListOf(
            JSONObject().put("role", "system").put("content", SYSTEM_PROMPT)
        )
        // The first turn is a local greeting, not a model call: it costs nothing and it cannot fail
        // for network reasons before the user has typed anything.
        return CoachReply(session, FIRST_QUESTION, "collecting", responseSource = "local_prompt")
    }

    override suspend fun message(session: CoachSession, text: String): CoachReply {
        if (text.isBlank() || text.length > 1000) throw CoachException("invalid_input")
        val history = transcripts[session.id] ?: throw CoachException("session_not_found")
        synchronized(history) { history.add(JSONObject().put("role", "user").put("content", text)) }
        val reply = callModel(history)
        synchronized(history) { history.add(JSONObject().put("role", "assistant").put("content", reply.toString())) }
        return toCoachReply(reply, session)
    }

    override suspend fun confirm(reply: CoachReply): CoachReply {
        val candidate = reply.confirmedCandidate ?: throw CoachException("invalid_response")
        candidates[reply.session.id] = candidate
        return reply.copy(status = "ready", confirmed = true, confirmedCandidate = candidate,
            assistantText = reply.assistantText, responseSource = reply.responseSource)
    }

    override suspend fun delete(session: CoachSession) {
        transcripts.remove(session.id)
        candidates.remove(session.id)
    }

    private fun newSession(): CoachSession {
        sessionCounter += 1
        return CoachSession("local-$sessionCounter", "local")
    }

    // ---------------------------------------------------------------- model call

    private suspend fun callModel(history: List<JSONObject>): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray(history.toList()))
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("temperature", 0.3)
            .put("max_TOKENs", 1200)
            .put("stream", false)

        val connection = (URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = false
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val output = ByteArrayOutputStream()
            stream?.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > 262_144) throw CoachException("response_too_large")
                    output.write(buffer, 0, read)
                }
            }
            val payload = output.toString("UTF-8")

            if (status !in 200..299) throw httpError(status, payload)
            parseModelJson(payload)
        } catch (e: CoachException) {
            throw e
        } catch (_: IOException) {
            throw CoachException("network_error", true)
        } catch (_: JSONException) {
            throw CoachException("provider_invalid_response")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Maps the provider's own failures onto the codes the UI already knows how to explain, so a
     * wrong key reads as "wrong key" rather than as a generic network error.
     */
    private fun httpError(status: Int, payload: String): CoachException {
        val detail = try {
            JSONObject(payload).optJSONObject("error")?.optString("message").orEmpty()
        } catch (_: JSONException) {
            ""
        }
        val code = when {
            status == 401 || status == 403 -> "provider_auth_failed"
            status == 429 -> "provider_rate_limited"
            status == 402 -> "provider_quota_exhausted"
            status == 404 -> "provider_model_not_found"
            status in 500..599 -> "provider_unavailable"
            else -> "provider_error"
        }
        return CoachException(code, retryable = status in setOf(408, 429, 500, 502, 503, 504), httpStatus = status)
            .also { if (detail.isNotBlank()) lastProviderMessage = detail.take(300) }
    }

    /** Last provider message, for the setup screen's "test the key" diagnostic. */
    var lastProviderMessage: String? = null
        private set

    private fun parseModelJson(payload: String): JSONObject {
        val root = JSONObject(payload)
        val choices = root.optJSONArray("choices") ?: throw CoachException("provider_invalid_response")
        if (choices.length() == 0) throw CoachException("provider_invalid_response")
        val content = choices.getJSONObject(0).optJSONObject("message")?.optString("content")
            ?: throw CoachException("provider_invalid_response")
        if (content.isBlank()) throw CoachException("provider_invalid_response")
        return try {
            JSONObject(stripFence(content))
        } catch (_: JSONException) {
            throw CoachException("provider_invalid_response")
        }
    }

    /** Models sometimes wrap JSON in a markdown fence even when asked not to. */
    private fun stripFence(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val withoutOpening = trimmed.substringAfter('\n', trimmed).trimEnd()
        return withoutFence(withoutOpening)
    }

    private fun withoutFence(text: String): String =
        if (text.endsWith("```")) text.dropLast(3).trimEnd() else text

    // ------------------------------------------------------------ reply parsing

    private fun toCoachReply(json: JSONObject, session: CoachSession): CoachReply {
        val status = json.optString("status")
        if (status !in setOf("collecting", "ready", "blocked")) throw CoachException("provider_invalid_response")
        val message = json.optString("message").ifBlank { "请继续说说你的情况。" }.take(4000)

        if (status == "blocked") {
            val route = when (json.optString("safety_route")) {
                "stop_and_seek_care" -> CoachSafetyRoute.STOP_AND_SEEK_CARE
                "professional_review" -> CoachSafetyRoute.PROFESSIONAL_REVIEW
                "unsupported_experience" -> CoachSafetyRoute.UNSUPPORTED_EXPERIENCE
                else -> CoachSafetyRoute.PROFESSIONAL_REVIEW
            }
            return CoachReply(session, message, "blocked", safetyRoute = route, responseSource = "deepseek")
        }
        if (status == "collecting") {
            return CoachReply(session, message, "collecting", responseSource = "deepseek")
        }

        val profile = json.optJSONObject("profile") ?: throw CoachException("provider_invalid_response")
        val candidate = parseCandidate(profile)
        return CoachReply(session, message, "ready", confirmedCandidate = candidate,
            revision = 1, responseSource = "deepseek")
    }

    private fun parseCandidate(p: JSONObject): CoachCandidate {
        fun int(name: String): Int {
            val value = p.opt(name)
            val number = (value as? Number)?.toDouble() ?: throw CoachException("provider_invalid_response")
            if (!number.isFinite() || number != number.toInt().toDouble()) throw CoachException("provider_invalid_response")
            return number.toInt()
        }
        fun double(name: String): Double {
            val number = (p.opt(name) as? Number)?.toDouble() ?: throw CoachException("provider_invalid_response")
            if (!number.isFinite()) throw CoachException("provider_invalid_response")
            return number
        }
        fun text(name: String): String = p.optString(name).takeIf { it.isNotBlank() }
            ?: throw CoachException("provider_invalid_response")

        val age = int("age")
        val height = double("height_cm")
        val weight = double("weight_kg")
        val days = int("days_per_week")
        val minutes = int("minutes_per_session")
        // Same bounds the server enforced; a model that invents 12-year-olds or 500 kg is refused.
        if (age !in 18..120) throw CoachException("provider_invalid_response")
        if (height !in 100.0..250.0) throw CoachException("provider_invalid_response")
        if (weight !in 30.0..300.0) throw CoachException("provider_invalid_response")
        if (days !in listOf(2, 3, 4)) throw CoachException("provider_invalid_response")
        if (minutes !in listOf(20, 30, 45, 60)) throw CoachException("provider_invalid_response")

        val equipmentJson = p.optJSONArray("equipment") ?: throw CoachException("provider_invalid_response")
        val equipmentMap = mapOf(
            "bodyweight" to Equipment.BODYWEIGHT, "dumbbell" to Equipment.DUMBBELL,
            "barbell" to Equipment.BARBELL, "machine" to Equipment.MACHINE,
        )
        val equipment = (0 until equipmentJson.length())
            .map { equipmentMap[equipmentJson.optString(it)] ?: throw CoachException("provider_invalid_response") }
            .toSet()
        if (equipment.isEmpty() || equipment.size != equipmentJson.length()) throw CoachException("provider_invalid_response")

        // Explicit sentinel only. A friendly sentence is not a clearance.
        if (p.optString("current_discomfort") != "none") throw CoachException("provider_invalid_response")
        if (p.optString("medical_restrictions") != "none") throw CoachException("provider_invalid_response")

        val gender = when (p.optString("gender")) {
            "female" -> Gender.FEMALE
            "male" -> Gender.MALE
            "other" -> Gender.OTHER
            else -> Gender.UNDISCLOSED
        }
        val goal = when (text("goal")) {
            "muscle" -> Goal.MUSCLE
            "fat_loss" -> Goal.FAT_LOSS
            "health" -> Goal.HEALTH
            else -> throw CoachException("provider_invalid_response")
        }
        val location = when (text("location")) {
            "home" -> Location.HOME
            "gym" -> Location.GYM
            else -> throw CoachException("provider_invalid_response")
        }
        val experience = when (text("experience")) {
            "beginner" -> TrainingExperience.BEGINNER
            "returning" -> TrainingExperience.RETURNING
            // Advanced is refused by the planner itself; do not silently downgrade the user.
            "advanced" -> throw CoachException("provider_invalid_response")
            else -> throw CoachException("provider_invalid_response")
        }

        return CoachCandidate(
            TrainingProfile(age = age, heightCm = height, weightKg = weight, gender = gender,
                goal = goal, location = location, equipment = equipment),
            PlanningPreferences(days, minutes, experience, SafetyFeedback(screenCompleted = true)),
        )
    }

    companion object {
        const val DEFAULT_MODEL = "deepseek-chat"
        const val DEFAULT_BASE = "https://api.deepseek.com"

        private const val FIRST_QUESTION =
            "你好，我是你的训练教练。先简单介绍一下你自己吧——多大年纪、大概多高多重？"

        /**
         * Kept short on purpose: every TOKEN here is paid for by the user on every request, and a
         * long prompt is also a place for the model to find room to improvise.
         */
        private val SYSTEM_PROMPT = """
            你是 Ready Gym 的训练教练。你的唯一任务是通过对话收集用户的训练档案，最终输出一个 JSON。

            需要收集：
            1. 年龄（18-120）、身高 cm（100-250）、体重 kg（30-300）、性别（female/male/other/undisclosed）
            2. 目标 goal：muscle 增肌 / fat_loss 减脂 / health 保持健康
            3. 地点 location：home 家里 / gym 健身房
            4. 器械 equipment：从 bodyweight、dumbbell、barbell、machine 中多选
            5. 每周训练天数 days_per_week：只能是 2、3 或 4
            6. 每次时长 minutes_per_session：只能是 20、30、45 或 60
            7. 经验 experience：beginner 初学 / returning 恢复训练
            8. 身体情况：是否有当前不适、是否有医学训练限制

            规则：
            - 一次只问一到两个问题，像正常聊天，不要一次抛出长问卷。
            - 用户说的话不够填某个字段时，继续追问，不要猜。
            - 用户提到胸痛、头晕、急性损伤、关节疼痛、怀孕、近期手术、心血管疾病等情况时，
              立即返回 blocked，safety_route 用 stop_and_seek_care 或 professional_review，
              并建议就医或咨询专业人士。不要诊断，不要试图绕过。
            - 用户说要练到力竭、快速减重、极端饮食、使用药物时，返回 blocked + professional_review。
            - 用户提到 advanced（进阶/竞技）时返回 blocked + unsupported_experience。

            只输出 JSON，不要 markdown，不要解释。格式：

            收集中：
            {"status":"collecting","message":"你回复给用户的话"}

            信息齐了：
            {"status":"ready","message":"确认一下这些信息对吗",
             "profile":{"age":25,"height_cm":175,"weight_kg":70,"gender":"male",
               "goal":"muscle","location":"gym","equipment":["dumbbell","barbell"],
               "days_per_week":3,"minutes_per_session":60,"experience":"beginner",
               "current_discomfort":"none","medical_restrictions":"none"}}

            有风险：
            {"status":"blocked","message":"你回复给用户的话","safety_route":"stop_and_seek_care"}

            注意：current_discomfort 和 medical_restrictions 只有在用户明确表示没有时才能填 "none"，
            否则必须返回 blocked，不要填其他值。
        """.trimIndent()
    }
}
