package com.readygym.server.ai;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AiUsageRepository extends JpaRepository<AiUsage, Long> {
    Optional<AiUsage> findByDeviceIdAndDay(String deviceId, LocalDate day);

    /**
     * Atomic per-day charge: the WHERE clause is the quota gate, so concurrent requests for the
     * same device can never push the counter past the limit. Returns the number of updated rows
     * (0 = no row for today yet, or the limit is already reached). It commits on its own instead
     * of joining a long controller transaction, so a unique-key clash on the first insert cannot
     * mark that transaction rollback-only and break the retry.
     */
    @Modifying
    @Query("update AiUsage u set u.calls = u.calls + 1 " +
           "where u.deviceId = :deviceId and u.day = :day and u.calls < :limit")
    @Transactional
    int tryCharge(@Param("deviceId") String deviceId,
                  @Param("day") LocalDate day,
                  @Param("limit") int limit);

    @Query("select coalesce(sum(u.calls), 0) from AiUsage u where u.day = ?1")
    long totalCallsOn(LocalDate day);

    @Query("select u.day, coalesce(sum(u.calls), 0) from AiUsage u where u.day >= ?1 group by u.day")
    List<Object[]> callsByDay(LocalDate since);
}
