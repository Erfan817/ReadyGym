package com.readygym.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Push-to-talk voice input with two engines:
 *
 * 1. On-device [SpeechRecognizer] — instant, free, no network; used when the phone ships a
 *    recognition service (most retail ROMs do).
 * 2. ReadyGym server relay (`/api/asr`) — the app records 16 kHz PCM while held and uploads the
 *    clip on release; the server holds the XunFei credentials. This is the fallback for phones
 *    without any recognition service (bare ROMs, some overseas models) and guarantees one
 *    consistent engine everywhere.
 *
 * State is a single StateFlow the composer button renders; release commits text via [onFinal].
 */
internal class VoiceInputController(private val context: Context, private val serviceBaseUrl: String = BuildConfig.SERVICE_BASE_URL) : RecognitionListener {

    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null

    sealed interface State {
        data object Idle : State
        data class Listening(val partial: String) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    var onFinal: ((String) -> Unit)? = null

    private val hasNative: Boolean get() = SpeechRecognizer.isRecognitionAvailable(appContext)

    /** RECORD_AUDIO is a runtime permission: manifest alone is not enough on API 23+. */
    fun hasMicPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    fun start() {
        if (hasNative) startNative() else startCloud()
    }

    private fun startNative() {
        if (recognizer != null) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).also { r ->
            r.setRecognitionListener(this)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            _state.value = State.Listening("")
            r.startListening(intent)
        }
    }

    private fun startCloud() {
        _state.value = State.Listening("")
        streamBroken = false
        streamReady = false
        streamId = null
        chunkQueue.clear()
        startRecording()
        // One worker: opens the relay session (hiding the handshake inside the press), then keeps
        // the queued chunks flowing so the server transcribes while the user is still talking.
        streamWorker = Thread {
            val id = try { asrPost("/api/asr/start", JSONObject(), 5_000, 5_000).optString("id") } catch (_: Exception) { null }
            if (id.isNullOrBlank()) { streamBroken = true; return@Thread }
            streamId = id
            streamReady = true
            while (true) {
                val chunk = try { chunkQueue.poll(200, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break }
                if (chunk == null) {
                    if (!recording) break
                    continue
                }
                val partial = try {
                    asrPost("/api/asr/stream",
                        JSONObject().put("id", id).put("audioB64", Base64.getEncoder().encodeToString(chunk)),
                        5_000, 5_000).optString("partial")
                } catch (_: Exception) {
                    streamBroken = true
                    break
                }
                if (partial.isNotEmpty()) _state.value = State.Listening(partial)
            }
        }.also { it.start() }
    }

    /** Release: native → stopListening (result arrives via onResults); cloud → upload + commit. */
    fun stop() {
        val r = recognizer
        if (r != null) {
            r.stopListening()
        } else {
            finishCloud()
        }
    }

    fun cancel() {
        abortStream()
        stopRecording()
        recognizer?.destroy()
        recognizer = null
        _state.value = State.Idle
    }

    fun dispose() {
        abortStream()
        stopRecording()
        recognizer?.destroy()
        recognizer = null
    }

    // ---------------------------------------------------------------- native engine

    override fun onReadyForSpeech(params: Bundle?) { _state.value = State.Listening("") }
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}

