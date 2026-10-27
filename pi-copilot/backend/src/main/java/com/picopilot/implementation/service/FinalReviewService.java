package com.picopilot.implementation.service;

import com.picopilot.audit.service.AuditService;
import com.picopilot.common.event.StageWorkCompletedEvent;
import com.picopilot.common.exception.InvalidStateException;
import com.picopilot.common.exception.ResourceNotFoundException;
import com.picopilot.common.exception.ValidationException;
import com.picopilot.common.model.Severity;
import com.picopilot.common.model.UserRole;
import com.picopilot.common.model.WorkspaceStage;
import com.picopilot.identity.model.Workspace;
import com.picopilot.identity.repository.WorkspaceRepository;
import com.picopilot.identity.service.WorkspaceLifecycleService;
import com.picopilot.identity.service.WorkspaceService;
import com.picopilot.implementation.model.ImplementationFileChange;
import com.picopilot.implementation.model.ImplementationRun;
import com.picopilot.implementation.model.ReviewLineComment;
import com.picopilot.implementation.repository.ImplementationFileChangeRepository;
import com.picopilot.implementation.repository.ImplementationRunRepository;
import com.picopilot.implementation.repository.ReviewLineCommentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Final Review of an implementation: approve (on to Data Sync), reject (workflow ends) or
 * request changes (a new round resolves the comment and comes back for review).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FinalReviewService {

    public enum Decision { APPROVE, REJECT, REQUEST_CHANGES }

    /**
     * @param line line number on the given side (NEW = changed file, OLD = original, for removed lines;
     *             0 with side FILE for a comment on the whole file)
     */
    public record LineComment(String path, int line, String side, String lineText, String body) {}

    public record ReviewRequest(Decision decision, String comment, List<LineComment> lineComments) {}

    /** One file of a round, with the versions the UI diffs. */
    public record FileChangeView(String path, String changeKind, String baseContent, String previousContent,
                                 String newContent) {}

    private final ImplementationRunRepository runRepository;
    private final ImplementationFileChangeRepository fileChangeRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceService workspaceService;
    private final WorkspaceLifecycleService lifecycleService;
    private final PullRequestGateway pullRequests;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final ReviewLineCommentRepository lineCommentRepository;

    @Transactional(readOnly = true)
    public List<FileChangeView> files(UUID workspaceId, UUID runId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        find(workspaceId, runId);
        return fileChangeRepository.findByRunIdOrderByPathAsc(runId).stream()
            .map(FinalReviewService::toView)
            .toList();
    }

    @Transactional
    public void review(UUID workspaceId, UUID runId, ReviewRequest request, String userId, String userName) {
        workspaceService.requireRole(workspaceId, userId, UserRole.PLATFORM_ADMIN, UserRole.APP_OWNER,
            UserRole.SECURITY_REVIEWER, UserRole.PRIVACY_REVIEWER);
        if (request == null || request.decision() == null) {
            throw new ValidationException("Choose approve, reject or request changes");
        }
        String comment = request.comment() == null ? "" : request.comment().trim();
        List<LineComment> lineComments = request.lineComments() == null ? List.of() : request.lineComments().stream()
            .filter(c -> c.body() != null && !c.body().isBlank() && c.path() != null
                && (c.line() > 0 || "FILE".equals(c.side())))
            .toList();
        if (request.decision() == Decision.REJECT && comment.isEmpty()) {
            throw new ValidationException("Add a comment explaining why the implementation is rejected");
        }
        if (request.decision() == Decision.REQUEST_CHANGES && comment.isEmpty() && lineComments.isEmpty()) {
            throw new ValidationException("Describe the changes you want, or comment on the lines to change");
        }

        ImplementationRun run = find(workspaceId, runId);
        ImplementationRun latest = runRepository.findFirstByWorkspaceIdOrderByCreatedAtDesc(workspaceId).orElseThrow();
        if (!latest.getId().equals(run.getId()) || !ImplementationRun.PENDING_REVIEW.equals(run.getReviewStatus())) {
            throw new InvalidStateException("Only the latest round waiting for review can be reviewed");
        }
        Workspace workspace = workspaceRepository.findById(workspaceId)
            .orElseThrow(() -> new ResourceNotFoundException("Workspace", workspaceId.toString()));
        if (workspace.getLifecycleStage().isTerminal()) {
            throw new InvalidStateException("The workspace lifecycle has ended (" + workspace.getLifecycleStage() + ")");
        }
        if (workspace.getLifecycleStage() != WorkspaceStage.FINAL_REVIEW) {
            // A round is waiting for review, so that is where the workspace belongs (e.g. it moved on
            // before Final Review existed); bring the lifecycle in line before applying the decision
            lifecycleService.applyReviewDecision(workspaceId, WorkspaceStage.FINAL_REVIEW, userId);
        }

        String author = userName != null ? userName : userId;
        for (LineComment c : lineComments) {
            ReviewLineComment saved = new ReviewLineComment();
            saved.setRunId(run.getId());
            saved.setPath(c.path());
            saved.setSide(normalizedSide(c.side()));
            saved.setLineNumber("FILE".equals(saved.getSide()) ? 0 : c.line());
            saved.setLineText(c.lineText());
            saved.setBody(c.body().trim());
            saved.setAuthor(author);
            lineCommentRepository.save(saved);
        }
        List<PullRequestGateway.InlineComment> inline = lineComments.stream()
            .map(c -> new PullRequestGateway.InlineComment(c.path(), c.line(), "OLD".equals(c.side()),
                "FILE".equals(c.side()), c.body().trim()))
            .toList();

        run.setReviewComment(comment.isEmpty() ? null : comment);
        run.setReviewedBy(author);
        run.setReviewedAt(Instant.now());
        PullRequestGateway.Repo repo = repoOrNull(run);

        switch (request.decision()) {
            case APPROVE -> {
                run.setReviewStatus(ImplementationRun.APPROVED);
                runRepository.save(run);
                if (repo != null) pullRequests.review(repo, run.getPrNumber(), run.getCommitSha(), "APPROVE",
                    "**Approved in PI Co-Pilot Final Review** (round " + run.getRoundNumber() + ")"
                        + (comment.isEmpty() ? "" : "\n\n" + comment), inline);
                // Approval does not merge: the branch is deployed as the release, and Finalize merges it
                // into the base branch only once the release succeeded. The lifecycle moves on to Data Sync
                events.publishEvent(new StageWorkCompletedEvent(workspaceId, WorkspaceStage.FINAL_REVIEW, userId));
            }
            case REJECT -> {
                run.setReviewStatus(ImplementationRun.REJECTED);
                runRepository.save(run);
                if (repo != null) {
                    pullRequests.review(repo, run.getPrNumber(), run.getCommitSha(), "COMMENT",
                        "**Rejected in PI Co-Pilot Final Review**\n\n" + comment, inline);
                    pullRequests.close(repo, run.getPrNumber());
                }
                lifecycleService.applyReviewDecision(workspaceId, WorkspaceStage.REJECTED, userId);
            }
            case REQUEST_CHANGES -> {
                run.setReviewStatus(ImplementationRun.CHANGES_REQUESTED);
                runRepository.save(run);
                ImplementationRun next = nextRound(run, improvementRequest(comment, lineComments));
                runRepository.save(next);
                if (repo != null) pullRequests.review(repo, run.getPrNumber(), run.getCommitSha(), "REQUEST_CHANGES",
                    "**Changes requested in PI Co-Pilot Final Review** (round " + run.getRoundNumber() + ")"
                        + (comment.isEmpty() ? "" : "\n\n" + comment), inline);
                lifecycleService.applyReviewDecision(workspaceId, WorkspaceStage.IMPLEMENTATION, userId);
            }
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("round", run.getRoundNumber());
        metadata.put("decision", request.decision().name());
        auditService.record(workspaceId, null, userId, null, "FINAL_REVIEW_" + request.decision().name(),
            "IMPLEMENTATION", run.getId().toString(), run.getBranchName(), "SUCCESS", Severity.INFO, null, null, metadata);
        log.info("Final review of round {} in workspace {}: {} by {}", run.getRoundNumber(), workspaceId,
            request.decision(), userId);
    }

    /** Re-queues a failed improvement round, e.g. after configuring the AI key. */
    @Transactional
    public void retry(UUID workspaceId, UUID runId, String userId) {
        workspaceService.requireRole(workspaceId, userId, UserRole.PLATFORM_ADMIN, UserRole.APP_OWNER);
        ImplementationRun run = find(workspaceId, runId);
        if (run.getParentRunId() == null || !ImplementationRun.FAILED.equals(run.getStatus())) {
            throw new InvalidStateException("Only a failed review round can be retried");
        }
        run.setStatus(ImplementationRun.PENDING);
        run.setCodeStatus(ImplementationRun.PENDING);
        run.setErrorMessage(null);
        run.setCompletedAt(null);
        runRepository.save(run);
    }

    /** The instruction for the next round: the general comment plus each line comment with its code. */
    static String improvementRequest(String comment, List<LineComment> lineComments) {
        StringBuilder text = new StringBuilder(comment);
        if (!lineComments.isEmpty()) {
            if (!text.isEmpty()) text.append("\n\n");
            text.append("Line comments:");
            for (LineComment c : lineComments) {
                text.append("\n- ").append(c.path());
                if ("FILE".equals(c.side())) {
                    text.append(" (whole file): ").append(c.body().trim());
                    continue;
                }
                text.append(" line ").append(c.line()).append("OLD".equals(c.side()) ? " (original)" : "");
                if (c.lineText() != null && !c.lineText().isBlank()) {
                    text.append(" `").append(c.lineText().trim()).append('`');
                }
                text.append(": ").append(c.body().trim());
            }
        }
        return text.toString();
    }

    private static String normalizedSide(String side) {
        return "OLD".equals(side) || "FILE".equals(side) ? side : "NEW";
    }

    private ImplementationRun nextRound(ImplementationRun previous, String comment) {
        ImplementationRun next = new ImplementationRun();
        next.setWorkspaceId(previous.getWorkspaceId());
        next.setScanJobId(previous.getScanJobId());
        next.setProviderConfigId(previous.getProviderConfigId());
        next.setRepositoryConnectorId(previous.getRepositoryConnectorId());
        next.setRepository(previous.getRepository());
        next.setBaseBranch(previous.getBaseBranch());
        next.setBranchName(previous.getBranchName());
        next.setCompareUrl(previous.getCompareUrl());
        next.setPrNumber(previous.getPrNumber());
        next.setPrUrl(previous.getPrUrl());
        next.setDdlScript(previous.getDdlScript());
        next.setColumnsJson(previous.getColumnsJson());
        next.setRoundNumber(previous.getRoundNumber() + 1);
        next.setParentRunId(previous.getId());
        next.setImprovementRequest(comment);
        // The user starts the round with "Run again" on the Implementation page
        next.setStatus(ImplementationRun.WAITING);
        // Rounds only revise code; the encrypted columns already exist
        next.setDbStatus(ImplementationRun.SKIPPED);
        next.setCodeStatus(ImplementationRun.PENDING);
        return next;
    }

    private PullRequestGateway.Repo repoOrNull(ImplementationRun run) {
        try {
            return run.getRepositoryConnectorId() == null ? null : pullRequests.repo(run.getRepositoryConnectorId());
        } catch (RuntimeException e) {
            log.info("GitHub connector unavailable for review comments: {}", e.getMessage());
            return null;
        }
    }

    private ImplementationRun find(UUID workspaceId, UUID runId) {
        return runRepository.findById(runId)
            .filter(r -> workspaceId.equals(r.getWorkspaceId()))
            .orElseThrow(() -> new ResourceNotFoundException("ImplementationRun", runId.toString()));
    }

    private static FileChangeView toView(ImplementationFileChange c) {
        return new FileChangeView(c.getPath(), c.getChangeKind(), c.getBaseContent(), c.getPreviousContent(), c.getNewContent());
    }
}
