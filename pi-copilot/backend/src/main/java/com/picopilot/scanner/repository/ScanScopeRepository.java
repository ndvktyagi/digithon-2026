package com.picopilot.scanner.repository;

import com.picopilot.scanner.model.ScanScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ScanScopeRepository extends JpaRepository<ScanScope, UUID> {
    Optional<ScanScope> findByScanJobId(UUID scanJobId);
}