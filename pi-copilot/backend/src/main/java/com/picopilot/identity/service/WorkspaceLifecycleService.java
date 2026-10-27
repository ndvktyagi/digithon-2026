package com.picopilot.identity.service;

import com.picopilot.audit.service.AuditService;
import com.picopilot.backfill.repository.BackfillJobRepository;
import com.picopilot.common.event.StageWorkCompletedEvent;
import com.picopilot.common.exception.InvalidStateException;
import com.picopilot.common.exception.ResourceNotFoundException;
import com.picopilot.common.exception.ValidationException;
import com.picopilot.common.model.ScanStatus;
import com.picopilot.common.model.Severity;
import com.picopilot.common.model.UserRole;
import com.picopilot.common.model.WorkspaceStage;
import com.picopilot.encryption.repository.EncryptionProviderConfigRepository;
import com.picopilot.datasync.model.DataSyncRun;
import com.picopilot.datasync.repository.DataSyncRunRepository;
import com.picopilot.implementation.model.FinalizeRun;
import com.picopilot.implementation.model.ImplementationRun;
import com.picopilot.implementation.repository.FinalizeRunRepository;
import com.picopilot.implementation.repository.ImplementationRunRepository;
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
import org.springframework.context.event.EventListener;
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
    private final ScannerService scannerService;
    private final ImplementationRunRepository implementationRunRepository;
    private final DataSyncRunRepository dataSyncRunRepository;
    private final FinalizeRunRepository finalizeRunRepository;

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

        if (current.isTerminal()) {
            throw new InvalidStateException("Workspace lifecycle has ended (" + current + ")");
        }
        Check check = evaluate(workspaceId, current);
        if (!check.ready()) {
            throw new InvalidStateException("Stage " + current + " is not complete: " + check.detail());
        }
        if (current == WorkspaceStage.FINALIZE && !confirmed) {
            throw new ValidationException("Confirm that the plain-text columns can be retired before finalizing");
        }

        moveTo(workspace, current, current.next(), userId, "WORKSPACE_STAGE_ADVANCED");
        if (current == WorkspaceStage.CONNECT) {
            // Every verified database is queued as an Initiated scan, ready to start from Scans
            scannerService.createInitiated(workspaceId, userId);
        }
        return toResponse(workspace);
    }

    /**
     * Moves the workspace forward when work for its current stage (or an earlier one it is
     * still on) has finished, e.g. a scan completed or a scan was approved.
     * Connect waits for an explicit "Complete" because users may still be adding connectors,
     * and Finalize needs the user's sign-off, so neither advances automatically.
     */
    @EventListener
    @Transactional
    public void onStageWorkCompleted(StageWorkCompletedEvent event) {
        workspaceRepository.findById(event.workspaceId()).ifPresent(workspace -> {
            WorkspaceStage current = workspace.getLifecycleStage();
            while (current != WorkspaceStage.CONNECT && current != WorkspaceStage.FINALIZE
                && !current.isTerminal()
                && current.ordinal() <= event.stage().ordinal()
                && evaluate(workspace.getId(), current).ready()) {
                WorkspaceStage next = current.next();
                moveTo(workspace, current, next, event.actor(), "WORKSPACE_STAGE_AUTO_ADVANCED");
                current = next;
            }
        });
    }

    @Transactional
    public WorkspaceLifecycleResponse reopen(UUID workspaceId, String userId, WorkspaceStage target) {
        workspaceService.requireRole(workspaceId, userId, UserRole.PLATFORM_ADMIN, UserRole.APP_OWNER);
        Workspace workspace = loadWorkspace(workspaceId);
        WorkspaceStage current = workspace.getLifecycleStage();

        if (target.isTerminal() || target.ordinal() >= current.ordinal()) {
            throw new ValidationException("Only an earlier stage can be reopened (current stage: " + current + ")");
        }
        moveTo(workspace, current, target, userId, "WORKSPACE_STAGE_REOPENED");
        return toResponse(workspace);
    }

    /**
     * Moves the workspace for the implementation/review loop: back to IMPLEMENTATION (new code or
     * changes requested), into FINAL_REVIEW (a round is waiting for review) or to the terminal
     * REJECTED stage. The caller has already checked permissions.
     */
    @Transactional
    public void applyReviewDecision(UUID workspaceId, WorkspaceStage target, String userId) {
        if (target != WorkspaceStage.IMPLEMENTATION && target != WorkspaceStage.FINAL_REVIEW
            && target != WorkspaceStage.REJECTED) {
            throw new IllegalArgumentException("Only IMPLEMENTATION, FINAL_REVIEW or REJECTED can be set here");
        }
        Workspace workspace = loadWorkspace(workspaceId);
        WorkspaceStage current = workspace.getLifecycleStage();
        if (current != target) {
            moveTo(workspace, current, target, userId, switch (target) {
                case REJECTED -> "WORKSPACE_REJECTED";
                case FINAL_REVIEW -> "WORKSPACE_STAGE_TO_REVIEW";
                default -> "WORKSPACE_STAGE_RETURNED";
            });
        }
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
        // A rejected workspace stopped at Final Review: earlier stages are done, later ones never ran
        WorkspaceStage position = current == WorkspaceStage.REJECTED ? WorkspaceStage.FINAL_REVIEW : current;
        for (WorkspaceStage stage : WorkspaceStage.values()) {
            if (stage.isTerminal()) continue;
            String state = stage.ordinal() < position.ordinal() ? "DONE"
                : stage == position ? (current == WorkspaceStage.REJECTED ? "REJECTED" : "CURRENT") : "UPCOMING";
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
            case IMPLEMENTATION -> {
                ImplementationRun latest = implementationRunRepository
                    .findFirstByWorkspaceIdOrderByCreatedAtDesc(workspaceId).orElse(null);
                boolean done = latest != null && ImplementationRun.COMPLETED.equals(latest.getStatus())
                    && !ImplementationRun.CHANGES_REQUESTED.equals(latest.getReviewStatus());
                yield new Check(done, latest == null ? "Add encrypted columns and push the code changes to a new branch"
                    : done ? "Round " + latest.getRoundNumber() + " pushed to " + latest.getBranchName()
                    : ImplementationRun.WAITING.equals(latest.getStatus())
                        ? "Changes requested: click Run again to start round " + latest.getRoundNumber()
                    : latest.getParentRunId() != null ? "Round " + latest.getRoundNumber() + " is resolving the review comments"
                    : "Implementation is " + latest.getStatus().toLowerCase());
            }
            case FINAL_REVIEW -> {
                ImplementationRun latest = implementationRunRepository
                    .findFirstByWorkspaceIdOrderByCreatedAtDesc(workspaceId).orElse(null);
                String review = latest == null ? null : latest.getReviewStatus();
                yield new Check(ImplementationRun.APPROVED.equals(review),
                    latest == null ? "Nothing to review yet"
                        : ImplementationRun.APPROVED.equals(review) ? "Round " + latest.getRoundNumber() + " approved"
                        : ImplementationRun.REJECTED.equals(review) ? "Rejected in round " + latest.getRoundNumber()
                        : "Review round " + latest.getRoundNumber() + ": approve, reject or request changes");
            }
            case DATA_SYNC -> {
                DataSyncRun latest = dataSyncRunRepository.findFirstByWorkspaceIdOrderByCreatedAtDesc(workspaceId).orElse(null);
                boolean done = latest != null && DataSyncRun.COMPLETED.equals(latest.getStatus());
                yield new Check(done, latest == null ? "Encrypt the existing PI values into the new columns"
                    : done ? latest.getValuesVerified() + " value(s) encrypted and verified"
                    : "Data sync " + latest.getStatus().toLowerCase()
                        + (latest.getErrorMessage() != null ? ": " + latest.getErrorMessage() : ""));
            }
            case FINALIZE -> {
                FinalizeRun latest = finalizeRunRepository.findFirstByWorkspaceIdOrderByCreatedAtDesc(workspaceId).orElse(null);
                boolean releaseFailed = latest != null && FinalizeRun.FAILED.equals(latest.getReleaseOutcome());
                boolean dropped = latest != null && FinalizeRun.DROPPED.equals(latest.getColumnsStatus());
                boolean done = releaseFailed ? FinalizeRun.DROPPED.equals(latest.getColumnsStatus())
                    || FinalizeRun.KEPT.equals(latest.getColumnsStatus()) : dropped;
                yield new Check(done, latest == null || latest.getReleaseOutcome() == null
                        ? "Deploy the approved release branch, then report whether the release succeeded"
                    : releaseFailed ? (done ? "Release failed: pull request closed, encrypted columns "
                        + (dropped ? "dropped" : "kept") : "Release failed: rolling back" + (latest.getErrorMessage() != null
                        ? " (" + latest.getErrorMessage() + ")" : ""))
                    : dropped ? plural(latest.getDroppedColumns() == null ? 0 : latest.getDroppedColumns().lines().count(),
                        "plain-text column", "plain-text columns") + " dropped"
                    : FinalizeRun.PENDING.equals(latest.getCodeStatus()) ? "Release succeeded: confirm the queries on encrypted fields"
                    : FinalizeRun.FAILED.equals(latest.getCodeStatus()) ? "Release cleanup failed: " + latest.getErrorMessage()
                    : FinalizeRun.RUNNING.equals(latest.getCodeStatus()) ? "Retiring the plain-text code paths"
                    : !FinalizeRun.MERGED.equals(latest.getMergeStatus())
                        ? "Merge the release pull request" + (latest.getMergeMessage() != null ? " (" + latest.getMergeMessage() + ")" : "")
                    : FinalizeRun.FAILED.equals(latest.getColumnsStatus()) ? "Dropping the columns failed: " + latest.getErrorMessage()
                    : "Release merged: deploy it, then drop the plain-text columns");
            }
            case COMPLETED -> new Check(true, "Lifecycle complete");
            case REJECTED -> new Check(false, "Rejected in Final Review");
        };
    }

    private static String plural(long count, String singular, String plural) {
        return count + " " + (count == 1 ? singular : plural);
    }
}
