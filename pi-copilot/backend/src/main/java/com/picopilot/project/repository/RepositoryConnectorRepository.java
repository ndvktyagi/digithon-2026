package com.picopilot.project.repository;

import com.picopilot.project.model.RepositoryConnector;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RepositoryConnectorRepository extends JpaRepository<RepositoryConnector, UUID> {
    List<RepositoryConnector> findByProjectId(UUID projectId);
    List<RepositoryConnector> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);
    Optional<RepositoryConnector> findByProjectIdAndRepositoryName(UUID projectId, String repoName);

    long countByWorkspaceIdAndVerifiedTrue(UUID workspaceId);
}
