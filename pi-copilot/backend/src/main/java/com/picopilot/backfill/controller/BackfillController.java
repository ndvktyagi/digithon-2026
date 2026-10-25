package com.picopilot.backfill.controller;

import com.picopilot.backfill.model.BackfillBatch;
import com.picopilot.backfill.model.BackfillJob;
import com.picopilot.backfill.model.VerificationRun;
import com.picopilot.backfill.service.BackfillService;
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
public class BackfillController {

    private final BackfillService backfillService;

    @PostMapping("/workspaces/{workspaceId}/backfills")
    @ResponseStatus(HttpStatus.CREATED)
    public BackfillJob createJob(@PathVariable UUID workspaceId,
                                  @RequestBody BackfillJob job,
                                  @AuthenticationPrincipal Jwt jwt) {
        return backfillService.createJob(workspaceId, job, jwt.getSubject());
    }

    @PostMapping("/workspaces/{workspaceId}/backfills/{jobId}/start")
    public BackfillJob startJob(@PathVariable UUID workspaceId,
                                 @PathVariable UUID jobId,
                                 @AuthenticationPrincipal Jwt jwt) {
        return backfillService.startJob(workspaceId, jobId, jwt.getSubject());
    }

    @PostMapping("/workspaces/{workspaceId}/backfills/{jobId}/pause")
    public BackfillJob pauseJob(@PathVariable UUID workspaceId,
                                 @PathVariable UUID jobId,
                                 @AuthenticationPrincipal Jwt jwt) {
        return backfillService.pauseJob(workspaceId, jobId, jwt.getSubject());
    }

    @PostMapping("/workspaces/{workspaceId}/backfills/{jobId}/stop")
    public BackfillJob stopJob(@PathVariable UUID workspaceId,
                                @PathVariable UUID jobId,
                                @AuthenticationPrincipal Jwt jwt) {
        return backfillService.stopJob(workspaceId, jobId, jwt.getSubject());
    }

    @GetMapping("/workspaces/{workspaceId}/backfills/{jobId}")
    public BackfillJob getStatus(@PathVariable UUID workspaceId,
                                  @PathVariable UUID jobId,
                                  @AuthenticationPrincipal Jwt jwt) {
        return backfillService.getStatus(workspaceId, jobId, jwt.getSubject());
    }

    @GetMapping("/workspaces/{workspaceId}/backfills/{jobId}/batches")
    public List<BackfillBatch> getBatches(@PathVariable UUID workspaceId,
                                           @PathVariable UUID jobId,
                                           @AuthenticationPrincipal Jwt jwt) {
        return backfillService.getBatches(jobId);
    }

    @GetMapping("/workspaces/{workspaceId}/backfills/{jobId}/verifications")
    public List<VerificationRun> getVerifications(@PathVariable UUID workspaceId,
                                                    @PathVariable UUID jobId,
                                                    @AuthenticationPrincipal Jwt jwt) {
        return backfillService.getVerifications(jobId);
    }
}