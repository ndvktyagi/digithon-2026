package com.picopilot.backfill.repository;

import com.picopilot.backfill.model.BackfillBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BackfillBatchRepository extends JpaRepository<BackfillBatch, UUID> {
    List<BackfillBatch> findByBackfillJobIdOrderByBatchNumberAsc(UUID jobId);
}