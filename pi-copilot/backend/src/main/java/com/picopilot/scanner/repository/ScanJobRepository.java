package com.picopilot.scanner.repository;

import com.picopilot.scanner.model.ScanJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ScanJobRepository extends JpaRepository<ScanJob, UUID> {
    List<ScanJob> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
    List<ScanJob> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);
}