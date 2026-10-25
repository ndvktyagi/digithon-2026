package com.picopilot.backfill.repository;

import com.picopilot.backfill.model.BackfillJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BackfillJobRepository extends JpaRepository<BackfillJob, UUID> {
    List<BackfillJob> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
}