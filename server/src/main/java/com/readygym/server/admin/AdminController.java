package com.readygym.server.admin;

import com.readygym.server.account.DeviceRepository;
import com.readygym.server.ai.AiUsageRepository;
import com.readygym.server.common.ApiException;
import com.readygym.server.common.ReadyGymProperties;
import com.readygym.server.workout.WorkoutRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/** Operator numbers: how many people use the app and how much AI it consumes. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final DeviceRepository devices;
    private final WorkoutRepository workouts;
    private final AiUsageRepository usage;
    private final ReadyGymProperties props;

    public AdminController(DeviceRepository devices, WorkoutRepository workouts, AiUsageRepository usage, ReadyGymProperties props) {
        this.devices = devices;
        this.workouts = workouts;
        this.usage = usage;
        this.props = props;
    }

    public record Stats(long totalUsers, long newUsersToday, long activeUsers7d, long workoutsTotal,
                        long workoutsToday, long aiCallsToday) {}

    @GetMapping("/stats")
    public Stats stats(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        String expected = props.adminToken();
        // No configured token means the admin API is off, not open.
        if (expected == null || expected.isBlank() || token == null
            || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "无权访问");
        }
        LocalDate today = LocalDate.now(props.zoneId());
        Instant startOfToday = today.atStartOfDay(props.zoneId()).toInstant();
        return new Stats(
            devices.count(),
            devices.countByCreatedAtAfter(startOfToday),
            devices.countByLastSeenAtAfter(Instant.now().minus(Duration.ofDays(7))),
            workouts.count(),
            workouts.countByStartedAtGreaterThanEqual(startOfToday),
            usage.totalCallsOn(today));
    }
}
