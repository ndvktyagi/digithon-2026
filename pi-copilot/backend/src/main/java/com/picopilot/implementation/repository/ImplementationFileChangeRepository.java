package com.picopilot.implementation.repository;

import com.picopilot.implementation.model.ImplementationFileChange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ImplementationFileChangeRepository extends JpaRepository<ImplementationFileChange, UUID> {
    List<ImplementationFileChange> findByRunIdOrderByPathAsc(UUID runId);
}
