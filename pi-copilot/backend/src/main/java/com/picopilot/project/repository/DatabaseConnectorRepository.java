package com.picopilot.project.repository;

import com.picopilot.project.model.DatabaseConnector;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DatabaseConnectorRepository extends JpaRepository<DatabaseConnector, UUID> {
    List<DatabaseConnector> findByProjectId(UUID projectId);
    List<DatabaseConnector> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    long countByWorkspaceIdAndVerifiedTrue(UUID workspaceId);
}
