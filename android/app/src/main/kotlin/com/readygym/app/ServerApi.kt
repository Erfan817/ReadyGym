package com.readygym.app

import android.content.Context
import com.readygym.core.workout.WorkoutSession
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The anonymous account: a bearer token (encrypted at rest) and the nickname shown on the leaderboard. */
internal class AccountStore(context: Context) {
    private val token = ApiKeyStore(context, slot = "account_token")
    private val prefs = context.applicationContext.getSharedPreferences("readygym.account", Context.MODE_PRIVATE)

    fun load(): Account? {
        val t = token.load() ?: return null
        return Account(t, prefs.getString("nickname", null).orEmpty())
    }

    fun save(account: Account) {
        token.save(account.token)
        prefs.edit().putString("nickname", account.nickname).apply()
    }

    fun rename(nickname: String) { prefs.edit().putString("nickname", nickname).apply() }

    fun clear() { token.clear(); prefs.edit().clear().apply() }
}

data class Account(val token: String, val nickname: String)

data class RankEntry(val rank: Int, val nickname: String, val done: Int, val planned: Int, val rate: Double, val me: Boolean)
data class Leaderboard(val weekStart: String, val entries: List<RankEntry>, val me: RankEntry?, val participants: Int)

/**
 * Blocking calls to the ReadyGym server; run them off the main thread. Failures surface as
 * [CoachException] with the server's own code and Chinese message in [CoachException.detail].
 */
class ServerApi(private val baseUrl: String, private val token: () -> String?) {

    fun register(nickname: String): Account {
        val json = call("POST", "/api/auth/register", JSONObject().put("nickname", nickname), auth = false)
        return Account(json.getString("token"), json.getString("nickname"))
    }

    fun rename(nickname: String): String =
        call("POST", "/api/me/nickname", JSONObject().put("nickname", nickname)).getString("nickname")

    /** Idempotent on the server, so uploading a session twice is harmless. */
    fun uploadWorkout(s: WorkoutSession, plannedDaysPerWeek: Int): Leaderboard = parseBoard(call("POST", "/api/workouts",
        JSONObject().put("clientSessionId", s.id).put("startedAtMillis", s.startedAtMillis)
            .put("endedAtMillis", requireNotNull(s.endedAtMillis)).put("completedSets", s.completedSets.size)
            .put("totalSets", s.sets.size).put("volumeKg", s.volumeKg).put("plannedDaysPerWeek", plannedDaysPerWeek.coerceIn(1, 7))))

    fun leaderboard(): Leaderboard = parseBoard(call("GET", "/api/leaderboard/weekly", null))

    /**
     * Anonymous by design: feedback must work before login and carries no training data — only
     * what helps the operator reproduce the problem (app version, model, OS) plus the text.
     */
    fun submitFeedback(content: String, contact: String, appVersion: String, deviceModel: String, osVersion: String) {
        call("POST", "/api/feedback", JSONObject()
            .put("content", content).put("contact", contact).put("appVersion", appVersion)
            .put("deviceModel", deviceModel).put("osVersion", osVersion), auth = false)
    }

    /** Chat completions go through the server's AI proxy; the provider key never reaches the phone. */
    fun chatModel(maxTokens: Int = 1500, readTimeoutMs: Int = 60_000): DeepSeekApi =
        DeepSeekApi(token().orEmpty(), baseUrl = "$baseUrl/api/ai", maxTokens = maxTokens, readTimeoutMs = readTimeoutMs)

    private fun parseBoard(json: JSONObject): Leaderboard {
        fun entry(o: JSONObject) = RankEntry(o.getInt("rank"), o.getString("nickname"), o.getInt("done"),
            o.getInt("planned"), o.getDouble("rate"), o.optBoolean("me"))
        val list = json.getJSONArray("entries")
        return Leaderboard(json.getString("weekStart"), (0 until list.length()).map { entry(list.getJSONObject(it)) },
            json.optJSONObject("me")?.let(::entry), json.getInt("participants"))
    }

    private fun call(method: String, path: String, body: JSONObject?, auth: Boolean = true): JSONObject {
        if (baseUrl.isBlank()) throw CoachException("server_not_configured", detail = "还没有配置服务器地址")
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            if (auth) token()?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        try {
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val out = ByteArrayOutputStream()
            stream?.use { it.copyTo(out) }
            val text = out.toString("UTF-8")
            if (status !in 200..299) throw serverError(status, text)
            // 201/204 replies (feedback submit) legitimately carry no body.
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } catch (e: CoachException) {
            throw e
        } catch (_: JSONException) {
            throw CoachException("invalid_response")
        } catch (_: IOException) {
            throw CoachException("network_error", retryable = true, detail = "连不上服务器，检查网络后重试")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun serverError(status: Int, text: String): CoachException {
            val error = runCatching { JSONObject(text).optJSONObject("error") }.getOrNull()
            return CoachException(error?.optString("code")?.ifBlank { null } ?: "http_$status",
                retryable = status == 429 || status >= 500, httpStatus = status,
                detail = error?.optString("message")?.ifBlank { null })
        }
    }
}
