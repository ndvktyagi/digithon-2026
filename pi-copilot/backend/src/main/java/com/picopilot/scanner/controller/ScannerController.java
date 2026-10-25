package com.picopilot.scanner.controller;

import com.picopilot.inventory.model.PiFinding;
import com.picopilot.scanner.service.ScannerService;
import com.picopilot.scanner.service.ScannerService.CreateScanRequest;
import com.picopilot.scanner.service.ScannerService.ScanView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/scans")
@RequiredArgsConstructor
public class ScannerController {

    private final ScannerService scannerService;

    /** Creates a scan over the given databases (in execution order); starts it unless start=false. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ScanView createScan(@PathVariable UUID workspaceId,
                               @RequestBody CreateScanRequest request,
                               @AuthenticationPrincipal Jwt jwt) {
        return scannerService.create(workspaceId, request, jwt.getSubject());
    }

    @GetMapping
    public List<ScanView> listScans(@PathVariable UUID workspaceId,
                                    @AuthenticationPrincipal Jwt jwt) {
        return scannerService.listScans(workspaceId, jwt.getSubject());
    }

    @GetMapping("/{scanId}")
    public ScanView getScan(@PathVariable UUID workspaceId,
                            @PathVariable UUID scanId,
                            @AuthenticationPrincipal Jwt jwt) {
        return scannerService.getScan(workspaceId, scanId, jwt.getSubject());
    }

    /** Starts an Initiated scan, optionally with a new execution order of its targets. */
    @PostMapping("/{scanId}/start")
    public ScanView startScan(@PathVariable UUID workspaceId,
                              @PathVariable UUID scanId,
                              @RequestBody(required = false) StartScanRequest request,
                              @AuthenticationPrincipal Jwt jwt) {
        return scannerService.start(workspaceId, scanId,
            request == null ? null : request.targetOrder(), jwt.getSubject());
    }

    @PostMapping("/{scanId}/cancel")
    public ScanView cancelScan(@PathVariable UUID workspaceId,
                               @PathVariable UUID scanId,
                               @AuthenticationPrincipal Jwt jwt) {
        return scannerService.cancelScan(workspaceId, scanId, jwt.getSubject());
    }

    @GetMapping("/{scanId}/findings")
    public List<PiFinding> listFindings(@PathVariable UUID workspaceId,
                                        @PathVariable UUID scanId,
                                        @AuthenticationPrincipal Jwt jwt) {
        return scannerService.listFindings(workspaceId, scanId, jwt.getSubject());
    }

    @PostMapping("/{scanId}/approve")
    public ScanView approveScan(@PathVariable UUID workspaceId,
                                @PathVariable UUID scanId,
                                @RequestBody ApproveScanRequest request,
                                @AuthenticationPrincipal Jwt jwt) {
        return scannerService.approve(workspaceId, scanId, request.findingIds(), request.comment(),
            jwt.getSubject(), jwt.getClaimAsString("preferred_username"));
    }

    public record ApproveScanRequest(List<UUID> findingIds, String comment) {}

    public record StartScanRequest(List<UUID> targetOrder) {}
}
