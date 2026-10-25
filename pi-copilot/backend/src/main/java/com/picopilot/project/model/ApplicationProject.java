package com.picopilot.project.model;

import com.picopilot.common.model.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "application_project")
@Getter
@Setter
public class ApplicationProject extends TenantAwareEntity {

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "target_language", length = 50)
    private String targetLanguage;

    @Column(name = "database_type", length = 50)
    private String databaseType;

    @Column(name = "default_branch", length = 100)
    private String defaultBranch;

    @Column(length = 30)
    private String status = "ACTIVE";
}