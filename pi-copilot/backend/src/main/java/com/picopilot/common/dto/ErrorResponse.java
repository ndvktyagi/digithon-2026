package com.picopilot.common.dto;

import lombok.Builder;

import java.time.Instant;

@Builder
public record ErrorResponse(
    int status,
    String error,
    String message,
    String path,
    Instant timestamp,
    String correlationId
) {}