package com.readygym.server.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;

@ConfigurationProperties("readygym")
public record ReadyGymProperties(DeepSeek deepseek, Ai ai, String adminToken, String zone, Xfyun xfyun) {
    public record DeepSeek(String apiKey, String baseUrl, String model) {}
    public record Ai(int dailyLimitPerDevice, int maxTokens) {}

    /** XunFei 语音听写 credentials; all blank = the ASR endpoint reports asr_not_configured. */
    public record Xfyun(String appId, String apiKey, String apiSecret) {}

    public ZoneId zoneId() { return ZoneId.of(zone); }
}
