package com.picopilot.scanner.service;

import com.picopilot.audit.service.AuditService;
import com.picopilot.common.exception.InvalidStateException;
import com.picopilot.common.exception.ResourceNotFoundException;
import com.picopilot.common.exception.ValidationException;
import com.picopilot.common.model.FindingStatus;
import com.picopilot.common.model.ReviewDecision;
import com.picopilot.common.model.ScanStatus;
import com.picopilot.common.model.Severity;
import com.picopilot.common.model.UserRole;
import com.picopilot.identity.service.WorkspaceService;
import com.picopilot.inventory.model.FindingReview;
import com.picopilot.inventory.model.PiFinding;
import com.picopilot.inventory.repository.FindingReviewRepository;
import com.picopilot.inventory.repository.PiFindingRepository;
import com.picopilot.project.model.DatabaseConnector;
import com.picopilot.project.repository.DatabaseConnectorRepository;
import com.picopilot.scanner.model.ScanJob;
import com.picopilot.scanner.model.ScanScope;
import com.picopilot.scanner.model.ScanTarget;
import com.picopilot.scanner.repository.ScanJobRepository;
import com.picopilot.scanner.repository.ScanScopeRepository;
import com.picopilot.scanner.repository.ScanTargetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScannerService {

    public static final String APPROVAL_PENDING = "PENDING";
    public static final String APPROVAL_APPROVED = "APPROVED";

    private final ScanJobRepository scanJobRepository;
    private final ScanScopeRepository scanScopeRepository;
    private final ScanTargetRepository targetRepository;
    private final PiFindingRepository findingRepository;
    private final FindingReviewRepository reviewRepository;
    private final DatabaseConnectorRepository dbConnectorRepository;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;
    private final ScanExecutor scanExecutor;

    /**
     * @param databaseConnectorIds databases to scan, in execution order
     * @param schemas              optional comma-separated schemas; overrides each connector's schema scope
     */
    public record CreateScanRequest(String name, List<UUID> databaseConnectorIds,
                                    String schemas, boolean valuePatternScan) {}

    public record TargetView(UUID id, UUID connectorId, String connectorType, String label, int executionOrder,
                             String schemas, ScanStatus status, int tablesScanned, int columnsScanned,
                             int findingsCount, Instant startedAt, Instant completedAt, String errorMessage) {}

    public record ScanView(UUID id, String name, ScanStatus status, String scanType, String approvalState,
                           String approvedBy, Instant approvedAt, int databaseCount, Integer totalTablesScanned,
                           Integer tablesWithPi, Integer findingsCount, Instant createdAt, String createdBy,
                           Instant startedAt, Instant completedAt, String errorMessage,
                           boolean valuePatternScan, List<TargetView> targets) {}

    @Transactional
    public ScanView createAndStart(UUID workspaceId, CreateScanRequest request, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        List<UUID> ids = request.databaseConnectorIds() == null ? List.of() : request.databaseConnectorIds();
        if (ids.isEmpty()) {
            throw new ValidationException("Select at least one database to scan");
        }
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new ValidationException("A database can only appear once in the execution order");
        }

        List<DatabaseConnector> connectors = ids.stream()
            .map(id -> dbConnectorRepository.findById(id)
                .filter(c -> workspaceId.equals(c.getWorkspaceId()))
                .orElseThrow(() -> new ResourceNotFoundException("DatabaseConnector", id.toString())))
            .toList();
        for (DatabaseConnector c : connectors) {
            if (!c.isVerified()) {
                throw new ValidationException("Database " + label(c) + " is not verified. Test the connection first.");
            }
        }

        ScanJob job = new ScanJob();
        job.setWorkspaceId(workspaceId);
        job.setProjectId(connectors.get(0).getProject().getId());
        job.setName(request.name() == null || request.name().isBlank()
            ? "Database scan " + LocalDate.now() : request.name().trim());
        job.setScanType("DATABASE");
        job.setAnalysisEngine("RULES_ONLY");
        job.setStatus(ScanStatus.RUNNING);
        job.setStartedAt(Instant.now());
        job.setTotalTablesScanned(0);
        job.setFindingsCount(0);
        job.setTablesWithPi(0);
        job = scanJobRepository.save(job);

        ScanScope scope = new ScanScope();
        scope.setWorkspaceId(workspaceId);
        scope.setScanJobId(job.getId());
        scope.setValuePatternScanEnabled(request.valuePatternScan());
        scanScopeRepository.save(scope);

        String schemaOverride = request.schemas() == null || request.schemas().isBlank() ? null : request.schemas().trim();
        int order = 1;
        for (DatabaseConnector c : connectors) {
            ScanTarget target = new ScanTarget();
            target.setWorkspaceId(workspaceId);
            target.setScanJobId(job.getId());
            target.setConnectorId(c.getId());
            target.setConnectorType("POSTGRESQL");
            target.setLabel(label(c));
            target.setExecutionOrder(order++);
            target.setSchemas(schemaOverride != null ? schemaOverride : c.getSchemaScope());
            targetRepository.save(target);
        }

        UUID jobId = job.getId();
        // Start only after commit so the worker sees the job and its targets
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                scanExecutor.execute(jobId);
            }
        });

        audit(workspaceId, userId, "SCAN_STARTED", job, Map.of("databases", connectors.size()));
        log.info("Scan {} created by {} across {} database(s)", jobId, userId, connectors.size());
        return toView(job, targetRepository.findByScanJobIdOrderByExecutionOrderAsc(jobId));
    }

    @Transactional(readOnly = true)
    public List<ScanView> listScans(UUID workspaceId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        List<ScanJob> jobs = scanJobRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
        Map<UUID, List<ScanTarget>> targets = targetRepository
            .findByScanJobIdIn(jobs.stream().map(ScanJob::getId).toList()).stream()
            .collect(Collectors.groupingBy(ScanTarget::getScanJobId));
        return jobs.stream()
            .map(j -> toView(j, targets.getOrDefault(j.getId(), List.of())))
            .toList();
    }

    @Transactional(readOnly = true)
    public ScanView getScan(UUID workspaceId, UUID scanJobId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        ScanJob job = find(workspaceId, scanJobId);
        return toView(job, targetRepository.findByScanJobIdOrderByExecutionOrderAsc(scanJobId));
    }

    @Transactional
    public ScanView cancelScan(UUID workspaceId, UUID scanJobId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        ScanJob job = find(workspaceId, scanJobId);
        if (job.getStatus() != ScanStatus.RUNNING && job.getStatus() != ScanStatus.PENDING) {
            throw new InvalidStateException("Cannot stop a scan that is " + job.getStatus());
        }
        job.setStatus(ScanStatus.CANCELLED);
        job.setCompletedAt(Instant.now());
        job = scanJobRepository.save(job);
        audit(workspaceId, userId, "SCAN_STOPPED", job, new HashMap<>());
        log.info("Scan {} stopped by {}", scanJobId, userId);
        return toView(job, targetRepository.findByScanJobIdOrderByExecutionOrderAsc(scanJobId));
    }

    @Transactional(readOnly = true)
    public List<PiFinding> listFindings(UUID workspaceId, UUID scanJobId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        find(workspaceId, scanJobId);
        return findingRepository.findByScanJobIdOrderByDbSchemaAscTableNameAscColumnNameAsc(scanJobId);
    }

    /**
     * Records the reviewer's selection: the given findings are approved for encryption,
     * every other finding of the scan is rejected. May be repeated to change the selection.
     */
    @Transactional
    public ScanView approve(UUID workspaceId, UUID scanJobId, List<UUID> approvedFindingIds,
                            String comment, String userId, String userName) {
        workspaceService.requireRole(workspaceId, userId, UserRole.PLATFORM_ADMIN, UserRole.APP_OWNER,
            UserRole.PRIVACY_REVIEWER, UserRole.SECURITY_REVIEWER);
        ScanJob job = find(workspaceId, scanJobId);
        if (job.getStatus() != ScanStatus.COMPLETED) {
            throw new InvalidStateException("Only a completed scan can be approved (scan is " + job.getStatus() + ")");
        }
        Set<UUID> selected = approvedFindingIds == null ? Set.of() : new LinkedHashSet<>(approvedFindingIds);
        if (selected.isEmpty()) {
            throw new ValidationException("Select at least one table or column to approve for encryption");
        }

        List<PiFinding> findings = findingRepository.findByScanJobId(scanJobId);
        Set<UUID> known = findings.stream().map(PiFinding::getId).collect(Collectors.toSet());
        if (!known.containsAll(selected)) {
            throw new ValidationException("Some selected findings do not belong to this scan");
        }

        Instant now = Instant.now();
        Set<String> approvedTables = new HashSet<>();
        for (PiFinding f : findings) {
            boolean approved = selected.contains(f.getId());
            if (approved) {
                approvedTables.add(f.getDatabaseConnectorId() + "." + f.getDbSchema() + "." + f.getTableName());
            }
            f.setApproved(approved);
            f.setStatus(FindingStatus.REVIEWED);
            f.setSuggestedAction(approved ? "ENCRYPT" : "NONE");
            f.setReviewedBy(userId);
            f.setReviewedAt(now);
            f.setReviewerComment(comment);

            FindingReview review = new FindingReview();
            review.setFindingId(f.getId());
            review.setDecision(approved ? ReviewDecision.CONFIRMED : ReviewDecision.REJECTED);
            review.setRationale(comment);
            review.setReviewerId(userId);
            review.setReviewerName(userName);
            reviewRepository.save(review);
        }
        findingRepository.saveAll(findings);

        job.setApprovalState(APPROVAL_APPROVED);
        job.setApprovedBy(userName != null ? userName : userId);
        job.setApprovedAt(now);
        job = scanJobRepository.save(job);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("approvedColumns", selected.size());
        metadata.put("approvedTables", approvedTables.size());
        audit(workspaceId, userId, "SCAN_APPROVED", job, metadata);
        log.info("Scan {} approved by {}: {} columns in {} tables", scanJobId, userId,
            selected.size(), approvedTables.size());
        return toView(job, targetRepository.findByScanJobIdOrderByExecutionOrderAsc(scanJobId));
    }

    private ScanJob find(UUID workspaceId, UUID scanJobId) {
        return scanJobRepository.findById(scanJobId)
            .filter(j -> workspaceId.equals(j.getWorkspaceId()))
            .orElseThrow(() -> new ResourceNotFoundException("ScanJob", scanJobId.toString()));
    }

    private void audit(UUID workspaceId, String userId, String action, ScanJob job, Map<String, Object> metadata) {
        auditService.record(workspaceId, job.getProjectId(), userId, null, action,
            "SCAN", job.getId().toString(), job.getName(), "SUCCESS", Severity.INFO,
            null, job.getId(), new HashMap<>(metadata));
    }

    private static String label(DatabaseConnector c) {
        return c.getHost() + ":" + c.getPort() + "/" + c.getDatabaseName();
    }

    private ScanView toView(ScanJob j, List<ScanTarget> targets) {
        boolean valueScan = scanScopeRepository.findByScanJobId(j.getId())
            .map(ScanScope::isValuePatternScanEnabled).orElse(false);
        List<TargetView> targetViews = targets.stream()
            .sorted((a, b) -> Integer.compare(a.getExecutionOrder(), b.getExecutionOrder()))
            .map(t -> new TargetView(t.getId(), t.getConnectorId(), t.getConnectorType(), t.getLabel(),
                t.getExecutionOrder(), t.getSchemas(), t.getStatus(), t.getTablesScanned(), t.getColumnsScanned(),
                t.getFindingsCount(), t.getStartedAt(), t.getCompletedAt(), t.getErrorMessage()))
            .toList();
        return new ScanView(j.getId(), j.getName(), j.getStatus(), j.getScanType(), j.getApprovalState(),
            j.getApprovedBy(), j.getApprovedAt(), targets.size(), j.getTotalTablesScanned(), j.getTablesWithPi(),
            j.getFindingsCount(), j.getCreatedAt(), j.getCreatedBy(), j.getStartedAt(), j.getCompletedAt(),
            j.getErrorMessage(), valueScan, targetViews);
    }
}
