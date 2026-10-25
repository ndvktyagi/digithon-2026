package com.picopilot.migration.repository;

import com.picopilot.migration.model.MigrationPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MigrationPlanRepository extends JpaRepository<MigrationPlan, UUID> {
    List<MigrationPlan> findByProjectId(UUID projectId);
    boolean existsByProviderConfigId(UUID providerConfigId);
}