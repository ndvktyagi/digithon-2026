package com.picopilot.featureflag.model;

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
@Table(name = "feature_flag")
@Getter
@Setter
public class FeatureFlag extends TenantAwareEntity {

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "migration_plan_id")
    private UUID migrationPlanId;

    @Column(nullable = false, unique = true, length = 100)
    private String flagName;

    @Column(length = 500)
    private String description;

    @Column(nullable = false, length = 30)
    private String scope; // APPLICATION, TABLE, FIELD, ENVIRONMENT

    @Column(name = "scope_target", length = 200)
    private String scopeTarget; // specific table/field name

    @Column(name = "current_state", nullable = false, length = 20)
    private String currentState; // DISABLED, DUAL_WRITE, ENCRYPTED_READ, LEGACY_READ, RETIRED

    @Column(name = "target_state", length = 20)
    private String targetState;

    @Column(name = "rollback_window_hours")
    private int rollbackWindowHours = 72;

    @JdbcTypeCode(SqlTypes.JSON)

    @Column(name = "readiness_checks", columnDefinition = "jsonb")
    private String readinessChecks;

    @Column(name = "enabled_at")
    private Instant enabledAt;

    @Column(name = "enabled_by", length = 100)
    private String enabledBy;

    @Column(name = "rollback_at")
    private Instant rollbackAt;

    @Column(name = "rollback_by", length = 100)
    private String rollbackBy;

    @Column(name = "rollback_reason", columnDefinition = "text")
    private String rollbackReason;

    @Column(name = "approval_required")
    private boolean approvalRequired = true;
}