package com.picopilot.monitoring.model;

import com.picopilot.common.model.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/** Recurring PI re-scan of a workspace's databases. */
@Entity
@Table(name = "monitoring_schedule")
@Getter
@Setter
public class MonitoringSchedule extends TenantAwareEntity {

    public enum IntervalUnit { MINUTES, HOURS, DAYS, WEEKS, MONTHS }

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "interval_value", nullable = false)
    private int intervalValue = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "interval_unit", nullable = false, length = 10)
    private IntervalUnit intervalUnit = IntervalUnit.WEEKS;

    @Column(name = "value_pattern_scan", nullable = false)
    private boolean valuePatternScan;

    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "last_scan_id")
    private UUID lastScanId;

    /** The run after {@code from}; months follow the calendar (in UTC). */
    public Instant nextRunAfter(Instant from) {
        return switch (intervalUnit) {
            case MINUTES -> from.plusSeconds(60L * intervalValue);
            case HOURS -> from.plusSeconds(3600L * intervalValue);
            case DAYS -> from.plusSeconds(86400L * intervalValue);
            case WEEKS -> from.plusSeconds(7 * 86400L * intervalValue);
            case MONTHS -> from.atOffset(ZoneOffset.UTC).plusMonths(intervalValue).toInstant();
        };
    }
}
