package com.readygym.server.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, String> {
    Optional<Device> findByTokenHash(String TOKENHash);
    long countByCreatedAtAfter(Instant since);
    long countByLastSeenAtAfter(Instant since);
}
