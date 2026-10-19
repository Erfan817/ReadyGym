package com.readygym.server.workout;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WorkoutRepository extends JpaRepository<WorkoutRecord, Long> {
    Optional<WorkoutRecord> findByDeviceIdAndClientSessionId(String deviceId, long clientSessionId);
    List<WorkoutRecord> findByStartedAtGreaterThanEqualAndStartedAtLessThan(Instant from, Instant to);
    long countByStartedAtGreaterThanEqual(Instant from);
}
