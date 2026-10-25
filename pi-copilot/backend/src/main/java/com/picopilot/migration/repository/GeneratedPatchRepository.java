package com.picopilot.migration.repository;

import com.picopilot.migration.model.GeneratedPatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface GeneratedPatchRepository extends JpaRepository<GeneratedPatch, UUID> {
    List<GeneratedPatch> findByMigrationPlanId(UUID planId);
}