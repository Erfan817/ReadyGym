package com.readygym.server.account;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final DeviceRepository devices;

    public AuthController(DeviceRepository devices) {
        this.devices = devices;
    }

    public record RegisterRequest(@NotNull String nickname) {}
    public record RegisterResponse(String deviceId, String token, String nickname) {}
    public record MeResponse(String deviceId, String nickname) {}

    /** Creates an anonymous account. The token is returned once; only its hash is kept. */
    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@RequestBody @Valid RegisterRequest request) {
        String token = Tokens.newToken();
        Device device = devices.save(new Device(UUID.randomUUID().toString(), Nicknames.clean(request.nickname()),
            Tokens.hash(token), Instant.now()));
        return new RegisterResponse(device.getId(), token, device.getNickname());
    }

    @GetMapping("/me")
    public MeResponse me(@RequestAttribute(AuthInterceptor.DEVICE) Device device) {
        return new MeResponse(device.getId(), device.getNickname());
    }

    @PostMapping("/me/nickname")
    public MeResponse rename(@RequestAttribute(AuthInterceptor.DEVICE) Device device, @RequestBody Map<String, String> body) {
        device.setNickname(Nicknames.clean(body.get("nickname")));
        devices.save(device);
        return new MeResponse(device.getId(), device.getNickname());
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
