package com.readygym.app

import com.readygym.core.ai.ChatMessage
import com.readygym.core.ai.ChatModel
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Blocking chat call through the configured OpenAI-compatible gateway (or server proxy).
 * Call it off the main thread.
 *
 * Failures become [CoachException] carrying the server's code and Chinese message, so the UI can
 * say exactly what went wrong (daily limit, expired login, AI busy) instead of a generic error.
 */
class DeepSeekApi(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val baseUrl: String = DEFAULT_BASE,
    private val maxTokens: Int = 1200,
    private val readTimeoutMs: Int = 60_000,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val retryDelay: (Long) -> Unit = { Thread.sleep(it) },
) : ChatModel {

    /** Last provider error message, for the setup screen's "test the key" diagnostic. */
    @Volatile var lastProviderMessage: String? = null
        private set

    override fun complete(messages: List<ChatMessage>): String =
        completeRaw(JSONArray(messages.map { JSONObject().put("role", it.role).put("content", it.content) }))

    fun completeRaw(messages: JSONArray, TOKENBudget: Int? = null): String {
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("temperature", 0.3)
            .put("max_TOKENs", TOKENBudget ?: maxTokens)
            .put("stream", false)
        return post(body)
    }

    /**
     * One vision turn: a text prompt plus one image (raw base64) through the same
     * OpenAI-compatible gateway. The bundled model is a reasoning vision model — its thinking
     * spends TOKENs before the answer lands, so the budget must cover both. JSON mode is not
     * forced: relays may reject it for image payloads, and the caller matches the answer itself.
     */
    fun recognizeImage(prompt: String, imageB64: String, mime: String, visionMaxTokens: Int = 2000): String {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:$mime;base64,$imageB64")))
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("temperature", 0.2)
            .put("max_TOKENs", visionMaxTokens)
            .put("stream", false)
        return post(body)
    }

    private fun post(body: JSONObject): String {
        lastProviderMessage = null
        var request = body
        // One retry covers a dropped connection or a brief gateway outage without an unbounded
        // wait. These calls only propose text; they never confirm a profile or write a plan.
        repeat(2) { attempt ->
            try {
                return postOnce(request)
            } catch (failure: TemporaryFailure) {
                if (attempt == 1 || (failure.retryAfterMs ?: 0) > 2_000) throw failure.error
                if (failure.needsMoreTokens) {
                    val previous = request.getInt("max_TOKENs")
                    if (previous >= 8_000) throw failure.error
                    request = JSONObject(request.toString()).put("max_TOKENs", (previous * 2).coerceAtMost(8_000))
                }
                try {
                    retryDelay(failure.retryAfterMs ?: 750)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw CoachException("network_error", true)
                }
            }
        }
        error("unreachable")
    }

    private fun postOnce(body: JSONObject): String {
        var openedConnection: HttpURLConnection? = null
        try {
            val connection = openConnection(URL("${baseUrl.trimEnd('/')}/chat/completions"))
                .also { openedConnection = it }.apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = readTimeoutMs
                    instanceFollowRedirects = false
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
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
            if (status !in 200..299) {
                val error = httpError(status, payload)
                if (status in setOf(408, 429, 500, 502, 503, 504) &&
                    error.code !in setOf("daily_limit", "insufficient_quota", "quota_exceeded")) {
                    val retryAfter = retryAfterMs(connection.getHeaderField("Retry-After"))
                    throw TemporaryFailure(error, retryAfterMs = retryAfter)
                }
                throw error
            }
            return contentOf(payload)
        } catch (e: CoachException) {
            throw e
        } catch (_: IOException) {
            throw TemporaryFailure(CoachException("network_error", true))
        } catch (_: JSONException) {
            throw CoachException("provider_invalid_response")
        } finally {
            openedConnection?.disconnect()
        }
    }

    private class TemporaryFailure(
        val error: CoachException,
        val retryAfterMs: Long? = null,
        val needsMoreTokens: Boolean = false,
    ) : Exception()

    private fun retryAfterMs(value: String?): Long? {
        if (value == null) return null
        value.trim().toLongOrNull()?.let { return it.coerceIn(0, 86_400) * 1_000 }
        return runCatching {
            (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() -
                System.currentTimeMillis()).coerceAtLeast(0)
        }.getOrNull()
    }

    /** The ReadyGym server answers errors as {"error":{"code","message"}}; keep both. */
    private fun httpError(status: Int, payload: String): CoachException =
        ServerApi.serverError(status, payload).also { e -> e.detail?.let { lastProviderMessage = it.take(300) } }

    /** The assistant's message content, with any markdown fence removed. */
    private fun contentOf(payload: String): String {
        val choices = JSONObject(payload).optJSONArray("choices") ?: throw CoachException("provider_invalid_response")
        if (choices.length() == 0) throw CoachException("provider_invalid_response")
        val choice = choices.getJSONObject(0)
        // A reasoning model can spend the whole budget thinking and return no answer, or a
        // truncated JSON document. Treat that separately from an invalid coach protocol.
        if (choice.optString("finish_reason") == "length") {
            throw TemporaryFailure(CoachException("provider_response_incomplete", true), needsMoreTokens = true)
        }
        val message = choice.optJSONObject("message") ?: throw CoachException("provider_invalid_response")
        val content = if (message.isNull("content")) null else message.optString("content")
        if (content.isNullOrBlank()) throw TemporaryFailure(CoachException("provider_empty_response", true))
        return stripFence(content)
    }

    companion object {
        const val DEFAULT_MODEL = "deepseek-chat"
        const val DEFAULT_BASE = "https://api.deepseek.com"

        /**
         * The bundled AI gateway, when the build carries one (local.properties readyGymAi*). The
         * ReadyGym VPS has no international egress, so the coach's chat and plan calls go straight
         * from the phone to the OpenAI-compatible gateway; with no key baked in this returns null
         * and everything flows through the server's AI proxy as before.
         */
        fun bundled(maxTokens: Int = 1500, readTimeoutMs: Int = 60_000): DeepSeekApi? {
            if (BuildConfig.AI_API_KEY.isBlank() || BuildConfig.AI_BASE_URL.isBlank()) return null
            return DeepSeekApi(BuildConfig.AI_API_KEY, BuildConfig.AI_MODEL, BuildConfig.AI_BASE_URL,
                maxTokens = maxTokens, readTimeoutMs = readTimeoutMs)
        }

        /** Models sometimes wrap JSON in a markdown fence even when asked not to. */
        fun stripFence(text: String): String {
            val t = text.trim()
            if (!t.startsWith("```")) return t
            return t.substringAfter('\n', t).trimEnd().removeSuffix("```").trimEnd()
        }

        /** Plans with up to 6 days × 8 items need far more room than a chat turn. */
    }
}
