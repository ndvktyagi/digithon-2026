package com.picopilot.datasync.repository;

import com.picopilot.datasync.model.DataSyncRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DataSyncRunRepository extends JpaRepository<DataSyncRun, UUID> {
    List<DataSyncRun> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);
    Optional<DataSyncRun> findFirstByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);
    boolean existsByWorkspaceIdAndStatusIn(UUID workspaceId, Collection<String> statuses);
    List<DataSyncRun> findByStatusIn(Collection<String> statuses);
}
