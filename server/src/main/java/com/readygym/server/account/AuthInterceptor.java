package com.readygym.server.account;

import com.readygym.server.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.time.Instant;

/** Resolves the bearer token to a {@link Device} and exposes it as the "device" request attribute. */
@Component
public class AuthInterceptor implements HandlerInterceptor {
    public static final String DEVICE = "device";
    private final DeviceRepository devices;

    public AuthInterceptor(DeviceRepository devices) {
        this.devices = devices;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "请先注册");
        }
        Device device = devices.findByTokenHash(Tokens.hash(header.substring(7).trim()))
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "登录已失效，请重新注册"));
        Instant now = Instant.now();
        // Coarse last-seen tracking is enough for "active users" and avoids a write per request.
        if (Duration.between(device.getLastSeenAt(), now).toMinutes() >= 10) {
            device.setLastSeenAt(now);
            devices.save(device);
        }
        request.setAttribute(DEVICE, device);
        return true;
    }
}
