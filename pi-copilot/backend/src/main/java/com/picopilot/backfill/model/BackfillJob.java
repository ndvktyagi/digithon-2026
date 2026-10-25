package com.picopilot.backfill.model;

import com.picopilot.common.model.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.JdbcTypeCode;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "backfill_job")
@Getter
@Setter
public class BackfillJob extends TenantAwareEntity {

    @Column(name = "migration_plan_id", nullable = false)
    private UUID migrationPlanId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(nullable = false, length = 30)
    private String status; // PENDING, RUNNING, PAUSED, COMPLETED, FAILED, STOPPED

    @Column(name = "total_rows")
    private Long totalRows;

    @Column(name = "processed_rows")
    private Long processedRows;

    @Column(name = "failed_rows")
    private Long failedRows;

    @JdbcTypeCode(SqlTypes.JSON)

    @Column(name = "current_checkpoint", columnDefinition = "jsonb")
    private String currentCheckpoint;

    @Column(name = "batch_size")
    private int batchSize = 1000;

    @Column(name = "max_retries")
    private int maxRetries = 3;

    @Column(name = "retry_count")
    private int retryCount;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "idempotency_key", unique = true, length = 100)
    private String idempotencyKey;

    @Column(name = "worker_node", length = 100)
    private String workerNode;
}