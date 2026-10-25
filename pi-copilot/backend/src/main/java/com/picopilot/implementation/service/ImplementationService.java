package com.picopilot.implementation.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picopilot.audit.service.AuditService;
import com.picopilot.common.exception.InvalidStateException;
import com.picopilot.common.exception.ResourceNotFoundException;
import com.picopilot.common.exception.ValidationException;
import com.picopilot.common.model.Severity;
import com.picopilot.common.model.UserRole;
import com.picopilot.encryption.model.EncryptionProviderConfig;
import com.picopilot.encryption.repository.EncryptionProviderConfigRepository;
import com.picopilot.common.model.WorkspaceStage;
import com.picopilot.identity.model.Workspace;
import com.picopilot.identity.repository.WorkspaceRepository;
import com.picopilot.identity.service.WorkspaceLifecycleService;
import com.picopilot.identity.service.WorkspaceService;
import com.picopilot.implementation.model.ReviewLineComment;
import com.picopilot.implementation.repository.ReviewLineCommentRepository;
import com.picopilot.implementation.model.ImplementationRun;
import com.picopilot.implementation.repository.ImplementationRunRepository;
import com.picopilot.inventory.repository.PiFindingRepository;
import com.picopilot.project.model.RepositoryConnector;
import com.picopilot.project.repository.RepositoryConnectorRepository;
import com.picopilot.scanner.model.ScanJob;
import com.picopilot.scanner.repository.ScanJobRepository;
import com.picopilot.scanner.service.ScannerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImplementationService {

    /** Only LOCAL_DEV shares its key format with the generated application cipher today. */
    private static final String SUPPORTED_PROVIDER = "LOCAL_DEV";

    private final ImplementationRunRepository runRepository;
    private final ScanJobRepository scanJobRepository;
    private final PiFindingRepository findingRepository;
    private final EncryptionProviderConfigRepository providerRepository;
    private final RepositoryConnectorRepository repoConnectorRepository;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;
    private final ImplementationExecutor executor;
    private final ObjectMapper objectMapper;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceLifecycleService lifecycleService;
    private final ReviewLineCommentRepository lineCommentRepository;

    /** Optional overrides; by default the latest approved scan, first verified provider and repository are used. */
    public record StartRequest(UUID providerConfigId, UUID repositoryConnectorId) {}

    public record Readiness(boolean ready, String approvedScanName, UUID approvedScanId, int approvedColumns,
                            String providerName, String repository, String baseBranch, List<String> problems) {}

    public record RunView(UUID id, String status, String dbStatus, String codeStatus, String scanName,
                          String providerName, String repository, String baseBranch, String branchName,
                          String commitSha, String compareUrl, String ddlScript, List<Map<String, Object>> columns,
                          List<String> changedFiles, List<String> log, Instant createdAt, Instant startedAt,
                          Instant completedAt, String errorMessage, int roundNumber, UUID parentRunId,
                          String improvementRequest, String aiSummary, String reviewStatus, String reviewComment,
                          String reviewedBy, Instant reviewedAt, Integer prNumber, String prUrl,
                          String mergeStatus, String mergeSha, String mergeMessage, Instant mergedAt,
                          List<LineCommentView> lineComments) {}

    public record LineCommentView(String path, int line, String side, String lineText, String body,
                                  String author, Instant createdAt, boolean resolved, Integer resolvedInRound,
                                  String resolutionNote) {}

    private record Inputs(ScanJob scan, EncryptionProviderConfig provider, RepositoryConnector repo,
                          int approvedColumns, List<String> problems) {}

    @Transactional(readOnly = true)
    public Readiness readiness(UUID workspaceId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        Inputs in = resolve(workspaceId, new StartRequest(null, null));
        return new Readiness(in.problems().isEmpty(),
            in.scan() == null ? null : in.scan().getName(), in.scan() == null ? null : in.scan().getId(),
            in.approvedColumns(), in.provider() == null ? null : in.provider().getName(),
            in.repo() == null ? null : in.repo().getRepositoryOwner() + "/" + in.repo().getRepositoryName(),
            in.repo() == null ? null : in.repo().getBranch(), in.problems());
    }

    @Transactional
    public RunView start(UUID workspaceId, StartRequest request, String userId) {
        workspaceService.requireRole(workspaceId, userId, UserRole.PLATFORM_ADMIN, UserRole.APP_OWNER);
        if (runRepository.existsByWorkspaceIdAndStatusIn(workspaceId,
            List.of(ImplementationRun.PENDING, ImplementationRun.RUNNING))) {
            throw new InvalidStateException("An implementation is already running in this workspace");
        }
        ImplementationRun latest = runRepository.findFirstByWorkspaceIdOrderByCreatedAtDesc(workspaceId).orElse(null);
        if (latest != null && ImplementationRun.PENDING_REVIEW.equals(latest.getReviewStatus())) {
            throw new InvalidStateException("Round " + latest.getRoundNumber() + " is waiting in Final Review; review it first");
        }
        if (latest != null && ImplementationRun.WAITING.equals(latest.getStatus())) {
            return startReviewRound(latest, userId);
        }
        Inputs in = resolve(workspaceId, request == null ? new StartRequest(null, null) : request);
        if (!in.problems().isEmpty()) {
            throw new ValidationException(String.join(" ", in.problems()));
        }
        Workspace workspace = workspaceRepository.findById(workspaceId).orElseThrow();
        WorkspaceStage stage = workspace.getLifecycleStage();
        if (stage.isTerminal()) {
            throw new InvalidStateException("The workflow has ended (" + stage + "). Reopen a stage on the Lifecycle page first.");
        }
        if (stage.ordinal() > WorkspaceStage.IMPLEMENTATION.ordinal()) {
            // New code has to go through Final Review again, so later stages no longer hold
            lifecycleService.applyReviewDecision(workspaceId, WorkspaceStage.IMPLEMENTATION, userId);
        }

        ImplementationRun run = new ImplementationRun();
        run.setWorkspaceId(workspaceId);
        run.setScanJobId(in.scan().getId());
        run.setProviderConfigId(in.provider().getId());
        run.setRepositoryConnectorId(in.repo().getId());
        run.setRepository(in.repo().getRepositoryOwner() + "/" + in.repo().getRepositoryName());
        run.setBaseBranch(in.repo().getBranch());
        run.setRoundNumber(runRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId).stream()
            .mapToInt(ImplementationRun::getRoundNumber).max().orElse(0) + 1);
        run = runRepository.save(run);

        UUID runId = run.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                executor.execute(runId);
            }
        });

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("scan", in.scan().getName());
        metadata.put("columns", in.approvedColumns());
        metadata.put("repository", run.getRepository());
        auditService.record(workspaceId, in.scan().getProjectId(), userId, null, "IMPLEMENTATION_STARTED",
            "IMPLEMENTATION", runId.toString(), run.getRepository(), "SUCCESS", Severity.INFO, null, null, metadata);
        log.info("Implementation {} started by {} for scan {}", runId, userId, in.scan().getId());
        return toView(run);
    }

    /** Queues the round Final Review's "Request changes" left waiting; the review scheduler runs it. */
    private RunView startReviewRound(ImplementationRun run, String userId) {
        WorkspaceStage stage = workspaceRepository.findById(run.getWorkspaceId()).orElseThrow().getLifecycleStage();
        if (stage.isTerminal()) {
            throw new InvalidStateException("The workflow has ended (" + stage + "). Reopen a stage on the Lifecycle page first.");
        }
        run.setStatus(ImplementationRun.PENDING);
        run = runRepository.save(run);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("round", run.getRoundNumber());
        auditService.record(run.getWorkspaceId(), null, userId, null, "IMPLEMENTATION_STARTED",
            "IMPLEMENTATION", run.getId().toString(), run.getBranchName(), "SUCCESS", Severity.INFO, null, null, metadata);
        log.info("Review round {} ({}) started by {}", run.getRoundNumber(), run.getId(), userId);
        return toView(run);
    }

    @Transactional(readOnly = true)
    public List<RunView> list(UUID workspaceId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        return runRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId).stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public RunView get(UUID workspaceId, UUID runId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        return runRepository.findById(runId)
            .filter(r -> workspaceId.equals(r.getWorkspaceId()))
            .map(this::toView)
            .orElseThrow(() -> new ResourceNotFoundException("ImplementationRun", runId.toString()));
    }

    private Inputs resolve(UUID workspaceId, StartRequest request) {
        List<String> problems = new java.util.ArrayList<>();

        ScanJob scan = scanJobRepository
            .findFirstByWorkspaceIdAndApprovalStateOrderByApprovedAtDesc(workspaceId, ScannerService.APPROVAL_APPROVED)
            .orElse(null);
        int columns = 0;
        if (scan == null) {
            problems.add("Approve a scan first (Review & Approve).");
        } else {
            columns = (int) findingRepository.countByScanJobIdAndApprovedTrue(scan.getId());
            if (columns == 0) problems.add("The approved scan has no columns selected for encryption.");
        }

        List<EncryptionProviderConfig> verified = providerRepository.findByWorkspaceId(workspaceId).stream()
            .filter(EncryptionProviderConfig::isVerified)
            .filter(p -> request.providerConfigId() == null || p.getId().equals(request.providerConfigId()))
            .toList();
        EncryptionProviderConfig provider = verified.stream()
            .filter(p -> SUPPORTED_PROVIDER.equals(p.getProviderType())).findFirst().orElse(null);
        if (verified.isEmpty()) {
            problems.add("Set up and verify an encryption provider first (Encryption Setup).");
        } else if (provider == null) {
            problems.add("Code generation currently supports the LOCAL_DEV provider only.");
        }

        RepositoryConnector repo = repoConnectorRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId).stream()
            .filter(RepositoryConnector::isVerified)
            .filter(r -> request.repositoryConnectorId() == null || r.getId().equals(request.repositoryConnectorId()))
            .findFirst().orElse(null);
        if (repo == null) {
            problems.add("Add and verify a GitHub repository connector first (Connect).");
        }
        return new Inputs(scan, provider, repo, columns, problems);
    }

    private RunView toView(ImplementationRun r) {
        String scanName = scanJobRepository.findById(r.getScanJobId()).map(ScanJob::getName).orElse(null);
        String providerName = r.getProviderConfigId() == null ? null
            : providerRepository.findById(r.getProviderConfigId()).map(EncryptionProviderConfig::getName).orElse(null);
        List<Map<String, Object>> columns = List.of();
        if (r.getColumnsJson() != null) {
            try {
                columns = objectMapper.readValue(r.getColumnsJson(), new TypeReference<>() {});
            } catch (Exception e) {
                log.debug("Unreadable columns JSON for run {}", r.getId());
            }
        }
        return new RunView(r.getId(), r.getStatus(), r.getDbStatus(), r.getCodeStatus(), scanName, providerName,
            r.getRepository(), r.getBaseBranch(), r.getBranchName(), r.getCommitSha(), r.getCompareUrl(),
            r.getDdlScript(), columns, lines(r.getChangedFiles()), lines(r.getLog()), r.getCreatedAt(),
            r.getStartedAt(), r.getCompletedAt(), r.getErrorMessage(), r.getRoundNumber(), r.getParentRunId(),
            r.getImprovementRequest(), r.getAiSummary(), r.getReviewStatus(), r.getReviewComment(), r.getReviewedBy(),
            r.getReviewedAt(), r.getPrNumber(), r.getPrUrl(),
            r.getMergeStatus(), r.getMergeSha(), r.getMergeMessage(), r.getMergedAt(),
            lineCommentRepository.findByRunIdOrderByPathAscLineNumberAsc(r.getId()).stream()
                .map(ImplementationService::toView)
                .toList());
    }

    private static LineCommentView toView(ReviewLineComment c) {
        return new LineCommentView(c.getPath(), c.getLineNumber(), c.getSide(), c.getLineText(), c.getBody(),
            c.getAuthor(), c.getCreatedAt(), c.isResolved(), c.getResolvedInRound(), c.getResolutionNote());
    }

    private static List<String> lines(String text) {
        return text == null || text.isBlank() ? List.of() : text.lines().toList();
    }
}
