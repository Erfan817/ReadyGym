package com.readygym.server.feedback;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FeedbackRepository extends JpaRepository<FeedbackRecord, Long> {
    /** Newest first; the operator reads this by hand, so a generous cap beats paging UI. */
    List<FeedbackRecord> findFirst200ByOrderByCreatedAtDesc();
}
