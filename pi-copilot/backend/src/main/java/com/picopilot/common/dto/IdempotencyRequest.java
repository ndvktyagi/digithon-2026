package com.picopilot.common.dto;

public record IdempotencyRequest(
    String idempotencyKey
) {}