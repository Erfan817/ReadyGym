package com.readygym.server.workout;

import com.readygym.server.account.Device;
import com.readygym.server.account.DeviceRepository;
import com.readygym.server.common.ReadyGymProperties;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 自律排行榜: this week's completion rate = workouts done ÷ the user's own weekly target, capped at
 * 100%. Dividing by the personal target keeps a 2-day plan and a 5-day plan comparable. Ties go to
 * more workouts, then to whoever finished earlier.
 */
@Service
public class LeaderboardService {
    public static final int TOP = 50;

    private final WorkoutRepository workouts;
    private final DeviceRepository devices;
    private final ReadyGymProperties props;

    public LeaderboardService(WorkoutRepository workouts, DeviceRepository devices, ReadyGymProperties props) {
        this.workouts = workouts;
        this.devices = devices;
        this.props = props;
    }

    public record Entry(int rank, String nickname, int done, int planned, double rate, boolean me) {}
    public record Board(LocalDate weekStart, List<Entry> entries, Entry me, int participants) {}

    public Board weekly(Device viewer, Instant now) {
        LocalDate weekStart = LocalDate.ofInstant(now, props.zoneId()).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Instant from = weekStart.atStartOfDay(props.zoneId()).toInstant();
        Instant to = weekStart.plusWeeks(1).atStartOfDay(props.zoneId()).toInstant();

        Map<String, List<WorkoutRecord>> byDevice = workouts.findByStartedAtGreaterThanEqualAndStartedAtLessThan(from, to)
            .stream().collect(Collectors.groupingBy(WorkoutRecord::getDeviceId));
        Map<String, String> names = devices.findAllById(byDevice.keySet()).stream()
            .collect(Collectors.toMap(Device::getId, Device::getNickname));

        record Row(String deviceId, int done, int planned, double rate, Instant last) {}
        List<Row> rows = byDevice.entrySet().stream().map(e -> {
            List<WorkoutRecord> list = e.getValue();
            WorkoutRecord latest = list.stream().max(Comparator.comparing(WorkoutRecord::getStartedAt)).orElseThrow();
            int planned = Math.max(1, latest.getPlannedDaysPerWeek());
            int done = list.size();
            Instant last = list.stream().map(WorkoutRecord::getEndedAt).max(Comparator.naturalOrder()).orElse(from);
            return new Row(e.getKey(), done, planned, Math.min(done, planned) / (double) planned, last);
        }).sorted(Comparator.comparingDouble(Row::rate).reversed()
            .thenComparing(Comparator.comparingInt(Row::done).reversed())
            .thenComparing(Row::last)).toList();

        List<Entry> ranked = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            ranked.add(new Entry(i + 1, names.getOrDefault(r.deviceId(), "匿名"), r.done(), r.planned(), r.rate(),
                viewer != null && r.deviceId().equals(viewer.getId())));
        }
        Entry me = ranked.stream().filter(Entry::me).findFirst().orElse(null);
        return new Board(weekStart, ranked.stream().limit(TOP).toList(), me, ranked.size());
    }
}
