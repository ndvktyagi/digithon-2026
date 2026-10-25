package com.picopilot.audit.repository;

import com.picopilot.audit.model.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}