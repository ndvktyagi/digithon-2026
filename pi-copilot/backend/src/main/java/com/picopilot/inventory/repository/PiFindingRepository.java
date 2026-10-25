package com.picopilot.inventory.repository;

import com.picopilot.inventory.model.PiFinding;
import com.picopilot.common.model.Confidence;
import com.picopilot.common.model.FindingStatus;
import com.picopilot.common.model.PiCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PiFindingRepository extends JpaRepository<PiFinding, UUID> {
    Page<PiFinding> findByProjectIdAndWorkspaceId(UUID projectId, UUID workspaceId, Pageable pageable);
    List<PiFinding> findByScanJobId(UUID scanJobId);
    List<PiFinding> findByProjectIdAndStatus(UUID projectId, FindingStatus status);
    long countByProjectId(UUID projectId);
    long countByProjectIdAndStatus(UUID projectId, FindingStatus status);

    @Query("SELECT f FROM PiFinding f WHERE f.projectId = :projectId " +
           "AND (:status IS NULL OR f.status = :status) " +
           "AND (:category IS NULL OR f.candidateCategory = :category) " +
           "AND (:confidence IS NULL OR f.confidence = :confidence) " +
           "AND (:schema IS NULL OR f.dbSchema = :schema) " +
           "AND (:table IS NULL OR f.tableName LIKE %:table%) " +
           "AND (:search IS NULL OR f.columnName LIKE %:search% OR f.tableName LIKE %:search%)")
    Page<PiFinding> findWithFilters(@Param("projectId") UUID projectId,
                                     @Param("status") FindingStatus status,
                                     @Param("category") PiCategory category,
                                     @Param("confidence") Confidence confidence,
                                     @Param("schema") String schema,
                                     @Param("table") String table,
                                     @Param("search") String search,
                                     Pageable pageable);

    long countByWorkspaceId(UUID workspaceId);
    long countByWorkspaceIdAndStatus(UUID workspaceId, FindingStatus status);
    List<PiFinding> findByScanJobIdOrderByDbSchemaAscTableNameAscColumnNameAsc(UUID scanJobId);
    List<PiFinding> findByWorkspaceIdAndApprovedTrue(UUID workspaceId);
    long countByScanJobIdAndApprovedTrue(UUID scanJobId);
}
