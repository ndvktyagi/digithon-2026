package com.picopilot.migration.model;

import com.picopilot.common.model.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "generated_patch")
@Getter
@Setter
public class GeneratedPatch extends TenantAwareEntity {

    @Column(name = "migration_plan_id", nullable = false)
    private UUID migrationPlanId;

    @Column(name = "file_path", nullable = false, length = 500)
    private String filePath;

    @Column(nullable = false, length = 30)
    private String patchType; // ADD_COLUMN, NEW_METHOD, CONFIG, TEST, DDL

    @Column(name = "original_content", columnDefinition = "text")
    private String originalContent;

    @Column(name = "patched_content", columnDefinition = "text")
    private String patchedContent;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "is_manual")
    private boolean isManual = false; // true if needs human intervention
}