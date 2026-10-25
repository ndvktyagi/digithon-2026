package com.picopilot.common.model;

/**
 * Ordered lifecycle a workspace moves through, from connecting sources to
 * removing the plain-text columns and legacy code once encryption is live.
 */
public enum WorkspaceStage {
    CONNECT,
    SCAN,
    REVIEW,
    ENCRYPTION_SETUP,
    DATA_SYNC,
    ENABLE_ENCRYPTION,
    FINALIZE,
    COMPLETED;

    public WorkspaceStage next() {
        WorkspaceStage[] all = values();
        return this == COMPLETED ? COMPLETED : all[ordinal() + 1];
    }
}
