package com.picopilot.scanner.repository;

import com.picopilot.scanner.model.ScanTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface ScanTargetRepository extends JpaRepository<ScanTarget, UUID> {
    List<ScanTarget> findByScanJobIdOrderByExecutionOrderAsc(UUID scanJobId);
    List<ScanTarget> findByScanJobIdIn(Collection<UUID> scanJobIds);
}
