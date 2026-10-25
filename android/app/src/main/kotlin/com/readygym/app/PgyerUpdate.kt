package com.readygym.app

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Update check through PGYER's open API (apiv2/app/check). The app compares the versionCode it
 * was built with against the versionCode of the newest build uploaded to PGYER; when newer, the
 * entry point shows a badge and "去下载" opens PGYER's download URL in the browser.
 *
 * The two keys come from the operator's PGYER console (账户设置 → API 信息 and 应用 → 安装设置)
 * and are baked in via local.properties at build time. Missing keys disable the check with a
 * clear message instead of a cryptic network error.
 */
internal object PgyerUpdate {

    data class UpdateInfo(val versionName: String, val versionCode: Long, val downloadUrl: String, val notes: String)

    sealed interface CheckResult {
        data class Newer(val info: UpdateInfo) : CheckResult
        data object UpToDate : CheckResult
        data class Failed(val why: String) : CheckResult
    }

    fun configured(): Boolean = BuildConfig.PGYER_API_KEY.isNotBlank() && BuildConfig.PGYER_APP_KEY.isNotBlank()

    /** Blocking; call off the main thread. */
    fun check(): CheckResult {
        if (!configured()) return CheckResult.Failed("还没有配置蒲公英的 Key，暂时无法检查更新")
        return try {
            val form = "_api_key=${enc(BuildConfig.PGYER_API_KEY)}" +
                "&appKey=${enc(BuildConfig.PGYER_APP_KEY)}" +
                "&buildVersionCode=${enc(BuildConfig.VERSION_CODE.toString())}"
            val connection = (URL("https://www.pgyer.com/apiv2/app/check").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            try {
                connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = ByteArrayOutputStream().also { stream?.use { s -> s.copyTo(it) } }.toString("UTF-8")
                if (status !in 200..299) return CheckResult.Failed("蒲公英返回 $status")
                parseCheckReply(text)
            } finally {
                connection.disconnect()
            }
        } catch (e: CoachException) {
            throw e
        } catch (_: Exception) {
            CheckResult.Failed("网络不通，稍后再试")
        }
    }

    /** Pure, so the reply-shape contract is unit-testable. */
    fun parseCheckReply(body: String): CheckResult {
        val root = JSONObject(body)
        if (root.optInt("code", -1) != 0) return CheckResult.Failed(root.optString("message").ifBlank { "蒲公英接口错误" })
        val data = root.optJSONObject("data") ?: return CheckResult.Failed("蒲公英没有返回版本信息")
        val remoteCode = data.optLong("buildVersionNo", -1L)
        if (remoteCode <= 0) return CheckResult.UpToDate  // no builds yet
        return if (remoteCode > BuildConfig.VERSION_CODE || data.optBoolean("buildHaveNewVersion", false)) {
            CheckResult.Newer(UpdateInfo(
                versionName = data.optString("buildVersion").ifBlank { "新版本" },
                versionCode = remoteCode,
                downloadUrl = data.optString("downloadURL"),
                notes = data.optString("buildUpdateDescription"),
            ))
        } else CheckResult.UpToDate
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
}

/**
 * Remembers the last silent check (once a day) and its result, so the 我的 entry can show the
 * "有新版本" hint without re-querying PGYER on every screen entry.
 */
internal class PgyerUpdateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("readygym.update", Context.MODE_PRIVATE)

    fun cachedNewer(): PgyerUpdate.UpdateInfo? {
        val url = prefs.getString(KEY_URL, null) ?: return null
        return PgyerUpdate.UpdateInfo(
            prefs.getString(KEY_VERSION, "") ?: "", prefs.getLong(KEY_CODE, -1), url,
            prefs.getString(KEY_NOTES, "") ?: "")
    }

    /** Called from a background check; stores "newer" for the badge and clears it when caught up. */
    fun record(result: PgyerUpdate.CheckResult, today: String) {
        prefs.edit().putString(KEY_DAY, today).apply()
        when (result) {
            is PgyerUpdate.CheckResult.Newer -> prefs.edit()
                .putString(KEY_URL, result.info.downloadUrl)
                .putString(KEY_VERSION, result.info.versionName)
                .putLong(KEY_CODE, result.info.versionCode)
                .putString(KEY_NOTES, result.info.notes)
                .apply()
            else -> prefs.edit().remove(KEY_URL).remove(KEY_VERSION).remove(KEY_CODE).remove(KEY_NOTES).apply()
        }
    }

    fun lastCheckDay(): String? = prefs.getString(KEY_DAY, null)

    private companion object {
        const val KEY_DAY = "day"; const val KEY_URL = "url"; const val KEY_VERSION = "version"
        const val KEY_CODE = "code"; const val KEY_NOTES = "notes"
    }
}
