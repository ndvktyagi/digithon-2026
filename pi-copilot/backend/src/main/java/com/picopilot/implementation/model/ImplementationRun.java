package com.picopilot.implementation.model;

import com.picopilot.common.model.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One attempt at the Encryption Implementation stage (database columns + code branch). */
@Entity
@Table(name = "implementation_run")
@Getter
@Setter
public class ImplementationRun extends TenantAwareEntity {

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";
    /** A review round created by "Request changes"; it runs only once the user clicks Run again. */
    public static final String WAITING = "WAITING";

    // Final Review states of a completed run
    public static final String PENDING_REVIEW = "PENDING_REVIEW";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String CHANGES_REQUESTED = "CHANGES_REQUESTED";

    @Column(name = "scan_job_id", nullable = false)
    private UUID scanJobId;

    @Column(name = "provider_config_id")
    private UUID providerConfigId;

    @Column(name = "repository_connector_id")
    private UUID repositoryConnectorId;

    @Column(nullable = false, length = 20)
    private String status = PENDING;

    @Column(name = "db_status", nullable = false, length = 20)
    private String dbStatus = PENDING;

    @Column(name = "code_status", nullable = false, length = 20)
    private String codeStatus = PENDING;

    /** "owner/name" of the repository changed. */
    @Column(length = 300)
    private String repository;

    @Column(name = "base_branch", length = 150)
    private String baseBranch;

    @Column(name = "branch_name", length = 200)
    private String branchName;

    @Column(name = "commit_sha", length = 64)
    private String commitSha;

    @Column(name = "compare_url", length = 500)
    private String compareUrl;

    @Column(name = "ddl_script", columnDefinition = "TEXT")
    private String ddlScript;

    /** JSON array of the encrypted columns: database, schema, table, column, encryptedColumn, codeStatus. */
    @Column(name = "columns_json", columnDefinition = "TEXT")
    private String columnsJson;

    /** Newline-separated "ADDED|MODIFIED path" entries. */
    @Column(name = "changed_files", columnDefinition = "TEXT")
    private String changedFiles;

    /** Newline-separated progress log shown to the user. */
    @Column(columnDefinition = "TEXT")
    private String log;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    /** Counts up across all runs of the workspace; review rounds (parentRunId set) revise the same branch. */
    @Column(name = "round_number", nullable = false)
    private int roundNumber = 1;

    @Column(name = "parent_run_id")
    private UUID parentRunId;

    /** Reviewer comment this round was created to resolve (rounds after the first). */
    @Column(name = "improvement_request", columnDefinition = "TEXT")
    private String improvementRequest;

    /** What the AI changed to resolve the comment. */
    @Column(name = "ai_summary", columnDefinition = "TEXT")
    private String aiSummary;

    @Column(name = "review_status", length = 20)
    private String reviewStatus;

    @Column(name = "review_comment", columnDefinition = "TEXT")
    private String reviewComment;

    @Column(name = "reviewed_by", length = 100)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "pr_number")
    private Integer prNumber;

    @Column(name = "pr_url", length = 500)
    private String prUrl;

    public static final String MERGED = "MERGED";

    /** After approval: MERGED into the base branch, or FAILED (merge_message says why; it can be retried). */
    @Column(name = "merge_status", length = 20)
    private String mergeStatus;

    @Column(name = "merge_sha", length = 64)
    private String mergeSha;

    @Column(name = "merge_message", columnDefinition = "TEXT")
    private String mergeMessage;

    @Column(name = "merged_at")
    private Instant mergedAt;
}
