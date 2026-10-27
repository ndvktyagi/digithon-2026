package com.picopilot.implementation.controller;

import com.picopilot.implementation.service.FinalizeService;
import com.picopilot.implementation.service.FinalizeService.Status;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/finalize")
@RequiredArgsConstructor
public class FinalizeController {

    private final FinalizeService finalizeService;

    /** @param acceptQueryWarnings continue although some repository queries can no longer match encrypted values */
    public record SucceededRequest(boolean acceptQueryWarnings) {}

    /** @param dropEncryptedColumns also drop the {@code _enc} columns after closing the release pull request */
    public record FailedRequest(boolean dropEncryptedColumns) {}

    /** @param deployed the cleaned-up application is deployed, so the plain columns are no longer used */
    public record DropRequest(boolean deployed) {}

    @GetMapping
    public Status status(@PathVariable UUID workspaceId, @AuthenticationPrincipal Jwt jwt) {
        return finalizeService.status(workspaceId, jwt.getSubject());
    }

    /** Release succeeded: push the cleanup to the release branch and merge it (or retry the merge). */
    @PostMapping("/release-succeeded")
    public Status releaseSucceeded(@PathVariable UUID workspaceId, @RequestBody(required = false) SucceededRequest request,
                                   @AuthenticationPrincipal Jwt jwt) {
        return finalizeService.releaseSucceeded(workspaceId, request != null && request.acceptQueryWarnings(), jwt.getSubject());
    }

    /** Release failed: close the release pull request, optionally drop the encrypted columns, and complete. */
    @PostMapping("/release-failed")
    public Status releaseFailed(@PathVariable UUID workspaceId, @RequestBody(required = false) FailedRequest request,
                                @AuthenticationPrincipal Jwt jwt) {
        return finalizeService.releaseFailed(workspaceId, request != null && request.dropEncryptedColumns(), jwt.getSubject());
    }

    /** After a successful release: drop the plain columns and complete the workspace lifecycle. */
    @PostMapping("/columns")
    public Status dropColumns(@PathVariable UUID workspaceId, @RequestBody(required = false) DropRequest request,
                              @AuthenticationPrincipal Jwt jwt) {
        return finalizeService.dropColumns(workspaceId, request != null && request.deployed(), jwt.getSubject());
    }
}
