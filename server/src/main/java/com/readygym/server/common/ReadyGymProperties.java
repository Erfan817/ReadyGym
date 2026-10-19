package com.readygym.server.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;

@ConfigurationProperties("readygym")
public record ReadyGymProperties(DeepSeek deepseek, Ai ai, String adminToken, String zone) {
    public record DeepSeek(String apiKey, String baseUrl, String model) {}
    public record Ai(int dailyLimitPerDevice, int maxTokens) {}

    public ZoneId zoneId() { return ZoneId.of(zone); }
}
