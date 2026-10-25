package com.readygym.server.ai;

import jakarta.persistence.*;

import java.time.LocalDate;

/** How many AI calls one device made on one day, for the daily quota and the admin stats. */
@Entity
@Table(name = "ai_usage", uniqueConstraints = @UniqueConstraint(columnNames = {"device_id", "usage_day"}))
public class AiUsage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String deviceId;

    /** "day" is a reserved word in H2 and MySQL. */
    @Column(name = "usage_day", nullable = false)
    private LocalDate day;

    @Column(nullable = false)
    private int calls;

    protected AiUsage() {}

    public AiUsage(String deviceId, LocalDate day) {
        this.deviceId = deviceId;
        this.day = day;
    }

    public int getCalls() { return calls; }
    public void increment() { calls++; }
}
