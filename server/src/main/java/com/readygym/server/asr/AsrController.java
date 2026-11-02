package com.readygym.server.asr;

import com.readygym.server.common.ApiException;
import com.readygym.server.common.ReadyGymProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.Map;

/**
 * Push-to-talk ASR relay: the app records a short PCM clip and posts it base64; the server holds
 * the XunFei credentials and returns plain text. Anonymous like feedback — a login wall would
 * push people away from reporting things, and the same holds for talking to the coach.
 *
 * Abuse surface is bounded by the 60 s clip cap and XunFei's own QPS/quota; the operator pays per
 * use, so keep the free-tier limit in mind before public distribution.
 */
@RestController
@RequestMapping("/api/asr")
public class AsrController {
    private final XfAsrClient client;

    public AsrController(XfAsrClient client) {
        this.client = client;
    }

    // 60 s: matches the app's ~59 s recording cap and the byte cap below. A tighter cap here
    // silently 400s long holds on the phone as a generic "语音服务暂时不可用" (an 8 s cap did).
    public record AudioUpload(@NotNull String audioB64, @Max(60) int seconds) {}

    public record StreamChunk(@NotNull String id, @NotNull String audioB64) {}

    public record StreamFinish(@NotNull String id) {}

    @PostMapping
    public Map<String, String> transcribe(@RequestBody @Valid AudioUpload body) {
        byte[] pcm;
        try {
            pcm = Base64.getDecoder().decode(body.audioB64());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_audio", "音频数据无效");
        }
        // 16 kHz * 2 bytes = 32 KB/s; 60 s ≈ 1.9 MB raw, 2.5 MB base64.
        if (pcm.length > 2_000_000) throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_audio", "录音太长，请分次说");
        return Map.of("text", client.recognize(pcm).text());
    }

    // Streaming relay (the fast path): chunks arrive while the button is still held, so XunFei is
    // transcribing during the hold and release only waits for the tail. A chunk failure surfaces
    // to the app, which falls back to the one-shot endpoint above.

    @PostMapping("/start")
    public Map<String, String> start() {
        return Map.of("id", client.startStream());
    }

    @PostMapping("/stream")
    public Map<String, String> stream(@RequestBody @Valid StreamChunk body) {
        byte[] pcm;
        try {
            pcm = Base64.getDecoder().decode(body.audioB64());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_audio", "音频数据无效");
        }
        // ~0.75 s of audio per chunk from the app; a much bigger body is abuse, not speech.
        if (pcm.length == 0 || pcm.length > 64_000) throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_audio", "音频数据无效");
        return Map.of("partial", client.appendStream(body.id(), pcm));
    }

    @PostMapping("/finish")
    public Map<String, String> finish(@RequestBody @Valid StreamFinish body) {
        return Map.of("text", client.finishStream(body.id()));
    }

    /** Best effort: frees the XunFei connection when the user abandons a hold. */
    @PostMapping("/abort")
    public Map<String, String> abort(@RequestBody @Valid StreamFinish body) {
        client.abortStream(body.id());
        return Map.of("ok", "1");
    }
}
