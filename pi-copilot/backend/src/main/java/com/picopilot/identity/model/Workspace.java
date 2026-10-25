package com.picopilot.identity.model;

import com.picopilot.common.model.BaseEntity;
import com.picopilot.common.model.WorkspaceStage;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "workspace")
@Getter
@Setter
public class Workspace extends BaseEntity {

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(nullable = false, unique = true, length = 50)
    private String slug;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_stage", nullable = false, length = 30)
    private WorkspaceStage lifecycleStage = WorkspaceStage.CONNECT;

    @Column(name = "stage_updated_at")
    private Instant stageUpdatedAt;
}