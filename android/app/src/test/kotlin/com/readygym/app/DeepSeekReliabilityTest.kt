package com.readygym.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** All connections are injected in memory: these tests never call the bundled gateway. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeepSeekReliabilityTest {
    private val collecting = """{"status":"collecting","message":"每周想练几天？"}"""

    private fun response(content: String? = collecting, finish: String = "stop"): String = JSONObject()
        .put("choices", JSONArray().put(JSONObject().put("finish_reason", finish)
            .put("message", JSONObject().put("content", content ?: JSONObject.NULL))))
        .toString()

    private class Connection(
        private val status: Int = 200,
        private val payload: String,
        private val timeout: Boolean = false,
        private val retryAfter: String? = null,
    ) : HttpURLConnection(URL("https://test.invalid/v1/chat/completions")) {
        val sent = ByteArrayOutputStream()
        var disconnected = false
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getResponseCode() = status
        override fun getOutputStream(): ByteArrayOutputStream {
            if (timeout) throw SocketTimeoutException("simulated dropped connection")
            return sent
        }
        override fun getInputStream() = ByteArrayInputStream(payload.toByteArray(Charsets.UTF_8))
        override fun getErrorStream() = ByteArrayInputStream(payload.toByteArray(Charsets.UTF_8))
        override fun getHeaderField(name: String?) = if (name == "Retry-After") retryAfter else null
        fun body() = JSONObject(sent.toString("UTF-8"))
    }

    private class Fixture(vararg responses: Connection) {
        private val queue = responses.toMutableList()
        val opened = mutableListOf<Connection>()
        val delays = mutableListOf<Long>()
        val urls = mutableListOf<String>()
        val api = DeepSeekApi("test-key", baseUrl = "https://test.invalid/v1/", maxTokens = 1500,
            openConnection = { url ->
                urls += url.toString()
                queue.removeAt(0).also { opened += it }
            }, retryDelay = { delays += it })
        fun call() = api.completeRaw(JSONArray().put(JSONObject().put("role", "user").put("content", "你好")))
    }

    private fun expectError(code: String, call: () -> Unit): CoachException {
        try { call(); fail("expected $code") } catch (error: CoachException) {
            assertEquals(code, error.code)
            return error
        }
        error("unreachable")
    }

    @Test fun gatewayUnavailableRetriesOnceAndDisconnectsEveryAttempt() {
        val fixture = Fixture(Connection(503, "gateway busy"), Connection(payload = response()))
        assertEquals(collecting, fixture.call())
        assertEquals(2, fixture.opened.size)
        assertEquals(listOf(750L), fixture.delays)
        assertTrue(fixture.opened.all { it.disconnected })
        assertTrue(fixture.urls.all { it == "https://test.invalid/v1/chat/completions" })
    }

    @Test fun droppedConnectionRetriesOnce() {
        val fixture = Fixture(Connection(payload = "", timeout = true), Connection(payload = response()))
        assertEquals(collecting, fixture.call())
        assertEquals(2, fixture.opened.size)
        assertTrue(fixture.opened.all { it.disconnected })
    }

    @Test fun failureWhileOpeningConnectionUsesTheSameBoundedRetry() {
        var attempts = 0
        val connection = Connection(payload = response())
        val api = DeepSeekApi("test-key", baseUrl = "https://test.invalid/v1", openConnection = {
            attempts += 1
            if (attempts == 1) throw SocketTimeoutException("simulated connection setup failure")
            connection
        }, retryDelay = {})
        assertEquals(collecting, api.completeRaw(JSONArray()))
        assertEquals(2, attempts)
        assertTrue(connection.disconnected)
    }

    @Test fun interruptedRetryReturnsACoachErrorAndRetainsTheInterruptSignal() {
        val connection = Connection(503, "busy")
        val api = DeepSeekApi("test-key", baseUrl = "https://test.invalid/v1", openConnection = { connection },
            retryDelay = { throw InterruptedException("simulated interruption") })
        try {
            expectError("network_error") { api.completeRaw(JSONArray()) }
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(connection.disconnected)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun persistentOutageStopsAfterTwoAttempts() {
        val fixture = Fixture(Connection(503, "busy"), Connection(503, "busy"))
        assertTrue(expectError("http_503") { fixture.call() }.retryable)
        assertEquals(2, fixture.opened.size)
        assertEquals(1, fixture.delays.size)
    }

    @Test fun repeatedRequestTimeoutKeepsManualRetryAvailable() {
        val fixture = Fixture(Connection(408, "timeout"), Connection(408, "timeout"))
        assertTrue(expectError("http_408") { fixture.call() }.retryable)
        assertEquals(2, fixture.opened.size)
    }

    @Test fun authenticationAndDailyQuotaFailuresAreNotRetried() {
        val unauthorized = Fixture(Connection(401, "unauthorized"))
        expectError("http_401") { unauthorized.call() }
        assertEquals(1, unauthorized.opened.size)
        val quota = Fixture(Connection(429, """{"error":{"code":"daily_limit"}}"""))
        expectError("daily_limit") { quota.call() }
        assertEquals(1, quota.opened.size)
        assertTrue(quota.delays.isEmpty())
    }

    @Test fun longRetryAfterIsRespectedWithoutBlockingTheUser() {
        val fixture = Fixture(Connection(429, "busy", retryAfter = "60"))
        expectError("http_429") { fixture.call() }
        assertEquals(1, fixture.opened.size)
        assertTrue(fixture.delays.isEmpty())
    }

    @Test fun shortRetryAfterDelaysTheSingleRetry() {
        val fixture = Fixture(Connection(429, "busy", retryAfter = "1"), Connection(payload = response()))
        assertEquals(collecting, fixture.call())
        assertEquals(listOf(1000L), fixture.delays)
    }

    @Test fun retryAfterHttpDateAlsoPreventsAnEarlyRetry() {
        val date = ZonedDateTime.now().plusMinutes(1).format(DateTimeFormatter.RFC_1123_DATE_TIME)
        val fixture = Fixture(Connection(503, "busy", retryAfter = date))
        expectError("http_503") { fixture.call() }
        assertEquals(1, fixture.opened.size)
        assertTrue(fixture.delays.isEmpty())
    }

    @Test fun reasoningBudgetExhaustionRetriesWithMoreRoomBeforeReturningJson() {
        val fixture = Fixture(Connection(payload = response("{", "length")), Connection(payload = response()))
        assertEquals(collecting, fixture.call())
        assertEquals(1500, fixture.opened[0].body().getInt("max_tokens"))
        assertEquals(3000, fixture.opened[1].body().getInt("max_tokens"))
    }

    @Test fun emptyProviderContentRetriesAndThenSurfacesAnActionableFailure() {
        val fixture = Fixture(Connection(payload = response(null)), Connection(payload = response("")))
        assertTrue(expectError("provider_empty_response") { fixture.call() }.retryable)
        assertEquals(2, fixture.opened.size)
    }

    @Test fun invalidProviderEnvelopeIsRejectedWithoutRepeatingTheRequest() {
        val fixture = Fixture(Connection(payload = "not json"))
        expectError("provider_invalid_response") { fixture.call() }
        assertEquals(1, fixture.opened.size)
    }

    @Test fun failedSendAndManualRetryDoNotDuplicateConversationHistory() = runBlocking {
        val fixture = Fixture(Connection(503, "busy"), Connection(503, "busy"),
            Connection(payload = response()), Connection(payload = response()))
        val client = DeepSeekCoachClient(fixture.api)
        val session = client.start().session
        try { client.message(session, "我是新手"); fail("expected outage") } catch (_: CoachException) { }
        client.message(session, "我是新手")
        val retried = fixture.opened[2].body().getJSONArray("messages")
        assertEquals(2, retried.length())
        assertEquals("我是新手", retried.getJSONObject(1).getString("content"))
        client.message(session, "每周练三天")
        val next = fixture.opened[3].body().getJSONArray("messages")
        assertEquals(4, next.length())
        assertEquals(listOf("system", "user", "assistant", "user"),
            (0 until next.length()).map { next.getJSONObject(it).getString("role") })
    }

    @Test fun invalidCoachReplyDoesNotPolluteTheNextRequest() = runBlocking {
        val fixture = Fixture(Connection(payload = response("""{"status":"unknown"}""")),
            Connection(payload = response()))
        val client = DeepSeekCoachClient(fixture.api)
        val session = client.start().session
        try { client.message(session, "我是新手"); fail("expected rejection") } catch (error: CoachException) {
            assertEquals("provider_invalid_response", error.code)
        }
        client.message(session, "我是新手")
        val messages = fixture.opened[1].body().getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("我是新手", messages.getJSONObject(1).getString("content"))
    }
}
