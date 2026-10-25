package com.picopilot.identity.dto;

/**
 * @param confirmed required for FINALIZE, where the user signs off that legacy
 *                  plain-text code paths and columns have been removed
 */
public record AdvanceStageRequest(boolean confirmed) {}
