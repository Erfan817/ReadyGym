package com.readygym.server.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Caps request bodies before Spring deserializes them. Anonymous endpoints (ASR, feedback,
 * registration) used to accept an arbitrarily large base64/JSON body and decode it into memory
 * before any @Valid or length check ran, which could exhaust the 512 MB heap.
 *
 * The caps leave headroom over the current legal maxima: the one-shot ASR clip is limited to
 * 2,000,000 raw PCM bytes (~2.7 MB once base64-wrapped in JSON), and the AI proxy allows at most
 * 30,000 message characters. Ordinary requests are therefore unaffected.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    private static final long ASR_LIMIT = 3L * 1024 * 1024;
    private static final long DEFAULT_LIMIT = 256L * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        boolean hasBody = "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
        return !hasBody || !request.getRequestURI().startsWith("/api/");
    }

    private static long limitFor(String uri) {
        // Both the one-shot /api/asr and the streaming /api/asr/stream carry PCM.
        return uri.startsWith("/api/asr") ? ASR_LIMIT : DEFAULT_LIMIT;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long limit = limitFor(request.getRequestURI());
        long declared = request.getContentLengthLong();
        if (declared > limit) {
            reject(response);
            return;
        }
        // A chunked request has no Content-Length; keep protecting it by capping the bytes read.
        HttpServletRequest downstream = declared < 0 ? new LimitedRequest(request, limit) : request;
        try {
            chain.doFilter(downstream, response);
        } catch (BodyTooLarge e) {
            if (!response.isCommitted()) reject(response);
        }
    }

    /** Same {"error":{"code","message"}} shape as ErrorHandler, so the app parses it unchanged. */
    private static void reject(HttpServletResponse response) throws IOException {
        if (response.isCommitted()) return;
        response.resetBuffer();
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
            "{\"error\":{\"code\":\"request_too_large\",\"message\":\"内容太长了，请缩短后重试\"}}");
    }

    /** Thrown from the wrapped stream as soon as the accumulated bytes exceed the cap. */
    static final class BodyTooLarge extends IOException {
        BodyTooLarge() { super("request body too large"); }
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long limit;
        private ServletInputStream limited;

        LimitedRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (limited == null) limited = new LimitedStream(super.getInputStream(), limit);
            return limited;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class LimitedStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long limit;
        private long read;

        LimitedStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        private void count(int n) throws IOException {
            if (n <= 0) return;
            read += n;
            if (read > limit) throw new BodyTooLarge();
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            count(b >= 0 ? 1 : 0);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            count(n);
            return n;
        }

        @Override public boolean isFinished() { return delegate.isFinished(); }
        @Override public boolean isReady() { return delegate.isReady(); }
        @Override public void setReadListener(ReadListener listener) { delegate.setReadListener(listener); }
    }
}
