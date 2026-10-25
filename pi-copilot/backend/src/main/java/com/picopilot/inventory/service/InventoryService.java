package com.picopilot.inventory.service;

import com.picopilot.common.dto.PagedResponse;
import com.picopilot.common.exception.InvalidStateException;
import com.picopilot.common.exception.ResourceNotFoundException;
import com.picopilot.common.model.Confidence;
import com.picopilot.common.model.FindingStatus;
import com.picopilot.common.model.PiCategory;
import com.picopilot.common.model.ReviewDecision;
import com.picopilot.identity.service.WorkspaceService;
import com.picopilot.inventory.model.FindingEvidence;
import com.picopilot.inventory.model.FindingRelationship;
import com.picopilot.inventory.model.FindingReview;
import com.picopilot.inventory.model.PiFinding;
import com.picopilot.inventory.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final PiFindingRepository findingRepository;
    private final FindingEvidenceRepository evidenceRepository;
    private final FindingRelationshipRepository relationshipRepository;
    private final FindingReviewRepository reviewRepository;
    private final WorkspaceService workspaceService;

    @Transactional(readOnly = true)
    public PagedResponse<PiFinding> listFindings(UUID workspaceId, UUID projectId,
                                                  FindingStatus status, PiCategory category,
                                                  Confidence confidence, String schema,
                                                  String table, String search,
                                                  String userId, Pageable pageable) {
        workspaceService.requireMembership(workspaceId, userId);
        Page<PiFinding> page = findingRepository.findWithFilters(
            projectId, status, category, confidence, schema, table, search, pageable);
        return PagedResponse.of(page.getContent(), page);
    }

    @Transactional(readOnly = true)
    public PiFinding getFindingDetail(UUID workspaceId, UUID findingId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        return findingRepository.findById(findingId)
            .orElseThrow(() -> new ResourceNotFoundException("PiFinding", findingId.toString()));
    }

    @Transactional(readOnly = true)
    public List<FindingEvidence> getEvidence(UUID findingId) {
        return evidenceRepository.findByFindingId(findingId);
    }

    @Transactional(readOnly = true)
    public List<FindingRelationship> getRelationships(UUID findingId) {
        return relationshipRepository.findAllRelationships(findingId);
    }

    @Transactional(readOnly = true)
    public List<FindingReview> getReviewHistory(UUID findingId) {
        return reviewRepository.findByFindingIdOrderByReviewedAtDesc(findingId);
    }

    @Transactional
    public FindingReview reviewFinding(UUID workspaceId, UUID findingId,
                                        ReviewDecision decision,
                                        PiCategory reclassifiedCategory,
                                        String rationale,
                                        String userId, String userName) {
        workspaceService.requireMembership(workspaceId, userId);

        PiFinding finding = findingRepository.findById(findingId)
            .orElseThrow(() -> new ResourceNotFoundException("PiFinding", findingId.toString()));

        if (finding.getStatus() != FindingStatus.DISCOVERED &&
            finding.getStatus() != FindingStatus.REVIEWED) {
            throw new InvalidStateException(
                "Finding is not in a reviewable state: " + finding.getStatus());
        }

        FindingReview review = new FindingReview();
        review.setFindingId(findingId);
        review.setDecision(decision);
        if (reclassifiedCategory != null) {
            review.setReclassifiedCategory(reclassifiedCategory.name());
        }
        review.setRationale(rationale);
        review.setReviewerId(userId);
        review.setReviewerName(userName);
        FindingReview saved = reviewRepository.save(review);

        // Update finding status
        finding.setStatus(FindingStatus.REVIEWED);
        finding.setReviewedBy(userId);
        finding.setReviewedAt(java.time.Instant.now());
        if (reclassifiedCategory != null) {
            finding.setCandidateCategory(reclassifiedCategory);
        }
        finding.setReviewerComment(rationale);
        findingRepository.save(finding);

        log.info("Finding {} reviewed by {}: {}", findingId, userId, decision);
        return saved;
    }

    @Transactional(readOnly = true)
    public long countFindingsByStatus(UUID projectId, FindingStatus status) {
        return findingRepository.countByProjectIdAndStatus(projectId, status);
    }
}