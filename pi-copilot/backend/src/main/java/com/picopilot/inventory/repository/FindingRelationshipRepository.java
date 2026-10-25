package com.picopilot.inventory.repository;

import com.picopilot.inventory.model.FindingRelationship;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface FindingRelationshipRepository extends JpaRepository<FindingRelationship, UUID> {
    List<FindingRelationship> findBySourceFindingId(UUID findingId);
    List<FindingRelationship> findByTargetFindingId(UUID findingId);

    @Query("SELECT r FROM FindingRelationship r WHERE r.sourceFindingId = :findingId OR r.targetFindingId = :findingId")
    List<FindingRelationship> findAllRelationships(@Param("findingId") UUID findingId);
}