package com.picopilot.backfill.service;

import com.picopilot.backfill.model.BackfillBatch;
import com.picopilot.backfill.model.BackfillJob;
import com.picopilot.backfill.model.VerificationRun;
import com.picopilot.backfill.repository.BackfillBatchRepository;
import com.picopilot.backfill.repository.BackfillJobRepository;
import com.picopilot.backfill.repository.VerificationRunRepository;
import com.picopilot.common.exception.InvalidStateException;
import com.picopilot.common.exception.ResourceNotFoundException;
import com.picopilot.identity.service.WorkspaceService;
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
public class BackfillService {

    private final BackfillJobRepository jobRepository;
    private final BackfillBatchRepository batchRepository;
    private final VerificationRunRepository verificationRepository;
    private final WorkspaceService workspaceService;

    @Transactional
    public BackfillJob createJob(UUID workspaceId, BackfillJob job, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        job.setWorkspaceId(workspaceId);
        job.setStatus("PENDING");
        return jobRepository.save(job);
    }

    @Transactional
    public BackfillJob startJob(UUID workspaceId, UUID jobId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        BackfillJob job = jobRepository.findById(jobId)
            .orElseThrow(() -> new ResourceNotFoundException("BackfillJob", jobId.toString()));

        if (!"PENDING".equals(job.getStatus())) {
            throw new InvalidStateException("Cannot start backfill in state: " + job.getStatus());
        }

        job.setStatus("RUNNING");
        job.setStartedAt(Instant.now());
        log.info("Backfill job {} started by {}", jobId, userId);
        return jobRepository.save(job);
    }

    @Transactional
    public BackfillJob pauseJob(UUID workspaceId, UUID jobId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        BackfillJob job = jobRepository.findById(jobId)
            .orElseThrow(() -> new ResourceNotFoundException("BackfillJob", jobId.toString()));

        if (!"RUNNING".equals(job.getStatus())) {
            throw new InvalidStateException("Cannot pause backfill in state: " + job.getStatus());
        }

        job.setStatus("PAUSED");
        return jobRepository.save(job);
    }

    @Transactional
    public BackfillJob stopJob(UUID workspaceId, UUID jobId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        BackfillJob job = jobRepository.findById(jobId)
            .orElseThrow(() -> new ResourceNotFoundException("BackfillJob", jobId.toString()));

        if (!List.of("RUNNING", "PAUSED").contains(job.getStatus())) {
            throw new InvalidStateException("Cannot stop backfill in state: " + job.getStatus());
        }

        job.setStatus("STOPPED");
        job.setCompletedAt(Instant.now());
        return jobRepository.save(job);
    }

    @Transactional(readOnly = true)
    public BackfillJob getStatus(UUID workspaceId, UUID jobId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        return jobRepository.findById(jobId)
            .orElseThrow(() -> new ResourceNotFoundException("BackfillJob", jobId.toString()));
    }

    @Transactional(readOnly = true)
    public List<BackfillBatch> getBatches(UUID jobId) {
        return batchRepository.findByBackfillJobIdOrderByBatchNumberAsc(jobId);
    }

    @Transactional(readOnly = true)
    public List<VerificationRun> getVerifications(UUID jobId) {
        return verificationRepository.findByBackfillJobIdOrderByCreatedAtDesc(jobId);
    }
}