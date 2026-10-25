package com.picopilot.common.event;

import com.picopilot.common.model.WorkspaceStage;

import java.util.UUID;

/**
 * Published when work belonging to a lifecycle stage finishes (a scan completes, a scan is
 * approved, ...). The lifecycle listens and advances the workspace if that stage is now done.
 *
 * @param actor user id, or "system" for background work
 */
public record StageWorkCompletedEvent(UUID workspaceId, WorkspaceStage stage, String actor) {}
