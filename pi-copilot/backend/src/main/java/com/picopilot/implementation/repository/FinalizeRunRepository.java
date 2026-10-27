package com.picopilot.implementation.repository;

import com.picopilot.implementation.model.FinalizeRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FinalizeRunRepository extends JpaRepository<FinalizeRun, UUID> {
    Optional<FinalizeRun> findFirstByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);
    List<FinalizeRun> findByCodeStatus(String codeStatus);
}