    override fun onError(error: Int) {
        recognizer?.destroy(); recognizer = null
        _state.value = State.Error(when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听清，再试一次"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "需要麦克风权限"
            else -> "识别出错（$error），请用键盘输入"
        })
    }

    override fun onResults(results: Bundle?) {
        recognizer?.destroy(); recognizer = null
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
        _state.value = State.Idle
        if (text.isNotEmpty()) onFinal?.invoke(text)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val partial = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        _state.value = State.Listening(partial)
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}

    // ---------------------------------------------------------------- cloud engine (server relay)

    @SuppressLint("MissingPermission") // RECORD_AUDIO is in the manifest; the toggle gates this path
    private fun stopRecording() {
        recording = false
        recorder?.let { try { it.stop() } catch (_: Exception) {} }
        recorder?.release()
        recorder = null
    }

    @SuppressLint("MissingPermission") // hasMicPermission() gates the recording path; checked per press
    private fun startRecording() {
        stopRecording()
        if (!hasMicPermission()) {
            _state.value = State.Error("需要麦克风权限：请在系统设置里允许 Ready Gym 使用麦克风")
            return
        }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            _state.value = State.Error("录音初始化失败，请用键盘输入"); return
        }
        // Some ROMs hand back an uninitialized recorder when the mic is held by another app or the
        // permission was revoked mid-session; check state instead of crashing on startRecording().
        val candidate = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
        if (candidate.state != AudioRecord.STATE_INITIALIZED) {
            candidate.release()
            _state.value = State.Error("麦克风被占用或不可用，请稍后再试")
            return
        }
        val buffer = ByteArray(minBuf.coerceAtLeast(4096))
        recorder = candidate
        val record = candidate
        pcmBuffer = ByteArrayOutputStream()
        try {
            record.startRecording()
        } catch (e: Exception) {
            record.release(); recorder = null
            _state.value = State.Error("麦克风启动失败，请用键盘输入")
            return
        }
        recordingThread = Thread {
            val pending = ByteArrayOutputStream()
            while (recording && record.state == AudioRecord.STATE_INITIALIZED) {
                val n = record.read(buffer, 0, buffer.size)
                if (n > 0) {
                    pcmBuffer?.write(buffer, 0, n)
                    pending.write(buffer, 0, n)
                    if (pending.size() >= 24_000) { // ~0.75 s of audio per streaming chunk
                        if (!chunkQueue.offer(pending.toByteArray())) streamBroken = true
                        pending.reset()
                    }
                }
                if ((pcmBuffer?.size() ?: 0) > 1_900_000) break // ~59 s hard cap, mirrors the server
            }
            if (pending.size() > 0) chunkQueue.offer(pending.toByteArray())
        }.also { it.start() }
        recording = true
    }

    private fun finishCloud() {
        recording = false
        val clip = pcmBuffer
        pcmBuffer = null
        recorder?.let { try { it.stop() } catch (_: Exception) {} }
        recorder?.release(); recorder = null
        val bytes = clip?.toByteArray() ?: byteArrayOf()
        val thread = recordingThread
        recordingThread = null
        thread?.join(1000)
        if (bytes.size < 3200) { // < 0.1 s: an accidental tap
            abortStream()
            _state.value = State.Error("没听清，再试一次")
            return
        }
        _state.value = State.Listening("识别中…")
        Thread {
            // Fast path: the session already holds all but the tail; only the final words remain.
            val worker = streamWorker
            val id = streamId
            streamWorker = null
            streamId = null
            if (worker != null && id != null && streamReady && !streamBroken) {
                try {
                    worker.join(8_000)
                    if (!worker.isAlive) {
                        val text = asrPost("/api/asr/finish", JSONObject().put("id", id), 5_000, 20_000).optString("text")
                        if (text.isNotEmpty()) { onFinal?.invoke(text); _state.value = State.Idle; return@Thread }
                        _state.value = State.Error("没听清，再试一次")
                        return@Thread
                    }
                } catch (_: Exception) {
                    // fall through to the one-shot upload below
                }
            }
            // Fallback: the whole clip still sits in [bytes]; the one-shot path needs no session.
            val text = try { uploadToServer(bytes) } catch (e: AsrServerMessage) {
                _state.value = State.Error(e.message ?: "语音服务暂时不可用，请用键盘输入"); return@Thread
            } catch (_: Exception) { null }
            _state.value = if (!text.isNullOrEmpty()) { onFinal?.invoke(text); State.Idle } else State.Error("语音服务暂时不可用，请用键盘输入")
        }.start()
    }

    /** Abandon a hold: stop the worker and free the server-side session (best effort). */
    private fun abortStream() {
        streamBroken = true
        chunkQueue.clear()
        val worker = streamWorker
        streamWorker = null
        worker?.interrupt()
        val id = streamId
        streamId = null
        if (id != null) Thread {
            try { asrPost("/api/asr/abort", JSONObject().put("id", id), 5_000, 5_000) } catch (_: Exception) {}
        }.start()
    }

    /** POST JSON to the relay; throws [AsrServerMessage] carrying the relay's Chinese message on failure. */
    private fun asrPost(path: String, body: JSONObject, connectMs: Int, readMs: Int): JSONObject {
        val base = serviceBaseUrl
        if (base.isBlank()) throw AsrServerMessage("语音服务暂时不可用")
        val connection = (URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectMs
            readTimeout = readMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.readText().orEmpty()
            if (status !in 200..299) throw AsrServerMessage(serverMessage(text) ?: "语音服务暂时不可用")
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun serverMessage(text: String): String? = try {
        JSONObject(text).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    /** Upload the whole clip and return the transcript; throws [AsrServerMessage] when the relay declines. */
    private fun uploadToServer(pcm: ByteArray): String {
        val reply = asrPost("/api/asr",
            JSONObject().put("audioB64", Base64.getEncoder().encodeToString(pcm)).put("seconds", pcm.size / 32_000),
            10_000, 30_000)
        return reply.optString("text")
    }

    private val chunkQueue = java.util.concurrent.ArrayBlockingQueue<ByteArray>(128)
    @Volatile private var streamWorker: Thread? = null
    @Volatile private var streamId: String? = null
    @Volatile private var streamBroken = false
    @Volatile private var streamReady = false

    private var recorder: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var pcmBuffer: ByteArrayOutputStream? = null

    /** The relay answered with a user-facing Chinese message; surface it instead of a generic one. */
    private class AsrServerMessage(message: String) : Exception(message)

    @Volatile private var recording = false

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}
