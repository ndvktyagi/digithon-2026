package com.picopilot.scanner.repository;

import com.picopilot.common.model.ScanStatus;
import com.picopilot.scanner.model.ScanJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface ScanJobRepository extends JpaRepository<ScanJob, UUID> {
    List<ScanJob> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
    List<ScanJob> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    long countByWorkspaceIdAndStatus(UUID workspaceId, ScanStatus status);
    List<ScanJob> findByStatusIn(Collection<ScanStatus> statuses);
    long countByWorkspaceIdAndApprovalState(UUID workspaceId, String approvalState);
}
