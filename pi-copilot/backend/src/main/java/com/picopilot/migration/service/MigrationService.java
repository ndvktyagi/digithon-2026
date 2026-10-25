package com.picopilot.migration.service;

import com.picopilot.common.exception.InvalidStateException;
import com.picopilot.common.exception.ResourceNotFoundException;
import com.picopilot.common.model.MigrationState;
import com.picopilot.identity.service.WorkspaceService;
import com.picopilot.migration.model.ApprovalRecord;
import com.picopilot.migration.model.GeneratedPatch;
import com.picopilot.migration.model.MigrationPlan;
import com.picopilot.migration.repository.ApprovalRecordRepository;
import com.picopilot.migration.repository.GeneratedPatchRepository;
import com.picopilot.migration.repository.MigrationPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MigrationService {

    private final MigrationPlanRepository planRepository;
    private final GeneratedPatchRepository patchRepository;
    private final ApprovalRecordRepository approvalRepository;
    private final WorkspaceService workspaceService;

    @Transactional
    public MigrationPlan createPlan(UUID workspaceId, MigrationPlan plan, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        plan.setWorkspaceId(workspaceId);
        plan.setState(MigrationState.PREVIEW);
        return planRepository.save(plan);
    }

    @Transactional(readOnly = true)
    public MigrationPlan getPlan(UUID workspaceId, UUID planId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        return planRepository.findById(planId)
            .orElseThrow(() -> new ResourceNotFoundException("MigrationPlan", planId.toString()));
    }

    @Transactional
    public MigrationPlan approvePlan(UUID workspaceId, UUID planId, String userId, String userName) {
        workspaceService.requireMembership(workspaceId, userId);
        MigrationPlan plan = planRepository.findById(planId)
            .orElseThrow(() -> new ResourceNotFoundException("MigrationPlan", planId.toString()));

        if (plan.getState() != MigrationState.PREVIEW) {
            throw new InvalidStateException("Plan not in PREVIEW state: " + plan.getState());
        }

        plan.setState(MigrationState.APPROVED);
        plan.setApprovedBy(userId);
        plan.setApprovedAt(Instant.now());
        MigrationPlan saved = planRepository.save(plan);

        // Record approval
        ApprovalRecord approval = new ApprovalRecord();
        approval.setTargetType("MIGRATION_PLAN");
        approval.setTargetId(planId);
        approval.setApproverId(userId);
        approval.setApproverName(userName);
        approval.setAction("APPROVED");
        approval.setComment("Migration plan approved");
        approvalRepository.save(approval);

        log.info("Migration plan {} approved by {}", planId, userId);
        return saved;
    }

    @Transactional
    public GeneratedPatch addPatch(UUID workspaceId, UUID planId, GeneratedPatch patch, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        patch.setWorkspaceId(workspaceId);
        patch.setMigrationPlanId(planId);
        return patchRepository.save(patch);
    }

    @Transactional(readOnly = true)
    public List<GeneratedPatch> getPatches(UUID planId) {
        return patchRepository.findByMigrationPlanId(planId);
    }

    @Transactional(readOnly = true)
    public List<ApprovalRecord> getApprovals(String targetType, UUID targetId) {
        return approvalRepository.findByTargetTypeAndTargetId(targetType, targetId);
    }
}