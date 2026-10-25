package com.picopilot.backfill.repository;

import com.picopilot.backfill.model.VerificationRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface VerificationRunRepository extends JpaRepository<VerificationRun, UUID> {
    List<VerificationRun> findByBackfillJobIdOrderByCreatedAtDesc(UUID jobId);
}