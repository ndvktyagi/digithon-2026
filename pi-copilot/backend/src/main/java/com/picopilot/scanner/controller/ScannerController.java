package com.picopilot.scanner.controller;

import com.picopilot.common.dto.IdempotencyRequest;
import com.picopilot.scanner.model.ScanJob;
import com.picopilot.scanner.model.ScanScope;
import com.picopilot.scanner.service.ScannerService;
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
public class ScannerController {

    private final ScannerService scannerService;

    @PostMapping("/workspaces/{workspaceId}/projects/{projectId}/scans")
    @ResponseStatus(HttpStatus.CREATED)
    public ScanJob createScan(@PathVariable UUID workspaceId,
                               @PathVariable UUID projectId,
                               @RequestBody CreateScanRequest request,
                               @AuthenticationPrincipal Jwt jwt) {
        return scannerService.createScan(workspaceId, projectId,
            request.scanJob(), request.scanScope(), jwt.getSubject());
    }

    @PostMapping("/workspaces/{workspaceId}/scans/{scanId}/start")
    public ScanJob startScan(@PathVariable UUID workspaceId,
                              @PathVariable UUID scanId,
                              @AuthenticationPrincipal Jwt jwt) {
        return scannerService.startScan(workspaceId, scanId, jwt.getSubject());
    }

    @PostMapping("/workspaces/{workspaceId}/scans/{scanId}/cancel")
    public ScanJob cancelScan(@PathVariable UUID workspaceId,
                               @PathVariable UUID scanId,
                               @AuthenticationPrincipal Jwt jwt) {
        return scannerService.cancelScan(workspaceId, scanId, jwt.getSubject());
    }

    @GetMapping("/workspaces/{workspaceId}/scans/{scanId}")
    public ScanJob getScanStatus(@PathVariable UUID workspaceId,
                                   @PathVariable UUID scanId,
                                   @AuthenticationPrincipal Jwt jwt) {
        return scannerService.getScanStatus(workspaceId, scanId, jwt.getSubject());
    }

    @GetMapping("/workspaces/{workspaceId}/projects/{projectId}/scans")
    public List<ScanJob> listScans(@PathVariable UUID workspaceId,
                                     @PathVariable UUID projectId,
                                     @AuthenticationPrincipal Jwt jwt) {
        return scannerService.listScans(workspaceId, projectId, jwt.getSubject());
    }

    public record CreateScanRequest(ScanJob scanJob, ScanScope scanScope) {}
}