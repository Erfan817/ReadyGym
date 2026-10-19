package com.readygym.server.ai;

import com.readygym.server.common.ApiException;
import com.readygym.server.common.ReadyGymProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** DeepSeek over HTTPS. The key comes from the environment and never leaves this process. */
@Component
public class DeepSeekGateway implements ModelGateway {
    private final ReadyGymProperties props;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public DeepSeekGateway(ReadyGymProperties props) {
        this.props = props;
    }

    @Override
    public UpstreamResponse complete(String requestJson) {
        String key = props.deepseek().apiKey();
        if (key == null || key.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ai_not_configured", "服务器还没有配置 AI Key");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(props.deepseek().baseUrl() + "/chat/completions"))
            .timeout(Duration.ofSeconds(90))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + key)
            .POST(HttpRequest.BodyPublishers.ofString(requestJson))
            .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new UpstreamResponse(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "upstream_unreachable", "AI 服务连接失败，请稍后再试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "interrupted", "请求被中断");
        }
    }
}
