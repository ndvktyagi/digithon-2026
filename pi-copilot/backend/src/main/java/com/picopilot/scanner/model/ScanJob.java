package com.picopilot.scanner.model;

import com.picopilot.common.model.ScanStatus;
import com.picopilot.common.model.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "scan_job")
@Getter
@Setter
public class ScanJob extends TenantAwareEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScanStatus status = ScanStatus.PENDING;

    @Column(name = "scan_type", nullable = false, length = 30)
    private String scanType; // CODE, DATABASE, FULL

    @Column(name = "analysis_engine", length = 50)
    private String analysisEngine; // RULES_ONLY, AI_ASSISTED

    @Column(name = "analysis_version", length = 50)
    private String analysisVersion;

    @Column(name = "repository_commit", length = 40)
    private String repositoryCommit;

    @Column(name = "total_files_scanned")
    private Integer totalFilesScanned;

    @Column(name = "total_tables_scanned")
    private Integer totalTablesScanned;

    @Column(name = "findings_count")
    private Integer findingsCount;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "idempotency_key", unique = true, length = 100)
    private String idempotencyKey;

    @Column(name = "scope_snapshot", columnDefinition = "jsonb")
    private String scopeSnapshot;
}