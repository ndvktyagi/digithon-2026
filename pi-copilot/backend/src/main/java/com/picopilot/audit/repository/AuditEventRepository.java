package com.picopilot.audit.repository;

import com.picopilot.audit.model.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {
    Page<AuditEvent> findByWorkspaceIdOrderByEventTimeDesc(UUID workspaceId, Pageable pageable);
    Page<AuditEvent> findByProjectIdOrderByEventTimeDesc(UUID projectId, Pageable pageable);
    Page<AuditEvent> findByActionAndWorkspaceId(String action, UUID workspaceId, Pageable pageable);
    List<AuditEvent> findByJobId(UUID jobId);
    Page<AuditEvent> findByWorkspaceIdAndEventTimeBetween(
        UUID workspaceId, Instant from, Instant to, Pageable pageable);

    /** Newest first; a null action or outcome matches any. */
    @Query("SELECT e FROM AuditEvent e WHERE e.workspaceId = :workspaceId"
        + " AND (:action IS NULL OR e.action = :action) AND (:outcome IS NULL OR e.outcome = :outcome)"
        + " ORDER BY e.eventTime DESC")
    Page<AuditEvent> search(@Param("workspaceId") UUID workspaceId, @Param("action") String action,
                            @Param("outcome") String outcome, Pageable pageable);

    @Query("SELECT DISTINCT e.action FROM AuditEvent e WHERE e.workspaceId = :workspaceId ORDER BY e.action")
    List<String> findDistinctActions(@Param("workspaceId") UUID workspaceId);
}