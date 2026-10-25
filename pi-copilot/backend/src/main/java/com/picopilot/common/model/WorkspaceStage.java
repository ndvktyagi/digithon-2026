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
    IMPLEMENTATION,
    FINAL_REVIEW,
    DATA_SYNC,
    ENABLE_ENCRYPTION,
    FINALIZE,
    COMPLETED,
    /** Terminal: the implementation was rejected in Final Review. */
    REJECTED;

    public boolean isTerminal() {
        return this == COMPLETED || this == REJECTED;
    }

    public WorkspaceStage next() {
        return switch (this) {
            case FINALIZE -> COMPLETED;
            case COMPLETED, REJECTED -> this;
            default -> values()[ordinal() + 1];
        };
    }
}
