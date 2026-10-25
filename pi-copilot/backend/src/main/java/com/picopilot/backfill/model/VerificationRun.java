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
@Table(name = "verification_run")
@Getter
@Setter
public class VerificationRun extends BaseEntity {

    @Column(name = "backfill_job_id", nullable = false)
    private UUID backfillJobId;

    @Column(nullable = false, length = 20)
    private String status; // RUNNING, PASSED, FAILED

    @Column(name = "rows_checked")
    private Long rowsChecked;

    @Column(name = "decrypt_success_count")
    private Long decryptSuccessCount;

    @Column(name = "decrypt_fail_count")
    private Long decryptFailCount;

    @Column(name = "row_count_match")
    private boolean rowCountMatch;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @JdbcTypeCode(SqlTypes.JSON)

    @Column(name = "result_detail", columnDefinition = "jsonb")
    private String resultDetail;
}