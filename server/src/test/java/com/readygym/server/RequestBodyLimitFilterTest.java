package com.readygym.server;

import com.readygym.server.common.RequestBodyLimitFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class RequestBodyLimitFilterTest {

    private final RequestBodyLimitFilter filter = new RequestBodyLimitFilter();

    private static MockHttpServletRequest post(String uri, byte[] body, boolean declareLength) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContentType("application/json");
        request.setContent(body);
        if (declareLength) request.addHeader("Content-Length", body.length);
        return request;
    }

    @Test
    void ordinaryApiRequestPassesThroughUnchanged() throws Exception {
        MockHttpServletRequest request = post("/api/feedback",
            "{\"content\":\"hi\"}".getBytes(StandardCharsets.UTF_8), true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean(false);
        filter.doFilter(request, response, (r, s) -> reached.set(true));
        assertThat(reached.get()).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void oversizedBodyIsRejectedBeforeDeserialization() throws Exception {
        MockHttpServletRequest request = post("/api/feedback", new byte[300 * 1024], true);
        assertThat(request.getContentLengthLong()).isEqualTo(300L * 1024);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean(false);
        filter.doFilter(request, response, (r, s) -> reached.set(true));
        assertThat(reached.get()).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("request_too_large");
    }

    @Test
    void asrKeepsItsLargerLegalLimit() throws Exception {
        // 2.9 MB is below the 3 MB ASR cap; the controller still enforces the 2 MB PCM cap.
        MockHttpServletRequest request = post("/api/asr", new byte[2_900_000], true);
        assertThat(request.getContentLengthLong()).isEqualTo(2_900_000L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean(false);
        filter.doFilter(request, response, (r, s) -> reached.set(true));
        assertThat(reached.get()).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void asrBodyAboveThreeMegabytesIsRejected() throws Exception {
        MockHttpServletRequest request = post("/api/asr", new byte[3 * 1024 * 1024 + 1], true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean(false);
        filter.doFilter(request, response, (r, s) -> reached.set(true));
        assertThat(reached.get()).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void chunkedRequestIsCappedWhileBeingRead() throws Exception {
        // MockHttpServletRequest fills in Content-Length from setContent(), so wrap it to model a
        // real chunked request that declares no length at all.
        MockHttpServletRequest base = post("/api/feedback", new byte[300 * 1024], false);
        HttpServletRequest chunked = new HttpServletRequestWrapper(base) {
            @Override public long getContentLengthLong() { return -1L; }
            @Override public int getContentLength() { return -1; }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(chunked, response,
            (r, s) -> ((HttpServletRequest) r).getInputStream().readAllBytes());
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("request_too_large");
    }

    @Test
    void getRequestsAndStaticResourcesAreUntouched() throws Exception {
        MockHttpServletRequest apiGet = new MockHttpServletRequest("GET", "/api/leaderboard/weekly");
        MockHttpServletRequest gif = new MockHttpServletRequest("GET", "/gifs/anything.gif");
        for (MockHttpServletRequest request : new MockHttpServletRequest[]{apiGet, gif}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean reached = new AtomicBoolean(false);
            filter.doFilter(request, response, (r, s) -> reached.set(true));
            assertThat(reached.get()).isTrue();
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }
}
