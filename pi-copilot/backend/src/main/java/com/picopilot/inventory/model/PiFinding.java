package com.picopilot.inventory.model;

import com.picopilot.common.model.Confidence;
import com.picopilot.common.model.FindingStatus;
import com.picopilot.common.model.PiCategory;
import com.picopilot.common.model.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "pi_finding")
@Getter
@Setter
public class PiFinding extends TenantAwareEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "scan_job_id", nullable = false)
    private UUID scanJobId;

    @Column(name = "db_schema", length = 100)
    private String dbSchema;

    @Column(name = "table_name", length = 200)
    private String tableName;

    @Column(name = "column_name", length = 200)
    private String columnName;

    @Column(name = "data_type", length = 100)
    private String dataType;

    @Column(nullable = false)
    private boolean nullable;

    @Column(name = "is_primary_key")
    private boolean isPrimaryKey;

    @Column(name = "is_foreign_key")
    private boolean isForeignKey;

    @Column(name = "foreign_key_reference", length = 500)
    private String foreignKeyReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "candidate_category", length = 50)
    private PiCategory candidateCategory;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Confidence confidence;

    @Column(name = "detection_method", length = 100)
    private String detectionMethod; // RULE, AST, AI, COMBINED

    @Column(name = "detector_rule", length = 200)
    private String detectorRule;

    @Column(name = "reasoning", columnDefinition = "text")
    private String reasoning;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FindingStatus status = FindingStatus.DISCOVERED;

    @Column(name = "suggested_action", length = 50)
    private String suggestedAction; // ENCRYPT, TOKENIZE, MASK, DELETE, REVIEW

    @Column(name = "impact_summary", columnDefinition = "text")
    private String impactSummary;

    @Column(name = "reviewer_comment", columnDefinition = "text")
    private String reviewerComment;

    @Column(name = "reviewed_by", length = 100)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private java.time.Instant reviewedAt;

    @Column(name = "analysis_engine", length = 50)
    private String analysisEngine;

    @Column(name = "analysis_version", length = 50)
    private String analysisVersion;

    @Column(name = "field_order_hash", length = 64, unique = true)
    private String fieldOrderHash;

    @Column(name = "database_connector_id")
    private UUID databaseConnectorId;

    public static final String PLAIN = "PLAIN";
    public static final String ENCRYPTED = "ENCRYPTED";

    /** PLAIN PI, or ENCRYPTED when the column already holds ciphertext (e.g. an "_enc" twin). */
    @Column(name = "encryption_state", nullable = false, length = 20)
    private String encryptionState = PLAIN;

    /** For a plain column that already has an encrypted twin: the twin's name. */
    @Column(name = "encrypted_column", length = 200)
    private String encryptedColumn;

    /** Selected by the reviewer for encryption when the scan was approved. */
    @Column(nullable = false)
    private boolean approved;
}
