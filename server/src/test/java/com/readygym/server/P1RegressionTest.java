package com.readygym.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.readygym.server.ai.AiUsage;
import com.readygym.server.ai.AiUsageRepository;
import com.readygym.server.ai.ModelGateway;
import com.readygym.server.asr.XfAsrClient;
import com.readygym.server.common.RateLimitFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1 hardening regressions. Kept on plain JUnit + MockMvc (no new dependencies); the Spring
 * profile uses its own in-memory database name and a tiny daily quota so concurrency is cheap.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:readygym-p1;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "readygym.ai.daily-limit-per-device=5",
    "readygym.ai.max-tokens=1000",
    "readygym.admin-token=admin-secret",
})
class P1RegressionTest {
    private static final String CHAT = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AiUsageRepository usage;
    @MockitoBean ModelGateway gateway;
    @MockitoBean XfAsrClient asr;

    private String register(String nickname) throws Exception {
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"" + nickname + "\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    private ResultActions chat(String token, String body) throws Exception {
        return mvc.perform(post("/api/ai/chat/completions")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** C1: the conditional UPDATE is the gate — N concurrent calls can never exceed the quota. */
    @Test
    void concurrentChatsNeverExceedTheDailyLimit() throws Exception {
        when(gateway.complete(anyString())).thenReturn(new ModelGateway.UpstreamResponse(200, "{\"ok\":1}"));
        String token = register("并发");

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/ai/chat/completions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(CHAT))
                    .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        int ok = 0, limited = 0;
        for (Future<Integer> f : futures) {
            int code = f.get(30, TimeUnit.SECONDS);
            if (code == 200) ok++;
            else if (code == 429) limited++;
        }
        pool.shutdownNow();

        assertThat(ok).isEqualTo(5);                 // daily-limit-per-device=5
        assertThat(ok + limited).isEqualTo(threads); // every request got a definite answer
        List<AiUsage> rows = usage.findAll();
        assertThat(rows).hasSize(1);                 // the (device, day) unique key held
        assertThat(rows.get(0).getCalls()).isEqualTo(5);
    }

    /** C1: a 401/timeout/5xx upstream must not cost a call, and the full quota remains usable. */
    @Test
    void upstreamFailureDoesNotConsumeQuota() throws Exception {
        when(gateway.complete(anyString())).thenReturn(new ModelGateway.UpstreamResponse(401, "{}"));
        String token = register("失败");
        for (int i = 0; i < 8; i++) {
            chat(token, CHAT).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("upstream_unavailable"));
        }
        assertThat(usage.count()).isZero();          // no charge row was ever created

        reset(gateway);
        when(gateway.complete(anyString())).thenReturn(new ModelGateway.UpstreamResponse(200, "{\"ok\":1}"));
        for (int i = 0; i < 5; i++) chat(token, CHAT).andExpect(status().isOk());
        chat(token, CHAT).andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error.code").value("daily_limit"));
    }

    /** C2: normal frequency passes, abuse gets 429 with the shared error shape, other routes are free. */
    @Test
    void rateLimitAllowsNormalBurstsAndBlocksAbuse() throws Exception {
        RateLimitFilter filter = new RateLimitFilter();
        String ip = "10.20.30.40";
        for (int i = 0; i < 30; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/feedback");
            request.setRemoteAddr(ip);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean reached = new AtomicBoolean(false);
            filter.doFilter(request, response, (r, s) -> reached.set(true));
            assertThat(reached.get()).isTrue();
            assertThat(response.getStatus()).isEqualTo(200);
        }

        MockHttpServletRequest blocked = new MockHttpServletRequest("POST", "/api/feedback");
        blocked.setRemoteAddr(ip);
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean(false);
        filter.doFilter(blocked, rejected, (r, s) -> reached.set(true));
        assertThat(reached.get()).isFalse();
        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getContentAsString()).contains("rate_limited");
        assertThat(rejected.getContentAsString()).contains("\"code\"");

        MockHttpServletRequest other = new MockHttpServletRequest("POST", "/api/workouts");
        other.setRemoteAddr(ip);
        MockHttpServletResponse untouched = new MockHttpServletResponse();
        AtomicBoolean otherReached = new AtomicBoolean(false);
        filter.doFilter(other, untouched, (r, s) -> otherReached.set(true));
        assertThat(otherReached.get()).isTrue();
        assertThat(untouched.getStatus()).isEqualTo(200);
    }

    /** C3/C2: oversized bodies keep the uniform {"error":{"code","message"}} shape. */
    @Test
    void oversizedBodyReturnsTheUniformErrorShape() throws Exception {
        byte[] huge = new byte[300 * 1024];   // /api/feedback cap is 256 KB
        mvc.perform(post("/api/feedback").contentType(MediaType.APPLICATION_JSON).content(huge))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.error.code").value("request_too_large"))
            .andExpect(jsonPath("$.error.message").isString());
    }

    /** C3: an unexpected exception becomes a stable 500 and never leaks a stack trace. */
    @Test
    void unexpectedExceptionReturnsUniform500WithoutStackTrace() throws Exception {
        when(gateway.complete(anyString())).thenThrow(new IllegalStateException("secret-stack-detail"));
        String token = register("兜底");
        mvc.perform(post("/api/ai/chat/completions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(CHAT))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.code").value("internal_error"))
            .andExpect(jsonPath("$.error.message").isString())
            .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                .doesNotContain("secret-stack-detail"));
    }
}
