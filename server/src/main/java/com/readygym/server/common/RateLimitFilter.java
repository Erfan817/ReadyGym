package com.readygym.server.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loose per-IP fixed-window limits for the anonymous write endpoints. The thresholds are far above
 * what a real user produces (a chat/ASR burst is a handful of calls), but stop one host from
 * farming free registration, feedback or ASR quota. Buckets live in memory and are swept lazily
 * on busy requests, so there is no scheduled executor and no resident thread to shut down.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)   // right behind RequestBodyLimitFilter
public class RateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MS = 60_000L;
    private static final int REGISTER_PER_MIN = 20;
    private static final int FEEDBACK_PER_MIN = 30;
    private static final int ASR_PER_MIN = 60;
    /** One ~60 s hold posts a stream chunk about every 0.75 s; keep its headroom well above 60. */
    private static final int ASR_STREAM_PER_MIN = 240;
    private static final int MAX_BUCKETS = 1024;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    private static final class Bucket {
        final long window;
        final AtomicInteger count = new AtomicInteger();

        Bucket(long window) { this.window = window; }
    }

    /** The limit and the independent counter name for one URI; NONE means "not protected". */
    private record Rule(int limit, String name) {
        static final Rule NONE = new Rule(-1, "");
    }

    private static Rule ruleFor(String uri) {
        if ("/api/auth/register".equals(uri)) return new Rule(REGISTER_PER_MIN, "register");
        if ("/api/feedback".equals(uri)) return new Rule(FEEDBACK_PER_MIN, "feedback");
        if ("/api/asr/stream".equals(uri)) return new Rule(ASR_STREAM_PER_MIN, "asr-stream");
        if ("/api/asr".equals(uri) || uri.startsWith("/api/asr/")) return new Rule(ASR_PER_MIN, "asr");
        return Rule.NONE;
    }

    /** Only the anonymous write endpoints are counted; reads and everything else are untouched. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        boolean write = "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
        return !write || ruleFor(request.getRequestURI()).limit() < 0;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Rule rule = ruleFor(request.getRequestURI());
        long window = System.currentTimeMillis() / WINDOW_MS;
        String key = clientIp(request) + "|" + rule.name();
        Bucket bucket = buckets.compute(key, (k, old) ->
            (old == null || old.window != window) ? new Bucket(window) : old);
        if (bucket.count.incrementAndGet() > rule.limit()) {
            reject(response);
            return;
        }
        sweep(window);
        chain.doFilter(request, response);
    }

    /** getRemoteAddr, not X-Forwarded-For, so a caller cannot forge a fresh bucket with a header. */
    private static String clientIp(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return ip == null || ip.isBlank() ? "unknown" : ip;
    }

    /** Lazy cleanup: no background thread; only runs once the map has grown past MAX_BUCKETS. */
    private void sweep(long window) {
        if (buckets.size() <= MAX_BUCKETS) return;
        buckets.entrySet().removeIf(e -> e.getValue().window < window);
    }

    /** Same {"error":{"code","message"}} shape as ErrorHandler, so the app parses it unchanged. */
    private static void reject(HttpServletResponse response) throws IOException {
        if (response.isCommitted()) return;
        response.resetBuffer();
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
            "{\"error\":{\"code\":\"rate_limited\",\"message\":\"请求太频繁，请稍后再试\"}}");
    }
}
