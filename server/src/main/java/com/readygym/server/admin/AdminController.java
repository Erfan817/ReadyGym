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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** One chart point of the daily series: users registered, workouts uploaded, AI calls that day. */
    public record DayPoint(String date, long newUsers, long workouts, long aiCalls) {}

    @GetMapping("/stats")
    public Stats stats(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        requireAdmin(token);
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

    /**
     * The daily series behind the admin console's trend chart, oldest day first. Grouping happens
     * in Java on the operator's zone so H2 and MySQL behave identically; spans are small (days
     * capped at 90), so loading plain timestamps is cheaper than date functions per database.
     */
    @GetMapping("/stats/daily")
    public List<DayPoint> daily(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                @RequestParam(defaultValue = "30") int days) {
        requireAdmin(token);
        int span = Math.min(Math.max(days, 1), 90);
        var zone = props.zoneId();
        LocalDate today = LocalDate.now(zone);
        LocalDate firstDay = today.minusDays(span - 1L);
        Instant since = firstDay.atStartOfDay(zone).toInstant();

        Map<LocalDate, long[]> byDay = new LinkedHashMap<>();
        for (int i = 0; i < span; i++) byDay.put(firstDay.plusDays(i), new long[2]);
        for (Instant at : devices.createdSince(since)) {
            long[] slot = byDay.get(LocalDate.ofInstant(at, zone));
            if (slot != null) slot[0]++;
        }
        for (Instant at : workouts.startedSince(since)) {
            long[] slot = byDay.get(LocalDate.ofInstant(at, zone));
            if (slot != null) slot[1]++;
        }
        Map<LocalDate, Long> aiCalls = new HashMap<>();
        for (Object[] row : usage.callsByDay(firstDay)) {
            aiCalls.put((LocalDate) row[0], ((Number) row[1]).longValue());
        }
        return byDay.entrySet().stream()
            .map(e -> new DayPoint(e.getKey().toString(), e.getValue()[0], e.getValue()[1],
                aiCalls.getOrDefault(e.getKey(), 0L)))
            .toList();
    }

    private void requireAdmin(String token) {
        String expected = props.adminToken();
        // No configured token means the admin API is off, not open.
        if (expected == null || expected.isBlank() || token == null
            || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "无权访问");
        }
    }
}
