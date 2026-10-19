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

/**
 * Blocking JSON-mode chat call through the ReadyGym server's AI proxy (OpenAI-compatible).
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
) : ChatModel {

    /** Last provider error message, for the setup screen's "test the key" diagnostic. */
    @Volatile var lastProviderMessage: String? = null
        private set

    override fun complete(messages: List<ChatMessage>): String =
        completeRaw(JSONArray(messages.map { JSONObject().put("role", it.role).put("content", it.content) }))

    fun completeRaw(messages: JSONArray): String {
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("temperature", 0.3)
            .put("max_tokens", maxTokens)
            .put("stream", false)

        val connection = (URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = readTimeoutMs
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
            return contentOf(payload)
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

    /** The ReadyGym server answers errors as {"error":{"code","message"}}; keep both. */
    private fun httpError(status: Int, payload: String): CoachException =
        ServerApi.serverError(status, payload).also { e -> e.detail?.let { lastProviderMessage = it.take(300) } }

    /** The assistant's message content, with any markdown fence removed. */
    private fun contentOf(payload: String): String {
        val choices = JSONObject(payload).optJSONArray("choices") ?: throw CoachException("provider_invalid_response")
        if (choices.length() == 0) throw CoachException("provider_invalid_response")
        val content = choices.getJSONObject(0).optJSONObject("message")?.optString("content")
        if (content.isNullOrBlank()) throw CoachException("provider_invalid_response")
        return stripFence(content)
    }

    companion object {
        const val DEFAULT_MODEL = "deepseek-chat"
        const val DEFAULT_BASE = "https://api.deepseek.com"

        /** Models sometimes wrap JSON in a markdown fence even when asked not to. */
        fun stripFence(text: String): String {
            val t = text.trim()
            if (!t.startsWith("```")) return t
            return t.substringAfter('\n', t).trimEnd().removeSuffix("```").trimEnd()
        }

        /** Plans with up to 6 days × 8 items need far more room than a chat turn. */
    }
}
