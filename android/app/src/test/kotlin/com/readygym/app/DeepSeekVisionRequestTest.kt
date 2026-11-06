package com.readygym.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression guard for the shape of the one vision call (AGENTS.md 4.4). The bundled model is a
 * reasoning vision model: forcing JSON mode or a tiny max_tokens makes the returned content empty.
 * All connections are injected in memory; this test never calls a real gateway.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeepSeekVisionRequestTest {

    private class Connection : HttpURLConnection(URL("https://test.invalid/v1/chat/completions")) {
        val sent = ByteArrayOutputStream()
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() = Unit
        override fun getResponseCode() = 200
        override fun getOutputStream(): ByteArrayOutputStream = sent
        override fun getInputStream() = ByteArrayInputStream(
            """{"choices":[{"finish_reason":"stop","message":{"content":"蝴蝶机"}}]}""".toByteArray(Charsets.UTF_8)
        )
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        fun body() = JSONObject(sent.toString("UTF-8"))
    }

    @Test
    fun visionUsesImageUrlWithoutJsonModeAndKeepsEnoughOfTheTokenBudget() {
        val connection = Connection()
        val api = DeepSeekApi("test-key", baseUrl = "https://test.invalid/v1/",
            openConnection = { connection })
        assertEquals("蝴蝶机", api.recognizeImage("这是什么器械？", "aGVsbG8=", "image/jpeg"))

        val body = connection.body()
        assertFalse("vision must not force json_object", body.has("response_format"))
        assertTrue("reasoning tokens need headroom", body.getInt("max_tokens") >= 2000)

        val content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        assertTrue(content.getJSONObject(1).getJSONObject("image_url").getString("url")
            .startsWith("data:image/jpeg;base64,"))
    }
}
