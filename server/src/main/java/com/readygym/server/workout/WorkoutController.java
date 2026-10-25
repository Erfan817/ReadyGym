package com.readygym.server.workout;

import com.readygym.server.account.AuthInterceptor;
import com.readygym.server.account.Device;
import com.readygym.server.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;

@RestController
@RequestMapping("/api")
public class WorkoutController {
    private final WorkoutRepository workouts;
    private final LeaderboardService leaderboard;

    public WorkoutController(WorkoutRepository workouts, LeaderboardService leaderboard) {
        this.workouts = workouts;
        this.leaderboard = leaderboard;
    }

    public record WorkoutUpload(
        @NotNull Long clientSessionId,
        @NotNull Long startedAtMillis,
        @NotNull Long endedAtMillis,
        @Min(0) @Max(500) int completedSets,
        @Min(0) @Max(500) int totalSets,
        @Min(0) @Max(1_000_000) double volumeKg,
        @Min(1) @Max(7) int plannedDaysPerWeek) {}

    /** Idempotent: uploading the same session again replaces it, so the app can retry freely. */
    @PostMapping("/workouts")
    public LeaderboardService.Board upload(@RequestAttribute(AuthInterceptor.DEVICE) Device device,
                                           @RequestBody @Valid WorkoutUpload body) {
        Instant start = Instant.ofEpochMilli(body.startedAtMillis());
        Instant end = Instant.ofEpochMilli(body.endedAtMillis());
        Instant now = Instant.now();
        if (end.isBefore(start) || Duration.between(start, end).toHours() > 12 || start.isAfter(now.plusSeconds(300))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_workout", "训练时间不合理");
        }
        if (body.completedSets() == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_workout", "没有完成的组");
        WorkoutRecord record = workouts.findByDeviceIdAndClientSessionId(device.getId(), body.clientSessionId())
            .orElseGet(() -> new WorkoutRecord(device.getId(), body.clientSessionId()));
        record.update(start, end, body.completedSets(), body.totalSets(), body.volumeKg(), body.plannedDaysPerWeek());
        workouts.save(record);
        return leaderboard.weekly(device, now);
    }

    @GetMapping("/leaderboard/weekly")
    public LeaderboardService.Board weekly(@RequestAttribute(AuthInterceptor.DEVICE) Device device) {
        return leaderboard.weekly(device, Instant.now());
    }
}
