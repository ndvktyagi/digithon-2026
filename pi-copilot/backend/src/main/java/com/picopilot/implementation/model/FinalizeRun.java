package com.picopilot.implementation.model;

import com.picopilot.common.model.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Finalize of an approved implementation: first a merged pull request that removes the plain-text code paths,
 * then the plain-text columns are dropped from the target databases.
 */
@Entity
@Table(name = "finalize_run")
@Getter
@Setter
public class FinalizeRun extends TenantAwareEntity {

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String MERGED = "MERGED";
    public static final String DROPPED = "DROPPED";
    /** The encrypted columns were left in place after a failed release. */
    public static final String KEPT = "KEPT";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String CLOSED = "CLOSED";

    @Column(name = "implementation_run_id", nullable = false)
    private UUID implementationRunId;

    /** How the release of the approved branch went: SUCCEEDED or FAILED, as the user reported it. */
    @Column(name = "release_outcome", length = 20)
    private String releaseOutcome;

    /** Cleanup pull request: PENDING, RUNNING, COMPLETED (pushed) or FAILED. */
    @Column(name = "code_status", nullable = false, length = 20)
    private String codeStatus = PENDING;

    @Column(name = "branch_name")
    private String branchName;

    @Column(name = "commit_sha", length = 64)
    private String commitSha;

    @Column(name = "pr_number")
    private Integer prNumber;

    @Column(name = "pr_url", length = 500)
    private String prUrl;

    /** MERGED or FAILED once a merge was attempted. */
    @Column(name = "merge_status", length = 20)
    private String mergeStatus;

    @Column(name = "merge_sha", length = 64)
    private String mergeSha;

    @Column(name = "merge_message", columnDefinition = "TEXT")
    private String mergeMessage;

    /** One "ADDED|path", "MODIFIED|path" or "DELETED|path" per line. */
    @Column(name = "changed_files", columnDefinition = "TEXT")
    private String changedFiles;

    /** Queries that still run but can no longer match encrypted values, one per line. */
    @Column(columnDefinition = "TEXT")
    private String warnings;

    /** Plain columns: PENDING, DROPPED or FAILED. */
    @Column(name = "columns_status", nullable = false, length = 20)
    private String columnsStatus = PENDING;

    /** "schema.table.column" per line. */
    @Column(name = "dropped_columns", columnDefinition = "TEXT")
    private String droppedColumns;

    @Column(name = "dropped_at")
    private Instant droppedAt;

    @Column(name = "dropped_by", length = 100)
    private String droppedBy;

    @Column(columnDefinition = "TEXT")
    private String log;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;
}
