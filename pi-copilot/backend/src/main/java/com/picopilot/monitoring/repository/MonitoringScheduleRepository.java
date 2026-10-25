package com.picopilot.monitoring.repository;

import com.picopilot.monitoring.model.MonitoringSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MonitoringScheduleRepository extends JpaRepository<MonitoringSchedule, UUID> {
    Optional<MonitoringSchedule> findByWorkspaceId(UUID workspaceId);
    List<MonitoringSchedule> findByEnabledTrueAndNextRunAtLessThanEqual(Instant now);
}
