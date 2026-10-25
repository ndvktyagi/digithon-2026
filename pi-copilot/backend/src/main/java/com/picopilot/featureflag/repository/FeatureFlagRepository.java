package com.picopilot.featureflag.repository;

import com.picopilot.featureflag.model.FeatureFlag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, UUID> {
    List<FeatureFlag> findByProjectId(UUID projectId);
    Optional<FeatureFlag> findByFlagName(String flagName);

    long countByWorkspaceId(UUID workspaceId);
    long countByWorkspaceIdAndCurrentState(UUID workspaceId, String currentState);
}
