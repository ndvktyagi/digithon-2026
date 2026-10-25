package com.picopilot.identity.dto;

import com.picopilot.common.model.WorkspaceStage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WorkspaceLifecycleResponse(
    UUID workspaceId,
    WorkspaceStage currentStage,
    Instant stageUpdatedAt,
    List<StageStatus> stages
) {}
