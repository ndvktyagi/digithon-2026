package com.picopilot.audit.service;

import com.picopilot.audit.model.AuditEvent;
import com.picopilot.audit.repository.AuditEventRepository;
import com.picopilot.common.model.Severity;
import com.picopilot.identity.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditEventRepository auditRepository;
    private final WorkspaceService workspaceService;
    private final ApplicationEventPublisher events;

    @Transactional
    public AuditEvent record(UUID workspaceId, UUID projectId, String actor, String actorRole,
                              String action, String targetType, String targetId, String targetName,
                              String outcome, Severity severity, String correlationId, UUID jobId,
                              Map<String, Object> eventMetadata) {
        AuditEvent event = new AuditEvent();
        event.setWorkspaceId(workspaceId);
        event.setProjectId(projectId);
        event.setActor(actor);
        event.setActorRole(actorRole);
        event.setAction(action);
        event.setTargetType(targetType);
        event.setTargetId(targetId);
        event.setTargetName(targetName);
        event.setOutcome(outcome);
        event.setSeverity(severity != null ? severity : Severity.INFO);
        event.setCorrelationId(correlationId);
        event.setJobId(jobId);

        // Serialize metadata to JSON string (excluding secrets and raw PI)
        if (eventMetadata != null) {
            try {
                // Sanitize: remove any potential secret/PI keys
                eventMetadata.remove("secret");
                eventMetadata.remove("password");
                eventMetadata.remove("token");
                eventMetadata.remove("rawValue");
                event.setEventMetadata(new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(eventMetadata));
            } catch (Exception e) {
                log.warn("Failed to serialize event metadata", e);
            }
        }

        AuditEvent saved = auditRepository.save(event);
        log.debug("Audit event recorded: {} by {} on {}", action, actor, targetType);
        events.publishEvent(new AuditEmailNotifier.AuditRecorded(saved));
        return saved;
    }

    @Transactional(readOnly = true)
    public Page<AuditEvent> getWorkspaceEvents(UUID workspaceId, String userId, Pageable pageable) {
        workspaceService.requireMembership(workspaceId, userId);
        return auditRepository.findByWorkspaceIdOrderByEventTimeDesc(workspaceId, pageable);
    }

    /** Newest first; blank filters match everything. */
    @Transactional(readOnly = true)
    public Page<AuditEvent> search(UUID workspaceId, String action, String outcome, String userId, Pageable pageable) {
        workspaceService.requireMembership(workspaceId, userId);
        return auditRepository.search(workspaceId, blankToNull(action), blankToNull(outcome), pageable);
    }

    @Transactional(readOnly = true)
    public List<String> actions(UUID workspaceId, String userId) {
        workspaceService.requireMembership(workspaceId, userId);
        return auditRepository.findDistinctActions(workspaceId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Transactional(readOnly = true)
    public Page<AuditEvent> getProjectEvents(UUID workspaceId, UUID projectId,
                                              String userId, Pageable pageable) {
        workspaceService.requireMembership(workspaceId, userId);
        return auditRepository.findByProjectIdOrderByEventTimeDesc(projectId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<AuditEvent> getEventsByAction(UUID workspaceId, String action,
                                               String userId, Pageable pageable) {
        workspaceService.requireMembership(workspaceId, userId);
        return auditRepository.findByActionAndWorkspaceId(action, workspaceId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<AuditEvent> getEventsByTimeRange(UUID workspaceId, Instant from, Instant to,
                                                   String userId, Pageable pageable) {
        workspaceService.requireMembership(workspaceId, userId);
        return auditRepository.findByWorkspaceIdAndEventTimeBetween(workspaceId, from, to, pageable);
    }
}