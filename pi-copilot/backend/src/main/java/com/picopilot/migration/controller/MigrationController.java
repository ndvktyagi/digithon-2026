package com.picopilot.migration.controller;

import com.picopilot.migration.model.ApprovalRecord;
import com.picopilot.migration.model.GeneratedPatch;
import com.picopilot.migration.model.MigrationPlan;
import com.picopilot.migration.service.MigrationService;
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
public class MigrationController {

    private final MigrationService migrationService;

    @PostMapping("/workspaces/{workspaceId}/migration-plans")
    @ResponseStatus(HttpStatus.CREATED)
    public MigrationPlan createPlan(@PathVariable UUID workspaceId,
                                     @RequestBody MigrationPlan plan,
                                     @AuthenticationPrincipal Jwt jwt) {
        return migrationService.createPlan(workspaceId, plan, jwt.getSubject());
    }

    @GetMapping("/workspaces/{workspaceId}/migration-plans/{planId}")
    public MigrationPlan getPlan(@PathVariable UUID workspaceId,
                                  @PathVariable UUID planId,
                                  @AuthenticationPrincipal Jwt jwt) {
        return migrationService.getPlan(workspaceId, planId, jwt.getSubject());
    }

    @PostMapping("/workspaces/{workspaceId}/migration-plans/{planId}/approve")
    public MigrationPlan approvePlan(@PathVariable UUID workspaceId,
                                      @PathVariable UUID planId,
                                      @AuthenticationPrincipal Jwt jwt) {
        String userName = jwt.getClaimAsString("preferred_username");
        return migrationService.approvePlan(workspaceId, planId, jwt.getSubject(), userName);
    }

    @PostMapping("/workspaces/{workspaceId}/migration-plans/{planId}/patches")
    @ResponseStatus(HttpStatus.CREATED)
    public GeneratedPatch addPatch(@PathVariable UUID workspaceId,
                                     @PathVariable UUID planId,
                                     @RequestBody GeneratedPatch patch,
                                     @AuthenticationPrincipal Jwt jwt) {
        return migrationService.addPatch(workspaceId, planId, patch, jwt.getSubject());
    }

    @GetMapping("/workspaces/{workspaceId}/migration-plans/{planId}/patches")
    public List<GeneratedPatch> getPatches(@PathVariable UUID workspaceId,
                                            @PathVariable UUID planId,
                                            @AuthenticationPrincipal Jwt jwt) {
        return migrationService.getPatches(planId);
    }
}