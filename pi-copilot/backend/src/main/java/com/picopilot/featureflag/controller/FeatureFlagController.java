package com.picopilot.featureflag.controller;

import com.picopilot.featureflag.model.FeatureFlag;
import com.picopilot.featureflag.service.EncryptionSwitchService;
import com.picopilot.featureflag.service.FeatureFlagService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class FeatureFlagController {

    private final FeatureFlagService flagService;
    private final EncryptionSwitchService switchService;

    @GetMapping("/workspaces/{workspaceId}/projects/{projectId}/feature-flags")
    public List<FeatureFlag> listFlags(@PathVariable UUID workspaceId,
                                         @PathVariable UUID projectId,
                                         @AuthenticationPrincipal Jwt jwt) {
        return flagService.listFlags(workspaceId, projectId, jwt.getSubject());
    }

    @PostMapping("/workspaces/{workspaceId}/feature-flags")
    @ResponseStatus(HttpStatus.CREATED)
    public FeatureFlag createFlag(@PathVariable UUID workspaceId,
                                    @RequestBody FeatureFlag flag,
                                    @AuthenticationPrincipal Jwt jwt) {
        return flagService.createFlag(workspaceId, flag, jwt.getSubject());
    }

    @PostMapping("/workspaces/{workspaceId}/feature-flags/{flagId}/enable")
    public FeatureFlag enableFlag(@PathVariable UUID workspaceId,
                                    @PathVariable UUID flagId,
                                    @AuthenticationPrincipal Jwt jwt) {
        return flagService.enableFlag(workspaceId, flagId, jwt.getSubject());
    }

    @PostMapping("/workspaces/{workspaceId}/feature-flags/{flagId}/rollback")
    public FeatureFlag rollbackFlag(@PathVariable UUID workspaceId,
                                      @PathVariable UUID flagId,
                                      @RequestBody RollbackRequest request,
                                      @AuthenticationPrincipal Jwt jwt) {
        return flagService.rollbackFlag(workspaceId, flagId, jwt.getSubject(), request.reason());
    }

    public record RollbackRequest(String reason) {}

    @GetMapping("/workspaces/{workspaceId}/encryption-switch")
    public EncryptionSwitchService.Status encryptionSwitch(@PathVariable UUID workspaceId,
                                                           @AuthenticationPrincipal Jwt jwt) {
        return switchService.status(workspaceId, jwt.getSubject());
    }

    @PutMapping("/workspaces/{workspaceId}/encryption-switch")
    public EncryptionSwitchService.Status setEncryptionSwitch(@PathVariable UUID workspaceId,
                                                              @RequestBody SwitchRequest request,
                                                              @AuthenticationPrincipal Jwt jwt) {
        return switchService.setEnabled(workspaceId, request.enabled(), request.reason(), jwt.getSubject());
    }

    public record SwitchRequest(boolean enabled, String reason) {}
}