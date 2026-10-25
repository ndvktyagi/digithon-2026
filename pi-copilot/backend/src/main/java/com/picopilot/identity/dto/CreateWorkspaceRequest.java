package com.picopilot.identity.dto;

import com.picopilot.common.model.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateWorkspaceRequest(
    @NotBlank @Size(max = 100) String name,
    @NotBlank @Size(max = 50) String slug,
    @Size(max = 500) String description
) {}
