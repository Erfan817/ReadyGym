package com.readygym.server.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.readygym.server.common.ApiException;
import com.readygym.server.common.ReadyGymProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * XunFei 语音听写 (streaming IAT) via its WebSocket API, called server-side so the operator's
 * credentials never reach the app.
 *
 * Protocol (https://www.xfyun.cn/doc/asr/voicedictation/API.html): authorize the wss handshake
 * with an HMAC-SHA256 signature over "host/date/request-line", send frames of base64 16 kHz
 * mono PCM (status 0 = first frame carrying common/business, 1 = middle, 2 = last), and collect
 * `data.result.ws[].cw[].w` until the server marks status 2. A hold-to-talk clip is one burst.
 */
@Component
public class XfAsrClient {
    private final ReadyGymProperties props;
    private final ObjectMapper json = new ObjectMapper();

    public XfAsrClient(ReadyGymProperties props) {
        this.props = props;
    }

    public record Result(String text) {}

    public Result recognize(byte[] pcm16k) {
        ReadyGymProperties.Xfyun asr = requireConfigured();
        if (pcm16k.length == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_audio", "没有录到声音");
        if (pcm16k.length > 1_900_000) throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_audio", "录音太长");
        try (WsClient ws = new WsClient(URI.create(authUrl(asr.apiKey(), asr.apiSecret())))) {
            StringBuilder text = new StringBuilder();
            int[] err = {0};
            boolean[] last = {false};
            sendFirst(ws, asr, Arrays.copyOf(pcm16k, Math.min(pcm16k.length, 10_000)));
            for (int off = 10_000; off < pcm16k.length; off += 10_000) {
                int len = Math.min(10_000, pcm16k.length - off);
                sendAudio(ws, Arrays.copyOfRange(pcm16k, off, off + len), off + len >= pcm16k.length);
            }
            if (pcm16k.length <= 10_000) ws.send(lastFrame());
            ws.drain(collectResults(text, err, last), 15);
            if (err[0] != 0) throw upstream(err[0]);
            return new Result(text.toString().trim());
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "asr_unreachable", "语音识别服务连接失败");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "interrupted", "请求被中断");
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "asr_upstream", "语音识别服务异常");
        }
    }

    // ------------------------------------------------------------------ streaming relay
    //
    // The app records while the button is held and POSTs chunks as they come; the session forwards
    // them to XunFei immediately so the engine is transcribing while the user is still talking.
    // Release then only pays the tail: a final empty frame and XunFei's last words. Sessions are
    // capability-keyed (the UUID is the secret), expire by inactivity, and are bounded — the
    // operator pays per XunFei connection, so this must not be free to farm.

    private final Map<String, StreamSession> sessions = new ConcurrentHashMap<>();
    private static final int MAX_SESSIONS = 8;

    /** Opens the XunFei connection up front so its handshake cost hides inside the press. */
    public String startStream() {
        ReadyGymProperties.Xfyun asr = requireConfigured();
        sessions.values().removeIf(s -> {
            boolean stale = s.idleMillis() > 90_000;
            if (stale) s.close();
            return stale;
        });
        if (sessions.size() >= MAX_SESSIONS) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "asr_busy", "语音服务繁忙，请稍后再试");
        }
        String id = UUID.randomUUID().toString();
        try {
            sessions.put(id, new StreamSession(asr));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "asr_unreachable", "语音识别服务连接失败");
        }
        return id;
    }

    /** Forwards one chunk and returns what the engine has heard so far (may be empty). */
    public String appendStream(String id, byte[] pcm) {
        StreamSession s = session(id);
        try {
            return s.append(pcm);
        } catch (ApiException e) {
            sessions.remove(id);
            s.close();
            throw e;
        } catch (Exception e) {
            sessions.remove(id);
            s.close();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "asr_unreachable", "语音识别服务连接失败");
        }
    }

    /** Sends the final frame and waits (bounded) for XunFei's last words. */
    public String finishStream(String id) {
        StreamSession s = session(id);
        try {
            return s.finish(6_000);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "asr_upstream", "语音识别服务异常");
        } finally {
            sessions.remove(id);
            s.close();
        }
    }

    public void abortStream(String id) {
        StreamSession s = sessions.remove(id);
        if (s != null) s.close();
    }

    private StreamSession session(String id) {
        if (id == null || id.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", "请求格式不正确");
        StreamSession s = sessions.get(id);
        if (s == null) throw new ApiException(HttpStatus.NOT_FOUND, "asr_session", "会话已失效，请重新按住说话");
        s.touch();
        return s;
    }

    private ReadyGymProperties.Xfyun requireConfigured() {
        ReadyGymProperties.Xfyun asr = props.xfyun();
        if (asr == null || asr.appId() == null || asr.appId().isBlank()
            || asr.apiKey() == null || asr.apiKey().isBlank() || asr.apiSecret() == null || asr.apiSecret().isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "asr_not_configured", "语音识别还没有配置");
        }
        return asr;
    }

    private void sendFirst(WsClient ws, ReadyGymProperties.Xfyun asr, byte[] audio) throws Exception {
        ws.send(json.writeValueAsString(Map.of(
            "common", Map.of("app_id", asr.appId()),
            "business", Map.of("language", "zh_cn", "domain", "iat", "accent", "mandarin", "vad_eos", 10_000),
            "data", Map.of("status", 0, "format", "audio/L16;rate=16000", "encoding", "raw",
                "audio", Base64.getEncoder().encodeToString(audio)))));
    }

    private void sendAudio(WsClient ws, byte[] audio, boolean last) throws Exception {
        ws.send(json.writeValueAsString(Map.of("data", Map.of(
            "status", last ? 2 : 1, "format", "audio/L16;rate=16000", "encoding", "raw",
            "audio", Base64.getEncoder().encodeToString(audio)))));
    }

    private String lastFrame() throws Exception {
        return json.writeValueAsString(Map.of("data", Map.of(
            "status", 2, "format", "audio/L16;rate=16000", "encoding", "raw", "audio", "")));
    }

    private ApiException upstream(int code) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "asr_upstream",
            "语音识别失败（" + code + "）：请在讯飞控制台确认该应用已添加语音听写服务");
    }

    /**
     * One result frame: appends the heard words, records an upstream error code, and flags
     * XunFei's terminal status=2. Shared by the one-shot and the streaming session reader.
     */
    private Consumer<String> collectResults(StringBuilder text, int[] err, boolean[] last) {
        return line -> {
            JsonNode root;
            try {
                root = json.readTree(line);
            } catch (Exception e) {
                return;
            }
            int code = root.path("code").asInt(0);
            if (code != 0) {
                err[0] = code;
                return;
            }
            for (JsonNode w : root.path("data").path("result").path("ws"))
                for (JsonNode cw : w.path("cw")) text.append(cw.path("w").asText());
            if (root.path("data").path("status").asInt(0) == 2) last[0] = true;
        };
    }

    private final class StreamSession implements AutoCloseable {
        private final WsClient ws;
        private final ReadyGymProperties.Xfyun asr;
        private final StringBuilder text = new StringBuilder();
        private final int[] err = {0};
        private final boolean[] last = {false};
        private boolean first = true;
        private volatile long lastTouch = System.currentTimeMillis();
        private final Thread reader;

        StreamSession(ReadyGymProperties.Xfyun asr) throws Exception {
            this.asr = asr;
            this.ws = new WsClient(URI.create(authUrl(asr.apiKey(), asr.apiSecret())));
            this.reader = new Thread(() -> {
                try {
                    ws.drain(collectResults(text, err, last), 120);
                } catch (Exception ignored) {
                    // A dead connection surfaces on the next append()/finish() as a plain failure.
                }
            }, "asr-stream-reader");
            this.reader.setDaemon(true);
            this.reader.start();
        }

        synchronized String append(byte[] pcm) throws Exception {
            if (err[0] != 0) throw upstream(err[0]);
            if (first) {
                sendFirst(ws, asr, pcm);
                first = false;
            } else {
                sendAudio(ws, pcm, false);
            }
            return text.toString().trim();
        }

        synchronized String finish(long waitMillis) throws Exception {
            ws.send(lastFrame());
            long deadline = System.currentTimeMillis() + waitMillis;
            while (System.currentTimeMillis() < deadline && !last[0] && reader.isAlive() && err[0] == 0) {
                Thread.sleep(50);
            }
            if (err[0] != 0) throw upstream(err[0]);
            return text.toString().trim();
        }

        void touch() { lastTouch = System.currentTimeMillis(); }
        long idleMillis() { return System.currentTimeMillis() - lastTouch; }

        @Override public void close() {
            try {
                ws.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Minimal blocking RFC6455 client: one clip per connection, masked client frames, TLS. */
    static final class WsClient implements AutoCloseable {
        private final javax.net.ssl.SSLSocket socket;
        private final java.io.InputStream in;
        private final java.io.OutputStream out;

        WsClient(URI uri) throws Exception {
            var factory = (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
            socket = (javax.net.ssl.SSLSocket) factory.createSocket(uri.getHost(), 443);
            // A stalled XunFei connection must not hang the reader forever; callers bound their
            // own waits below this.
            socket.setSoTimeout(130_000);
            socket.startHandshake();
            out = socket.getOutputStream();
            in = socket.getInputStream();
            String key = Base64.getEncoder().encodeToString(("rg" + System.nanoTime()).getBytes(StandardCharsets.US_ASCII));
            String req = "GET " + uri.getRawPath() + "?" + uri.getRawQuery() + " HTTP/1.1\r\n"
                + "Host: " + uri.getHost() + "\r\n"
                + "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            StringBuilder head = new StringBuilder();
            int c;
            while ((c = in.read()) != -1) {
                head.append((char) c);
                if (head.toString().endsWith("\r\n\r\n")) break;
            }
            if (!head.toString().contains("101")) throw new IOException("handshake failed: " + head.toString().replace('\n', ' '));
        }

        void send(String text) throws IOException {
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            out.write(0x81); // FIN + text frame
            if (payload.length < 126) out.write(0x80 | payload.length);
            else if (payload.length < 65_536) {
                out.write(0x80 | 126);
                out.write((payload.length >> 8) & 0xFF);
                out.write(payload.length & 0xFF);
            } else {
                out.write(0x80 | 127);
                for (int i = 7; i >= 0; i--) out.write((payload.length >>> (i * 8)) & 0xFF);
            }
            byte[] mask = new byte[4];
            new java.security.SecureRandom().nextBytes(mask);
            out.write(mask);
            for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
            out.write(payload);
            out.flush();
        }

        /** Reads frames for up to [seconds], handing text payloads to [onText]; returns on close. */
        void drain(Consumer<String> onText, int seconds) throws Exception {
            long deadline = System.currentTimeMillis() + seconds * 1000L;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            while (System.currentTimeMillis() < deadline) {
                int b0 = in.read();
                if (b0 == -1) return;
                int b1 = in.read();
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) len = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
                else if (len == 127) for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xFF);
                byte[] mask = masked ? in.readNBytes(4) : null;
                buf.reset();
                int remaining = (int) len;
                byte[] chunk = new byte[Math.min(Math.max(remaining, 1), 8192)];
                while (remaining > 0) {
                    int n = in.read(chunk, 0, Math.min(chunk.length, remaining));
                    if (n == -1) return;
                    buf.write(chunk, 0, n);
                    remaining -= n;
                }
                byte[] payload = buf.toByteArray();
                if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
                int opcode = b0 & 0x0F;
                if (opcode == 8) return; // server close
                if (opcode == 1) onText.accept(new String(payload, StandardCharsets.UTF_8));
            }
        }

        @Override public void close() {
            try {
                out.write(0x88);
                out.write(0x80);
                out.write(new byte[4]);
                out.flush();
            } catch (Exception ignored) {}
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    static String authUrl(String apiKey, String apiSecret) {
        try {
            String host = "iat-api.xfyun.cn";
            String path = "/v2/iat";
            String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(java.time.ZoneOffset.UTC));
            String origin = "host: " + host + "\ndate: " + date + "\nGET " + path + " HTTP/1.1";
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = Base64.getEncoder().encodeToString(mac.doFinal(origin.getBytes(StandardCharsets.UTF_8)));
            String authOrigin = "api_key=\"" + apiKey + "\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"" + signature + "\"";
            String authorization = Base64.getEncoder().encodeToString(authOrigin.getBytes(StandardCharsets.UTF_8));
            return "wss://" + host + path + "?authorization=" + URLEncoder.encode(authorization, StandardCharsets.UTF_8)
                + "&date=" + URLEncoder.encode(date, StandardCharsets.UTF_8)
                + "&host=" + URLEncoder.encode(host, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("asr signature failed", e);
        }
    }
}
