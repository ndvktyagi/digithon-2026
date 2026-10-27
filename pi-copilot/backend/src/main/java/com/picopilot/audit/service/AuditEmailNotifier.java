package com.picopilot.audit.service;

import com.picopilot.audit.model.AuditEvent;
import com.picopilot.common.model.Severity;
import com.picopilot.identity.repository.WorkspaceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Emails audit trail events to the recipients configured under {@code picopilot.audit.email}. Runs after the
 * event's transaction commits and on a background thread, so a slow or failing mail server never delays or
 * rolls back the action being audited; failures are only logged.
 */
@Slf4j
@Component
public class AuditEmailNotifier {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss z")
        .withZone(ZoneId.systemDefault());

    /** Published by AuditService for every saved event. */
    public record AuditRecorded(AuditEvent event) {}

    private final ObjectProvider<JavaMailSender> mailSender;
    private final WorkspaceRepository workspaceRepository;
    private final boolean enabled;
    private final String from;
    private final String[] to;
    private final Set<String> actions;
    private final Severity minSeverity;
    private final String subjectPrefix;

    public AuditEmailNotifier(ObjectProvider<JavaMailSender> mailSender,
                              WorkspaceRepository workspaceRepository,
                              @Value("${picopilot.audit.email.enabled:false}") boolean enabled,
                              @Value("${picopilot.audit.email.from:pi-copilot@localhost}") String from,
                              @Value("${picopilot.audit.email.to:}") String to,
                              @Value("${picopilot.audit.email.actions:*}") String actions,
                              @Value("${picopilot.audit.email.min-severity:INFO}") String minSeverity,
                              @Value("${picopilot.audit.email.subject-prefix:[PI Security Co-Pilot]}") String subjectPrefix) {
        this.mailSender = mailSender;
        this.workspaceRepository = workspaceRepository;
        this.enabled = enabled;
        this.from = from;
        this.to = Arrays.stream(to.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
        this.actions = Arrays.stream(actions.split(",")).map(s -> s.trim().toUpperCase(Locale.ROOT))
            .filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        this.minSeverity = Severity.valueOf(minSeverity.trim().toUpperCase(Locale.ROOT));
        this.subjectPrefix = subjectPrefix;
        if (enabled && this.to.length == 0) {
            log.warn("Audit emails are enabled but picopilot.audit.email.to is empty; no emails will be sent");
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAuditRecorded(AuditRecorded recorded) {
        AuditEvent event = recorded.event();
        if (!shouldSend(event)) return;
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.warn("Audit emails are enabled but no mail server is configured (spring.mail.host)");
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject(event));
            message.setText(body(event));
            sender.send(message);
            log.debug("Audit email sent for {} to {} recipient(s)", event.getAction(), to.length);
        } catch (RuntimeException e) {
            log.warn("Could not send audit email for {}: {}", event.getAction(), e.getMessage());
        }
    }

    boolean shouldSend(AuditEvent event) {
        if (!enabled || to.length == 0) return false;
        Severity severity = event.getSeverity() != null ? event.getSeverity() : Severity.INFO;
        // Severity is declared most severe first, so a lower ordinal is more severe
        if (severity.ordinal() > minSeverity.ordinal()) return false;
        return actions.contains("*") || actions.contains(event.getAction().toUpperCase(Locale.ROOT));
    }

    private String subject(AuditEvent event) {
        String outcome = "SUCCESS".equals(event.getOutcome()) ? "" : " [" + event.getOutcome() + "]";
        return subjectPrefix + " " + label(event.getAction()) + outcome
            + (event.getTargetName() != null ? ": " + event.getTargetName() : "");
    }

    private String body(AuditEvent event) {
        String workspace = event.getWorkspaceId() == null ? "-"
            : workspaceRepository.findById(event.getWorkspaceId()).map(w -> w.getName()).orElse(event.getWorkspaceId().toString());
        StringBuilder text = new StringBuilder()
            .append("A new event was recorded in the PI Security Co-Pilot audit trail.\n\n")
            .append("Action:     ").append(label(event.getAction())).append(" (").append(event.getAction()).append(")\n")
            .append("Outcome:    ").append(event.getOutcome()).append('\n')
            .append("Severity:   ").append(event.getSeverity()).append('\n')
            .append("Workspace:  ").append(workspace).append('\n')
            .append("Actor:      ").append(event.getActor()).append('\n')
            .append("Target:     ").append(event.getTargetType())
            .append(event.getTargetName() != null ? " - " + event.getTargetName() : "").append('\n')
            .append("Time:       ").append(TIME.format(event.getEventTime())).append('\n');
        if (event.getEventMetadata() != null) {
            text.append("Details:    ").append(event.getEventMetadata()).append('\n');
        }
        return text.append("\nOpen the Audit Trail in PI Security Co-Pilot for the full history.\n").toString();
    }

    /** SCAN_APPROVED -> Scan approved. */
    private static String label(String action) {
        String words = action.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
