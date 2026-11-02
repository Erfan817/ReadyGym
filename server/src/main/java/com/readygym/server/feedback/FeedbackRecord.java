package com.readygym.server.feedback;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One user-submitted feedback entry from the app's 意见反馈 form.
 *
 * Anonymous by design: the app may not have an account when something is worth reporting, so the
 * row keeps whatever the phone chose to send (device model, app version, optional contact) and a
 * coarse device id for abuse filtering, never a nickname or training data.
 */
@Entity
@Table(name = "feedback")
public class FeedbackRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    /** The submitting device's stable id; blank when the app had no account yet. */
    @Column(nullable = false)
    private String deviceId = "";

    @Column(nullable = false, length = 1000)
    private String content;

    @Column(length = 100)
    private String contact = "";

    @Column(length = 40)
    private String appVersion = "";

    @Column(length = 80)
    private String deviceModel = "";

    @Column(length = 40)
    private String osVersion = "";

    protected FeedbackRecord() {}

    public FeedbackRecord(String deviceId, String content, String contact,
                          String appVersion, String deviceModel, String osVersion) {
        this.deviceId = deviceId == null ? "" : deviceId;
        this.content = content;
        this.contact = contact == null ? "" : contact;
        this.appVersion = appVersion == null ? "" : appVersion;
        this.deviceModel = deviceModel == null ? "" : deviceModel;
        this.osVersion = osVersion == null ? "" : osVersion;
    }

    public Long getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public String getDeviceId() { return deviceId; }
    public String getContent() { return content; }
    public String getContact() { return contact; }
    public String getAppVersion() { return appVersion; }
    public String getDeviceModel() { return deviceModel; }
    public String getOsVersion() { return osVersion; }
}
