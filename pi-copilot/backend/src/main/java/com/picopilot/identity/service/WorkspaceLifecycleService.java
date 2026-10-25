package com.picopilot.identity.service;

import com.picopilot.audit.service.AuditService;
import com.picopilot.backfill.repository.BackfillJobRepository;
import com.picopilot.common.exception.InvalidStateException;
import com.picopilot.common.exception.ResourceNotFoundException;
import com.picopilot.common.exception.ValidationException;
import com.picopilot.common.model.ScanStatus;
import com.picopilot.common.model.Severity;
import com.picopilot.common.model.UserRole;
import com.picopilot.common.model.WorkspaceStage;
import com.picopilot.encryption.repository.EncryptionProviderConfigRepository;
import com.picopilot.featureflag.repository.FeatureFlagRepository;
import com.picopilot.identity.dto.StageStatus;
import com.picopilot.identity.dto.WorkspaceLifecycleResponse;
import com.picopilot.identity.model.Workspace;
import com.picopilot.identity.repository.WorkspaceRepository;
import com.picopilot.inventory.repository.PiFindingRepository;
import com.picopilot.project.repository.DatabaseConnectorRepository;
import com.picopilot.project.repository.RepositoryConnectorRepository;
import com.picopilot.scanner.service.ScannerService;
import com.picopilot.scanner.repository.ScanJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks where a workspace is in its protection lifecycle and only lets it move
 * forward once the current stage's exit criteria are met by real workspace data.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceLifecycleService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;
    private final RepositoryConnectorRepository repoConnectorRepository;
    private final DatabaseConnectorRepository dbConnectorRepository;
    private final ScanJobRepository scanJobRepository;
    private final PiFindingRepository findingRepository;
    private final EncryptionProviderConfigRepository providerRepository;
    private final BackfillJobRepository backfillJobRepository;
    private final FeatureFlagRepository featureFlagRepository;

    private record Check(boolean ready, String detail) {}

    @Transactional(readOnly = true)
    public WorkspaceLifecycleResponse getLifecycle(UUID workspaceId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        return toResponse(loadWorkspace(workspaceId));
    }

    @Transactional
    public WorkspaceLifecycleResponse advance(UUID workspaceId, String userId, boolean confirmed) {
        workspaceService.requireRole(workspaceId, userId, UserRole.PLATFORM_ADMIN, UserRole.APP_OWNER);
        Workspace workspace = loadWorkspace(workspaceId);
        WorkspaceStage current = workspace.getLifecycleStage();

        if (current == WorkspaceStage.COMPLETED) {
            throw new InvalidStateException("Workspace lifecycle is already completed");
        }
        Check check = evaluate(workspaceId, current);
        if (!check.ready()) {
            throw new InvalidStateException("Stage " + current + " is not complete: " + check.detail());
        }
        if (current == WorkspaceStage.FINALIZE && !confirmed) {
            throw new ValidationException(
                "Confirm that legacy plain-text code paths and columns have been removed before finalizing");
        }

        moveTo(workspace, current, current.next(), userId, "WORKSPACE_STAGE_ADVANCED");
        return toResponse(workspace);
    }

    @Transactional
    public WorkspaceLifecycleResponse reopen(UUID workspaceId, String userId, WorkspaceStage target) {
        workspaceService.requireRole(workspaceId, userId, UserRole.PLATFORM_ADMIN, UserRole.APP_OWNER);
        Workspace workspace = loadWorkspace(workspaceId);
        WorkspaceStage current = workspace.getLifecycleStage();

        if (target == WorkspaceStage.COMPLETED || target.ordinal() >= current.ordinal()) {
            throw new ValidationException("Only an earlier stage can be reopened (current stage: " + current + ")");
        }
        moveTo(workspace, current, target, userId, "WORKSPACE_STAGE_REOPENED");
        return toResponse(workspace);
    }

    private void moveTo(Workspace workspace, WorkspaceStage from, WorkspaceStage to,
                        String userId, String action) {
        workspace.setLifecycleStage(to);
        workspace.setStageUpdatedAt(Instant.now());
        workspaceRepository.save(workspace);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("fromStage", from.name());
        metadata.put("toStage", to.name());
        auditService.record(workspace.getId(), null, userId, null, action,
            "WORKSPACE", workspace.getId().toString(), workspace.getName(),
            "SUCCESS", Severity.INFO, null, null, metadata);
        log.info("Workspace {} moved from {} to {} by {}", workspace.getSlug(), from, to, userId);
    }

    private Workspace loadWorkspace(UUID workspaceId) {
        return workspaceRepository.findById(workspaceId)
            .orElseThrow(() -> new ResourceNotFoundException("Workspace", workspaceId.toString()));
    }

    private WorkspaceLifecycleResponse toResponse(Workspace workspace) {
        UUID workspaceId = workspace.getId();
        WorkspaceStage current = workspace.getLifecycleStage();
        List<StageStatus> stages = new ArrayList<>();
        for (WorkspaceStage stage : WorkspaceStage.values()) {
            if (stage == WorkspaceStage.COMPLETED) continue;
            String state = stage.ordinal() < current.ordinal() ? "DONE"
                : stage == current ? "CURRENT" : "UPCOMING";
            Check check = evaluate(workspaceId, stage);
            stages.add(new StageStatus(stage, state, check.ready(), check.detail()));
        }
        return new WorkspaceLifecycleResponse(workspaceId, current, workspace.getStageUpdatedAt(), stages);
    }

    private Check evaluate(UUID workspaceId, WorkspaceStage stage) {
        return switch (stage) {
            case CONNECT -> {
                long repos = repoConnectorRepository.countByWorkspaceIdAndVerifiedTrue(workspaceId);
                long dbs = dbConnectorRepository.countByWorkspaceIdAndVerifiedTrue(workspaceId);
                yield new Check(repos + dbs > 0,
                    plural(repos, "verified repository", "verified repositories") + ", "
                        + plural(dbs, "verified database", "verified databases"));
            }
            case SCAN -> {
                long completed = scanJobRepository.countByWorkspaceIdAndStatus(workspaceId, ScanStatus.COMPLETED);
                long findings = findingRepository.countByWorkspaceId(workspaceId);
                yield new Check(completed > 0,
                    plural(completed, "completed scan", "completed scans") + ", "
                        + plural(findings, "finding", "findings"));
            }
            case REVIEW -> {
                long approved = scanJobRepository.countByWorkspaceIdAndApprovalState(
                    workspaceId, ScannerService.APPROVAL_APPROVED);
                long completed = scanJobRepository.countByWorkspaceIdAndStatus(workspaceId, ScanStatus.COMPLETED);
                yield new Check(approved > 0,
                    approved > 0 ? plural(approved, "approved scan", "approved scans")
                        : completed == 0 ? "No completed scan to review yet"
                        : "Select tables in a completed scan and approve them for encryption");
            }
            case ENCRYPTION_SETUP -> {
                long verified = providerRepository.countByWorkspaceIdAndVerifiedTrue(workspaceId);
                yield new Check(verified > 0,
                    plural(verified, "verified encryption provider", "verified encryption providers"));
            }
            case DATA_SYNC -> {
                long jobs = backfillJobRepository.countByWorkspaceId(workspaceId);
                long done = backfillJobRepository.countByWorkspaceIdAndStatus(workspaceId, "COMPLETED");
                yield new Check(done > 0, done + " of " + jobs + " backfill jobs completed");
            }
            case ENABLE_ENCRYPTION -> {
                long flags = featureFlagRepository.countByWorkspaceId(workspaceId);
                long encrypted = featureFlagRepository.countByWorkspaceIdAndCurrentState(workspaceId, "ENCRYPTED_READ");
                yield new Check(encrypted > 0, encrypted + " of " + flags + " flags reading encrypted data");
            }
            // Cleanup is done in the target application; the stage records the user's sign-off.
            case FINALIZE -> new Check(true, "Remove duplicate code paths and plain-text columns, then confirm");
            case COMPLETED -> new Check(true, "Lifecycle complete");
        };
    }

    private static String plural(long count, String singular, String plural) {
        return count + " " + (count == 1 ? singular : plural);
    }
}
