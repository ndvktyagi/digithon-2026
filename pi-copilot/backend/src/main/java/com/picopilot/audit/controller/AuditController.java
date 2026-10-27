package com.picopilot.audit.controller;

import com.picopilot.audit.model.AuditEvent;
import com.picopilot.audit.service.AuditService;
import com.picopilot.common.dto.PagedResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    @GetMapping("/workspaces/{workspaceId}/audit-events")
    public PagedResponse<AuditEvent> getWorkspaceEvents(
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal Jwt jwt,
            Pageable pageable) {
        Page<AuditEvent> page = auditService.getWorkspaceEvents(
            workspaceId, jwt.getSubject(), pageable);
        return PagedResponse.of(page.getContent(), page);
    }

    @GetMapping("/workspaces/{workspaceId}/projects/{projectId}/audit-events")
    public PagedResponse<AuditEvent> getProjectEvents(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @AuthenticationPrincipal Jwt jwt,
            Pageable pageable) {
        Page<AuditEvent> page = auditService.getProjectEvents(
            workspaceId, projectId, jwt.getSubject(), pageable);
        return PagedResponse.of(page.getContent(), page);
    }

    @GetMapping("/workspaces/{workspaceId}/audit-events/search")
    public PagedResponse<AuditEvent> searchEvents(
            @PathVariable UUID workspaceId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @AuthenticationPrincipal Jwt jwt,
            Pageable pageable) {
        Page<AuditEvent> page;
        if (from != null && to != null) {
            page = auditService.getEventsByTimeRange(workspaceId, from, to,
                jwt.getSubject(), pageable);
        } else {
            page = auditService.search(workspaceId, action, outcome, jwt.getSubject(), pageable);
        }
        return PagedResponse.of(page.getContent(), page);
    }

    /** Actions recorded in this workspace, for the filter list. */
    @GetMapping("/workspaces/{workspaceId}/audit-events/actions")
    public List<String> actions(@PathVariable UUID workspaceId, @AuthenticationPrincipal Jwt jwt) {
        return auditService.actions(workspaceId, jwt.getSubject());
    }

    @GetMapping("/workspaces/{workspaceId}/reports/audit")
    public String exportAuditReport(@PathVariable UUID workspaceId,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                     @AuthenticationPrincipal Jwt jwt) {
        // Export as CSV or human-readable report - no raw PI/secrets
        return "{" +
            "\"workspaceId\": \"" + workspaceId + "\"," +
            "\"generatedAt\": \"" + Instant.now() + "\"," +
            "\"format\": \"csv\"," +
            "\"status\": \"implementing\"" +
            "}";
    }
}