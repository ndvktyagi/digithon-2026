package com.picopilot.scanner.model;

import com.picopilot.common.model.ScanStatus;
import com.picopilot.common.model.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One connector scanned as part of a scan job, run in {@link #executionOrder}. */
@Entity
@Table(name = "scan_target")
@Getter
@Setter
public class ScanTarget extends TenantAwareEntity {

    @Column(name = "scan_job_id", nullable = false)
    private UUID scanJobId;

    @Column(name = "connector_id")
    private UUID connectorId;

    @Column(name = "connector_type", nullable = false, length = 30)
    private String connectorType; // POSTGRESQL

    /** Snapshot of the connector's display name, e.g. "db.example.com:5432/orders". */
    @Column(nullable = false, length = 300)
    private String label;

    @Column(name = "execution_order", nullable = false)
    private int executionOrder;

    /** Comma-separated schemas to scan; null scans all non-system schemas. */
    @Column(length = 500)
    private String schemas;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScanStatus status = ScanStatus.PENDING;

    @Column(name = "tables_scanned", nullable = false)
    private int tablesScanned;

    @Column(name = "columns_scanned", nullable = false)
    private int columnsScanned;

    @Column(name = "findings_count", nullable = false)
    private int findingsCount;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;
}
