package com.picopilot.backfill.model;

import com.picopilot.common.model.BaseEntity;
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
@Table(name = "backfill_batch")
@Getter
@Setter
public class BackfillBatch extends BaseEntity {

    @Column(name = "backfill_job_id", nullable = false)
    private UUID backfillJobId;

    @Column(nullable = false)
    private int batchNumber;

    @JdbcTypeCode(SqlTypes.JSON)

    @Column(name = "start_range", columnDefinition = "jsonb")
    private String startRange;

    @JdbcTypeCode(SqlTypes.JSON)

    @Column(name = "end_range", columnDefinition = "jsonb")
    private String endRange;

    @Column(name = "row_count")
    private int rowCount;

    @Column(name = "processed_count")
    private int processedCount;

    @Column(name = "error_count")
    private int errorCount;

    @Column(nullable = false, length = 20)
    private String status; // PENDING, PROCESSING, COMPLETED, FAILED

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @JdbcTypeCode(SqlTypes.JSON)

    @Column(name = "error_details", columnDefinition = "jsonb")
    private String errorDetails;

    @Column(name = "retry_count")
    private int retryCount = 0;
}