package com.readygym.server.feedback;

import com.readygym.server.common.ApiException;
import com.readygym.server.common.ReadyGymProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The app's 意见反馈 form posts here (anonymous: the barrier to report a problem must be as low
 * as possible), and the operator reads the list through the admin token.
 */
@RestController
public class FeedbackController {
    private final FeedbackRepository feedback;
    private final ReadyGymProperties props;

    public FeedbackController(FeedbackRepository feedback, ReadyGymProperties props) {
        this.feedback = feedback;
        this.props = props;
    }

    public record FeedbackUpload(
        @NotBlank @Size(max = 1000) String content,
        @Size(max = 100) String contact,
        @Size(max = 40) String appVersion,
        @Size(max = 80) String deviceModel,
        @Size(max = 40) String osVersion,
        @Size(max = 64) String deviceId) {}

    public record FeedbackEntry(long id, String createdAt, String content, String contact,
                                String appVersion, String deviceModel, String osVersion) {}

    @PostMapping("/api/feedback")
    @ResponseStatus(HttpStatus.CREATED)
    public void submit(@RequestBody @Valid FeedbackUpload body) {
        feedback.save(new FeedbackRecord(body.deviceId(), body.content().trim(),
            body.contact(), body.appVersion(), body.deviceModel(), body.osVersion()));
    }

    @GetMapping("/api/admin/feedback")
    public List<FeedbackEntry> list(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        String expected = props.adminToken();
        // No configured token means the admin API is off, not open.
        if (expected == null || expected.isBlank() || token == null
            || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "无权访问");
        }
        var fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(props.zoneId());
        return feedback.findFirst200ByOrderByCreatedAtDesc().stream()
            .map(f -> new FeedbackEntry(f.getId(), fmt.format(f.getCreatedAt()), f.getContent(),
                f.getContact(), f.getAppVersion(), f.getDeviceModel(), f.getOsVersion()))
            .toList();
    }
}
