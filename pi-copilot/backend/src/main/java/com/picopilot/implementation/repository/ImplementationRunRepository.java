package com.picopilot.implementation.repository;

import com.picopilot.implementation.model.ImplementationRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ImplementationRunRepository extends JpaRepository<ImplementationRun, UUID> {
    List<ImplementationRun> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);
    long countByWorkspaceIdAndStatus(UUID workspaceId, String status);
    boolean existsByWorkspaceIdAndStatusIn(UUID workspaceId, Collection<String> statuses);
    List<ImplementationRun> findByStatusIn(Collection<String> statuses);
    Optional<ImplementationRun> findFirstByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);
    List<ImplementationRun> findByStatusAndParentRunIdIsNotNull(String status);
}
