package com.picopilot.migration.model;

import com.picopilot.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "approval_record")
@Getter
@Setter
public class ApprovalRecord extends BaseEntity {

    @Column(name = "target_type", nullable = false, length = 30)
    private String targetType; // MIGRATION_PLAN, RETIREMENT, FEATURE_FLAG

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(name = "approver_id", nullable = false, length = 100)
    private String approverId;

    @Column(name = "approver_name", length = 200)
    private String approverName;

    @Column(nullable = false, length = 20)
    private String action; // APPROVED, REJECTED, REQUESTED_CHANGES

    @Column(columnDefinition = "text")
    private String comment;
}