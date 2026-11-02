package com.readygym.server.ai;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AiUsageRepository extends JpaRepository<AiUsage, Long> {
    Optional<AiUsage> findByDeviceIdAndDay(String deviceId, LocalDate day);

    @Query("select coalesce(sum(u.calls), 0) from AiUsage u where u.day = ?1")
    long totalCallsOn(LocalDate day);

    @Query("select u.day, coalesce(sum(u.calls), 0) from AiUsage u where u.day >= ?1 group by u.day")
    List<Object[]> callsByDay(LocalDate since);
}
