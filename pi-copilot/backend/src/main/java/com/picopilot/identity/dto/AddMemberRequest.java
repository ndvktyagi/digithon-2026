package com.picopilot.identity.dto;

import com.picopilot.common.model.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record AddMemberRequest(
    @NotBlank String userId,
    @NotNull UserRole role
) {}
