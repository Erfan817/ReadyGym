package com.readygym.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.readygym.server.ai.ModelGateway;
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
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
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
}
