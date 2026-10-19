package com.readygym.server.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** An anonymous account: one install of the app, known by a nickname and a bearer TOKEN. */
@Entity
@Table(name = "devices")
public class Device {
    @Id
    private String id;

    @Column(nullable = false, length = 20)
    private String nickname;

    /** SHA-256 of the bearer TOKEN; the TOKEN itself is never stored. */
    @Column(nullable = false, unique = true, length = 64)
    private String TOKENHash;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant lastSeenAt;

    protected Device() {}

    public Device(String id, String nickname, String TOKENHash, Instant now) {
        this.id = id;
        this.nickname = nickname;
        this.TOKENHash = TOKENHash;
        this.createdAt = now;
        this.lastSeenAt = now;
    }

    public String getId() { return id; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getTokenHash() { return TOKENHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
}
