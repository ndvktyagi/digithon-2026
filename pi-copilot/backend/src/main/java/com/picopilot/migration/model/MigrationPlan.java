package com.picopilot.migration.model;

import com.picopilot.common.model.MigrationState;
import com.picopilot.common.model.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.JdbcTypeCode;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "migration_plan")
@Getter
@Setter
public class MigrationPlan extends TenantAwareEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "provider_config_id")
    private UUID providerConfigId;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MigrationState state = MigrationState.PREVIEW;

    @Column(name = "ddl_script", columnDefinition = "text")
    private String ddlScript;

    @Column(name = "rollback_ddl", columnDefinition = "text")
    private String rollbackDdl;

    @JdbcTypeCode(SqlTypes.JSON)

    @Column(name = "impacted_tables", columnDefinition = "jsonb")
    private String impactedTables;

    @JdbcTypeCode(SqlTypes.JSON)

    @Column(name = "impacted_queries", columnDefinition = "jsonb")
    private String impactedQueries;

    @Column(name = "estimated_row_count")
    private Long estimatedRowCount;

    @Column(name = "test_plan", columnDefinition = "text")
    private String testPlan;

    @Column(name = "rollback_plan", columnDefinition = "text")
    private String rollbackPlan;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "pr_branch_name", length = 200)
    private String prBranchName;

    @Column(name = "pr_number")
    private Integer prNumber;

    @Column(name = "pr_url", length = 500)
    private String prUrl;

    @Column(name = "feature_flag_name", length = 100)
    private String featureFlagName;
}