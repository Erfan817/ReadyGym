package com.readygym.server.workout;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * The summary of one finished workout, uploaded by the app. Set-level detail stays on the phone;
 * the server only needs enough to rank weeks and count activity.
 */
@Entity
@Table(name = "workouts", uniqueConstraints = @UniqueConstraint(columnNames = {"device_id", "client_session_id"}))
public class WorkoutRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String deviceId;

    /** The app's local session id, so a retried upload updates instead of double-counting. */
    @Column(nullable = false)
    private long clientSessionId;

    @Column(nullable = false)
    private Instant startedAt;

    @Column(nullable = false)
    private Instant endedAt;

    private int completedSets;
    private int totalSets;
    private double volumeKg;

    /** The user's weekly target when this workout happened; the leaderboard divides by it. */
    private int plannedDaysPerWeek;

    protected WorkoutRecord() {}

    public WorkoutRecord(String deviceId, long clientSessionId) {
        this.deviceId = deviceId;
        this.clientSessionId = clientSessionId;
    }

    public void update(Instant startedAt, Instant endedAt, int completedSets, int totalSets, double volumeKg, int plannedDaysPerWeek) {
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.completedSets = completedSets;
        this.totalSets = totalSets;
        this.volumeKg = volumeKg;
        this.plannedDaysPerWeek = plannedDaysPerWeek;
    }

    public String getDeviceId() { return deviceId; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public int getPlannedDaysPerWeek() { return plannedDaysPerWeek; }
}
