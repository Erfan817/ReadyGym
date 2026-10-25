package com.readygym.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.readygym.server.ai.ModelGateway;
import com.readygym.server.asr.XfAsrClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:readygym-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "readygym.ai.daily-limit-per-device=2",
    "readygym.ai.max-tokens=1000",
    "readygym.admin-token=admin-secret",
})
class ServerApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean ModelGateway gateway;
    @MockitoBean XfAsrClient asr;

    private String register(String nickname) throws Exception {
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"" + nickname + "\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    private ResultActions chat(String token, String body) throws Exception {
        return mvc.perform(post("/api/ai/chat/completions").header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions upload(String token, long id, Instant start, int planned) throws Exception {
        String body = """
            {"clientSessionId":%d,"startedAtMillis":%d,"endedAtMillis":%d,"completedSets":10,"totalSets":12,"volumeKg":500,"plannedDaysPerWeek":%d}
            """.formatted(id, start.toEpochMilli(), start.plus(40, ChronoUnit.MINUTES).toEpochMilli(), planned);
        return mvc.perform(post("/api/workouts").header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void registrationIssuesATokenAndEverythingElseRequiresIt() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("unauthorized"));
        String token = register("小绿");
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("小绿"));
        mvc.perform(get("/api/me").header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"  \"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid_nickname"));
    }

    @Test
    void aiProxyAddsTheGuardOwnsTheSettingsAndEnforcesTheDailyLimit() throws Exception {
        when(gateway.complete(anyString())).thenReturn(new ModelGateway.UpstreamResponse(200,
            "{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}"));
        String token = register("阿强");
        String request = """
            {"model":"some-expensive-model","max_tokens":99999,"response_format":{"type":"json_object"},
             "messages":[{"role":"system","content":"app prompt"},{"role":"user","content":"帮我写一段 Python"}]}""";
        chat(token, request).andExpect(status().isOk()).andExpect(jsonPath("$.choices[0].message.content").value("{}"));

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(gateway).complete(sent.capture());
        JsonNode upstream = json.readTree(sent.getValue());
        assertThat(upstream.get("model").asText()).isEqualTo("deepseek-chat");
        assertThat(upstream.get("max_tokens").asInt()).isEqualTo(1000);
        assertThat(upstream.at("/messages/0/role").asText()).isEqualTo("system");
        assertThat(upstream.at("/messages/0/content").asText()).contains("只回答健身");
        assertThat(upstream.at("/messages/1/content").asText()).isEqualTo("app prompt");
        assertThat(upstream.at("/response_format/type").asText()).isEqualTo("json_object");

        chat(token, request).andExpect(status().isOk());
        chat(token, request).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error.code").value("daily_limit"));
        verify(gateway, times(2)).complete(anyString());
    }

    @Test
    void providerAuthFailureIsNotReportedAsTheUsersFault() throws Exception {
        when(gateway.complete(anyString())).thenReturn(new ModelGateway.UpstreamResponse(401, "{}"));
        String token = register("测试");
        chat(token, "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("upstream_unavailable"));
        chat(token, "{\"messages\":[{\"role\":\"tool\",\"content\":\"x\"}]}").andExpect(status().isBadRequest());
    }

    @Test
    void leaderboardRanksByCompletionRateAgainstEachUsersOwnTarget() throws Exception {
        Instant now = Instant.now().minus(1, ChronoUnit.HOURS);
        String twoOfTwo = register("两天党"), threeOfFour = register("四天党"), fourOfFour = register("铁人");
        upload(twoOfTwo, 1, now.minusSeconds(60), 2);
        upload(twoOfTwo, 1, now.minusSeconds(60), 2);   // retried upload: still one workout
        upload(twoOfTwo, 2, now, 2);
        for (int i = 1; i <= 3; i++) upload(threeOfFour, i, now.minusSeconds(i * 10L), 4);
        for (int i = 1; i <= 4; i++) upload(fourOfFour, i, now.minusSeconds(i * 10L), 4);

        mvc.perform(get("/api/leaderboard/weekly").header("Authorization", "Bearer " + threeOfFour))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.participants").value(3))
            .andExpect(jsonPath("$.entries[0].nickname").value("铁人"))
            .andExpect(jsonPath("$.entries[1].nickname").value("两天党"))
            .andExpect(jsonPath("$.entries[1].done").value(2))
            .andExpect(jsonPath("$.entries[2].nickname").value("四天党"))
            .andExpect(jsonPath("$.me.rank").value(3))
            .andExpect(jsonPath("$.me.rate").value(0.75));
    }

    @Test
    void adminStatsNeedTheAdminToken() throws Exception {
        mvc.perform(get("/api/admin/stats")).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/stats").header("X-Admin-Token", "nope")).andExpect(status().isForbidden());
        String token = register("甲");
        register("乙");
        upload(token, 1, Instant.now().minus(1, ChronoUnit.HOURS), 3);
        mvc.perform(get("/api/admin/stats").header("X-Admin-Token", "admin-secret"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalUsers").value(2))
            .andExpect(jsonPath("$.newUsersToday").value(2))
            .andExpect(jsonPath("$.workoutsTotal").value(1));
    }

    /** The daily series feeds the admin console chart: oldest first, today last, grouped by day. */
    @Test
    void dailySeriesNeedsTheAdminTokenAndBucketsByLocalDay() throws Exception {
        mvc.perform(get("/api/admin/stats/daily")).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/stats/daily?days=400").header("X-Admin-Token", "admin-secret"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(90));   // the span is capped
        String token = register("丙");
        register("丁");
        upload(token, 1, Instant.now().minus(1, ChronoUnit.HOURS), 3);
        String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        mvc.perform(get("/api/admin/stats/daily?days=7").header("X-Admin-Token", "admin-secret"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(7))
            .andExpect(jsonPath("$[6].date").value(today))
            .andExpect(jsonPath("$[6].newUsers").value(2))
            .andExpect(jsonPath("$[6].workouts").value(1))
            .andExpect(jsonPath("$[6].aiCalls").value(0))
            .andExpect(jsonPath("$[5].newUsers").value(0));
    }

    /** The operator console is a static page; its data stays behind the admin token. */
    @Test
    void adminConsolePageIsServedAsAStaticAsset() throws Exception {
        var response = mvc.perform(get("/admin.html")).andExpect(status().isOk()).andReturn().getResponse();
        // MockMvc decodes the body as ISO-8859-1 by default; the page itself is UTF-8.
        assertThat(new String(response.getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8))
            .contains("ReadyGym 运营面板");
    }

    /** The equipment guide's demonstration GIFs: public, correct type, cacheable, no traversal. */
    @Test
    void equipmentGifsAreServedPubliclyWithCaching() throws Exception {
        mvc.perform(get("/gifs/test-fixture/pixel.gif"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.IMAGE_GIF))
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=2592000")));
        mvc.perform(get("/gifs/does/not/exist.gif")).andExpect(status().isNotFound());
        mvc.perform(get("/gifs/../application.yml")).andExpect(status().is4xxClientError());
    }

    /**
     * Voice relay: anonymous, and the seconds cap must match the app's ~59 s recording window —
     * an 8 s cap here once rejected every normal sentence with a 400 the phone showed as
     * "语音服务暂时不可用".
     */
    @Test
    void voiceRelayAcceptsAHoldUpToTheAppsRecordingCap() throws Exception {
        when(asr.recognize(org.mockito.ArgumentMatchers.any())).thenReturn(new XfAsrClient.Result("深蹲怎么做"));
        String audio = java.util.Base64.getEncoder().encodeToString(new byte[64]);
        mvc.perform(post("/api/asr").contentType(MediaType.APPLICATION_JSON)
                .content("{\"audioB64\":\"" + audio + "\",\"seconds\":59}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.text").value("深蹲怎么做"));
        mvc.perform(post("/api/asr").contentType(MediaType.APPLICATION_JSON)
                .content("{\"audioB64\":\"" + audio + "\",\"seconds\":61}"))
            .andExpect(status().isBadRequest());
    }

    /** The streaming relay: session id is the capability, chunks give partials, finish gives text. */
    @Test
    void streamingRelayAcceptsChunksAndReturnsPartialsThenText() throws Exception {
        when(asr.startStream()).thenReturn("stream-1");
        when(asr.appendStream(eq("stream-1"), any())).thenReturn("深蹲");
        when(asr.finishStream("stream-1")).thenReturn("深蹲怎么做");
        when(asr.appendStream(eq("gone"), any())).thenThrow(new com.readygym.server.common.ApiException(
            org.springframework.http.HttpStatus.NOT_FOUND, "asr_session", "会话已失效，请重新按住说话"));

        String id = json.readTree(mvc.perform(post("/api/asr/start"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asText();
        String audio = java.util.Base64.getEncoder().encodeToString(new byte[64]);
        mvc.perform(post("/api/asr/stream").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"" + id + "\",\"audioB64\":\"" + audio + "\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.partial").value("深蹲"));
        mvc.perform(post("/api/asr/finish").contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"" + id + "\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.text").value("深蹲怎么做"));

        mvc.perform(post("/api/asr/stream").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"gone\",\"audioB64\":\"" + audio + "\"}"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("asr_session"));
        mvc.perform(post("/api/asr/abort").contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"stream-1\"}"))
            .andExpect(status().isOk());
    }

    /** Feedback is anonymous (no token), validated, and readable only with the admin token. */
    @Test
    void feedbackCanBeSubmittedAnonymouslyAndReadByTheOperator() throws Exception {
        mvc.perform(post("/api/feedback").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/feedback").contentType(MediaType.APPLICATION_JSON).content("""
            {"content":"器械指南的GIF加载很慢，希望能预下载","contact":"wx: gym-fan",
             "appVersion":"0.4.1","deviceModel":"Pixel 8","osVersion":"Android 15","deviceId":"d-1"}
            """))
            .andExpect(status().isCreated());
        mvc.perform(get("/api/admin/feedback")).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/feedback").header("X-Admin-Token", "admin-secret"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].content").value("器械指南的GIF加载很慢，希望能预下载"))
            .andExpect(jsonPath("$[0].deviceModel").value("Pixel 8"));
    }
}
