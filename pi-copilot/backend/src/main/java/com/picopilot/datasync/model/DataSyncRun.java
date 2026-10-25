package com.picopilot.datasync.model;

import com.picopilot.common.model.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One Data Sync run: encrypt existing plain values into the _enc columns, then verify them. */
@Entity
@Table(name = "data_sync_run")
@Getter
@Setter
public class DataSyncRun extends TenantAwareEntity {

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";

    @Column(name = "implementation_run_id", nullable = false)
    private UUID implementationRunId;

    @Column(nullable = false, length = 20)
    private String status = PENDING;

    /** ENCRYPTING or VERIFYING while running. */
    @Column(length = 20)
    private String phase;

    @Column(name = "rows_encrypted", nullable = false)
    private long rowsEncrypted;

    @Column(name = "values_encrypted", nullable = false)
    private long valuesEncrypted;

    @Column(name = "values_verified", nullable = false)
    private long valuesVerified;

    @Column(name = "verify_failures", nullable = false)
    private long verifyFailures;

    /** Rows whose PI values all decrypt back to the plain values. */
    @Column(name = "rows_verified", nullable = false)
    private long rowsVerified;

    @Column(name = "rows_failed", nullable = false)
    private long rowsFailed;

    /** JSON per-table result: database, schema, table, rows, per-column encrypted / verified counts. */
    @Column(name = "tables_json", columnDefinition = "TEXT")
    private String tablesJson;

    @Column(columnDefinition = "TEXT")
    private String log;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;
}
