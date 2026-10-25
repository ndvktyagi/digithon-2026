package com.picopilot.implementation.controller;

import com.picopilot.implementation.service.FinalReviewService;
import com.picopilot.implementation.service.FinalReviewService.FileChangeView;
import com.picopilot.implementation.service.FinalReviewService.ReviewRequest;
import com.picopilot.implementation.service.ImplementationService;
import com.picopilot.implementation.service.ImplementationService.Readiness;
import com.picopilot.implementation.service.ImplementationService.RunView;
import com.picopilot.implementation.service.ImplementationService.StartRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/implementation")
@RequiredArgsConstructor
public class ImplementationController {

    private final ImplementationService implementationService;
    private final FinalReviewService finalReviewService;

    /** What the next run would use, and anything that blocks it. */
    @GetMapping("/readiness")
    public Readiness readiness(@PathVariable UUID workspaceId, @AuthenticationPrincipal Jwt jwt) {
        return implementationService.readiness(workspaceId, jwt.getSubject());
    }

    @GetMapping("/runs")
    public List<RunView> list(@PathVariable UUID workspaceId, @AuthenticationPrincipal Jwt jwt) {
        return implementationService.list(workspaceId, jwt.getSubject());
    }

    @GetMapping("/runs/{runId}")
    public RunView get(@PathVariable UUID workspaceId, @PathVariable UUID runId, @AuthenticationPrincipal Jwt jwt) {
        return implementationService.get(workspaceId, runId, jwt.getSubject());
    }

    /** Files a round changed, with base / previous / new content for the review diff. */
    @GetMapping("/runs/{runId}/files")
    public List<FileChangeView> files(@PathVariable UUID workspaceId, @PathVariable UUID runId,
                                      @AuthenticationPrincipal Jwt jwt) {
        return finalReviewService.files(workspaceId, runId, jwt.getSubject());
    }

    /** Final Review decision on the latest round: APPROVE, REJECT or REQUEST_CHANGES. */
    @PostMapping("/runs/{runId}/review")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void review(@PathVariable UUID workspaceId, @PathVariable UUID runId,
                       @RequestBody ReviewRequest request, @AuthenticationPrincipal Jwt jwt) {
        finalReviewService.review(workspaceId, runId, request, jwt.getSubject(), jwt.getClaimAsString("preferred_username"));
    }

    @PostMapping("/runs/{runId}/merge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void merge(@PathVariable UUID workspaceId, @PathVariable UUID runId, @AuthenticationPrincipal Jwt jwt) {
        finalReviewService.merge(workspaceId, runId, jwt.getSubject());
    }

    @PostMapping("/runs/{runId}/retry")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retry(@PathVariable UUID workspaceId, @PathVariable UUID runId, @AuthenticationPrincipal Jwt jwt) {
        finalReviewService.retry(workspaceId, runId, jwt.getSubject());
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    public RunView start(@PathVariable UUID workspaceId,
                         @RequestBody(required = false) StartRequest request,
                         @AuthenticationPrincipal Jwt jwt) {
        return implementationService.start(workspaceId, request, jwt.getSubject());
    }
}
