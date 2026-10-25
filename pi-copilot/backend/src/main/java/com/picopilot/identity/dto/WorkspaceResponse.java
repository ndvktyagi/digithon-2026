package com.picopilot.identity.dto;

import com.picopilot.common.model.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record WorkspaceResponse(
    UUID id,
    String name,
    String slug,
    String description,
    boolean active,
    java.time.Instant createdAt,
    java.time.Instant updatedAt
) {}
