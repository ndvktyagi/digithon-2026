package com.picopilot.identity.dto;

import com.picopilot.common.model.WorkspaceStage;
import jakarta.validation.constraints.NotNull;

public record ReopenStageRequest(@NotNull WorkspaceStage stage) {}
