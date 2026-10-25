package com.picopilot.audit.model;

import com.picopilot.common.model.BaseEntity;
import com.picopilot.common.model.Severity;
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
@Table(name = "audit_event")
@Getter
@Setter
public class AuditEvent extends BaseEntity {

    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(nullable = false, length = 100)
    private String actor;

    @Column(name = "actor_role", length = 30)
    private String actorRole;

    @Column(nullable = false, length = 100)
    private String action; // SCAN_STARTED, FINDING_REVIEWED, KEY_ROTATED, etc.

    @Column(nullable = false, length = 50)
    private String targetType; // SCAN_JOB, PI_FINDING, MIGRATION_PLAN, FEATURE_FLAG, etc.

    @Column(nullable = false)
    private String targetId;

    @Column(name = "target_name", length = 300)
    private String targetName;

    @Column(nullable = false, length = 20)
    private String outcome; // SUCCESS, FAILURE, ATTEMPT

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Severity severity;

    @Column(name = "correlation_id", length = 50)
    private String correlationId;

    @Column(name = "job_id")
    private UUID jobId;

    @JdbcTypeCode(SqlTypes.JSON)

    @Column(name = "event_metadata", columnDefinition = "jsonb")
    private String eventMetadata;

    @Column(name = "source_ip", length = 50)
    private String sourceIp;

    @Column(name = "event_time", nullable = false)
    private Instant eventTime = Instant.now();
}