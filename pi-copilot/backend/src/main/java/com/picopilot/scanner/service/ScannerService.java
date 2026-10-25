package com.picopilot.scanner.service;

import com.picopilot.common.exception.InvalidStateException;
import com.picopilot.common.exception.ResourceNotFoundException;
import com.picopilot.common.exception.ScanException;
import com.picopilot.common.model.ScanStatus;
import com.picopilot.identity.service.WorkspaceService;
import com.picopilot.scanner.model.ScanJob;
import com.picopilot.scanner.model.ScanScope;
import com.picopilot.scanner.repository.ScanJobRepository;
import com.picopilot.scanner.repository.ScanScopeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScannerService {

    private final ScanJobRepository scanJobRepository;
    private final ScanScopeRepository scanScopeRepository;
    private final WorkspaceService workspaceService;

    @Transactional
    public ScanJob createScan(UUID workspaceId, UUID projectId,
                               ScanJob scanJob, ScanScope scope, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        scanJob.setWorkspaceId(workspaceId);
        scanJob.setProjectId(projectId);
        scanJob.setStatus(ScanStatus.PENDING);
        ScanJob saved = scanJobRepository.save(scanJob);

        scope.setWorkspaceId(workspaceId);
        scope.setScanJobId(saved.getId());
        scanScopeRepository.save(scope);

        log.info("Scan job {} created for project {}", saved.getId(), projectId);
        return saved;
    }

    @Transactional
    public ScanJob startScan(UUID workspaceId, UUID scanJobId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        ScanJob scanJob = scanJobRepository.findById(scanJobId)
            .orElseThrow(() -> new ResourceNotFoundException("ScanJob", scanJobId.toString()));

        if (scanJob.getStatus() != ScanStatus.PENDING) {
            throw new InvalidStateException("Scan cannot be started in state: " + scanJob.getStatus());
        }

        scanJob.setStatus(ScanStatus.RUNNING);
        scanJob.setStartedAt(Instant.now());
        ScanJob saved = scanJobRepository.save(scanJob);

        // Trigger async execution
        executeScanAsync(saved.getId());
        return saved;
    }

    @Async
    protected void executeScanAsync(UUID scanJobId) {
        // Background worker would perform the actual scan:
        // 1. Connect to repository via GitHub connector
        // 2. Parse Java/SQL/TypeScript files for AST analysis
        // 3. Connect to database for schema/metadata analysis
        // 4. Apply rule engine for PI detection
        // 5. Optionally call AI provider for classification
        // 6. Create PI findings with evidence
        log.info("Async scan execution started for job {}", scanJobId);
    }

    @Transactional
    public ScanJob cancelScan(UUID workspaceId, UUID scanJobId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        ScanJob scanJob = scanJobRepository.findById(scanJobId)
            .orElseThrow(() -> new ResourceNotFoundException("ScanJob", scanJobId.toString()));

        if (scanJob.getStatus() != ScanStatus.RUNNING && scanJob.getStatus() != ScanStatus.PENDING) {
            throw new InvalidStateException("Cannot cancel scan in state: " + scanJob.getStatus());
        }

        scanJob.setStatus(ScanStatus.CANCELLED);
        scanJob.setCompletedAt(Instant.now());
        log.info("Scan job {} cancelled by {}", scanJobId, userId);
        return scanJobRepository.save(scanJob);
    }

    @Transactional(readOnly = true)
    public ScanJob getScanStatus(UUID workspaceId, UUID scanJobId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        return scanJobRepository.findById(scanJobId)
            .orElseThrow(() -> new ResourceNotFoundException("ScanJob", scanJobId.toString()));
    }

    @Transactional(readOnly = true)
    public List<ScanJob> listScans(UUID workspaceId, UUID projectId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        return scanJobRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }
}