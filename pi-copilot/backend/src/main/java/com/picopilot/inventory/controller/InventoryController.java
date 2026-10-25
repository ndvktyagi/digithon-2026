package com.picopilot.inventory.controller;

import com.picopilot.common.dto.PagedResponse;
import com.picopilot.common.model.Confidence;
import com.picopilot.common.model.FindingStatus;
import com.picopilot.common.model.PiCategory;
import com.picopilot.common.model.ReviewDecision;
import com.picopilot.inventory.model.FindingEvidence;
import com.picopilot.inventory.model.FindingRelationship;
import com.picopilot.inventory.model.FindingReview;
import com.picopilot.inventory.model.PiFinding;
import com.picopilot.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @GetMapping("/workspaces/{workspaceId}/projects/{projectId}/findings")
    public PagedResponse<PiFinding> listFindings(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @RequestParam(required = false) FindingStatus status,
            @RequestParam(required = false) PiCategory category,
            @RequestParam(required = false) Confidence confidence,
            @RequestParam(required = false) String schema,
            @RequestParam(required = false) String table,
            @RequestParam(required = false) String search,
            @AuthenticationPrincipal Jwt jwt,
            Pageable pageable) {
        return inventoryService.listFindings(workspaceId, projectId,
            status, category, confidence, schema, table, search,
            jwt.getSubject(), pageable);
    }

    @GetMapping("/workspaces/{workspaceId}/findings/{findingId}")
    public PiFinding getFinding(@PathVariable UUID workspaceId,
                                 @PathVariable UUID findingId,
                                 @AuthenticationPrincipal Jwt jwt) {
        return inventoryService.getFindingDetail(workspaceId, findingId, jwt.getSubject());
    }

    @GetMapping("/workspaces/{workspaceId}/findings/{findingId}/evidence")
    public List<FindingEvidence> getEvidence(@PathVariable UUID workspaceId,
                                               @PathVariable UUID findingId,
                                               @AuthenticationPrincipal Jwt jwt) {
        return inventoryService.getEvidence(findingId);
    }

    @GetMapping("/workspaces/{workspaceId}/findings/{findingId}/relationships")
    public List<FindingRelationship> getRelationships(@PathVariable UUID workspaceId,
                                                       @PathVariable UUID findingId,
                                                       @AuthenticationPrincipal Jwt jwt) {
        return inventoryService.getRelationships(findingId);
    }

    @GetMapping("/workspaces/{workspaceId}/findings/{findingId}/reviews")
    public List<FindingReview> getReviewHistory(@PathVariable UUID workspaceId,
                                                  @PathVariable UUID findingId,
                                                  @AuthenticationPrincipal Jwt jwt) {
        return inventoryService.getReviewHistory(findingId);
    }

    @PostMapping("/workspaces/{workspaceId}/findings/{findingId}/review")
    public FindingReview reviewFinding(@PathVariable UUID workspaceId,
                                        @PathVariable UUID findingId,
                                        @RequestBody ReviewRequest request,
                                        @AuthenticationPrincipal Jwt jwt) {
        PiCategory recat = null;
        if (request.reclassifiedCategory() != null) {
            try {
                recat = PiCategory.valueOf(request.reclassifiedCategory());
            } catch (IllegalArgumentException ignored) {}
        }
        ReviewDecision decision = ReviewDecision.valueOf(request.decision());
        return inventoryService.reviewFinding(workspaceId, findingId,
            decision, recat, request.rationale(),
            jwt.getSubject(), jwt.getClaimAsString("preferred_username"));
    }

    public record ReviewRequest(
        String decision,
        String reclassifiedCategory,
        String rationale
    ) {}
}