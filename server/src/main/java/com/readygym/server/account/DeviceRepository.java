package com.readygym.server.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, String> {
    Optional<Device> findByTokenHash(String tokenHash);
    long countByCreatedAtAfter(Instant since);
    long countByLastSeenAtAfter(Instant since);

    /** Timestamps only, so the daily chart can group by calendar day in the operator's zone. */
    @Query("select d.createdAt from Device d where d.createdAt >= ?1")
    List<Instant> createdSince(Instant since);
}
