package com.picopilot.identity.dto;

import com.picopilot.common.model.WorkspaceStage;

/**
 * @param state  DONE, CURRENT or UPCOMING relative to the workspace's current stage
 * @param ready  whether the stage's exit criteria are met by workspace data
 * @param detail human-readable progress, e.g. "2 verified repositories"
 */
public record StageStatus(
    WorkspaceStage stage,
    String state,
    boolean ready,
    String detail
) {}
