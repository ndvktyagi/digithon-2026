package com.picopilot.audit.service;

import com.picopilot.audit.model.AuditEvent;
import com.picopilot.common.model.Severity;
import com.picopilot.identity.repository.WorkspaceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.util.HtmlUtils;

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
            String workspace = workspaceName(event);
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject(event));
            // Plain text for clients that cannot show HTML, HTML for the rest
            helper.setText(body(event, workspace), html(event, workspace));
            sender.send(message);
            log.info("Audit email sent for {} to {}", event.getAction(), String.join(", ", to));
        } catch (MailAuthenticationException e) {
            log.error("Could not send audit email for {}: the mail server rejected the login for {}. For Gmail, "
                + "MAIL_PASSWORD must be a 16-character App Password (Google Account > Security > App passwords), "
                + "not the account password", event.getAction(), from, e);
        } catch (MessagingException | RuntimeException e) {
            log.error("Could not send audit email for {}", event.getAction(), e);
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

    private String workspaceName(AuditEvent event) {
        return event.getWorkspaceId() == null ? "-"
            : workspaceRepository.findById(event.getWorkspaceId()).map(w -> w.getName()).orElse(event.getWorkspaceId().toString());
    }

    private String body(AuditEvent event, String workspace) {
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

    /** Table-based layout with inline styles, which is what most mail clients (Gmail, Outlook) render reliably. */
    private String html(AuditEvent event, String workspace) {
        Severity severity = event.getSeverity() != null ? event.getSeverity() : Severity.INFO;
        boolean success = "SUCCESS".equals(event.getOutcome());
        String target = event.getTargetType() + (event.getTargetName() != null ? " - " + event.getTargetName() : "");

        StringBuilder rows = new StringBuilder()
            .append(row("Action", esc(label(event.getAction()))
                + " <span style=\"color:#6b7280;font-family:Consolas,monospace;font-size:12px;\">"
                + esc(event.getAction()) + "</span>"))
            .append(row("Outcome", badge(String.valueOf(event.getOutcome()), success ? "#047857" : "#b91c1c",
                success ? "#d1fae5" : "#fee2e2")))
            .append(row("Severity", badge(severity.name(), severityColor(severity), "#f3f4f6")))
            .append(row("Workspace", esc(workspace)))
            .append(row("Actor", esc(event.getActor())))
            .append(row("Target", esc(target)))
            .append(row("Time", event.getEventTime() != null ? esc(TIME.format(event.getEventTime())) : "-"));
        if (event.getEventMetadata() != null) {
            rows.append(row("Details", "<div style=\"font-family:Consolas,monospace;font-size:12px;color:#374151;"
                + "background:#f9fafb;border:1px solid #e5e7eb;border-radius:6px;padding:8px;word-break:break-all;\">"
                + esc(String.valueOf(event.getEventMetadata())) + "</div>"));
        }

        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>"
            + "<body style=\"margin:0;padding:0;background:#f3f4f6;font-family:Segoe UI,Roboto,Helvetica,Arial,sans-serif;\">"
            + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#f3f4f6;padding:24px 12px;\">"
            + "<tr><td align=\"center\">"
            + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:600px;"
            + "background:#ffffff;border-radius:10px;overflow:hidden;border:1px solid #e5e7eb;\">"
            // Header
            + "<tr><td style=\"background:#1e3a8a;padding:20px 24px;\">"
            + "<div style=\"color:#bfdbfe;font-size:12px;letter-spacing:1px;text-transform:uppercase;\">PI Security Co-Pilot</div>"
            + "<div style=\"color:#ffffff;font-size:20px;font-weight:600;margin-top:4px;\">Audit trail notification</div>"
            + "</td></tr>"
            // Severity strip
            + "<tr><td style=\"height:4px;background:" + severityColor(severity) + ";line-height:4px;font-size:0;\">&nbsp;</td></tr>"
            // Summary
            + "<tr><td style=\"padding:24px 24px 8px 24px;\">"
            + "<div style=\"font-size:18px;font-weight:600;color:#111827;\">" + esc(label(event.getAction()))
            + (event.getTargetName() != null ? ": " + esc(event.getTargetName()) : "") + "</div>"
            + "<div style=\"font-size:14px;color:#6b7280;margin-top:6px;\">A new event was recorded in the audit trail.</div>"
            + "</td></tr>"
            // Details
            + "<tr><td style=\"padding:8px 24px 24px 24px;\">"
            + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"border-collapse:collapse;font-size:14px;\">"
            + rows
            + "</table></td></tr>"
            // Footer
            + "<tr><td style=\"background:#f9fafb;border-top:1px solid #e5e7eb;padding:16px 24px;font-size:12px;color:#6b7280;\">"
            + "Open the Audit Trail in PI Security Co-Pilot for the full history.<br>"
            + "This is an automated message, please do not reply."
            + "</td></tr>"
            + "</table></td></tr></table></body></html>";
    }

    private static String row(String name, String valueHtml) {
        return "<tr><td style=\"padding:10px 12px 10px 0;border-bottom:1px solid #f3f4f6;color:#6b7280;width:110px;"
            + "vertical-align:top;white-space:nowrap;\">" + name + "</td>"
            + "<td style=\"padding:10px 0;border-bottom:1px solid #f3f4f6;color:#111827;vertical-align:top;\">"
            + valueHtml + "</td></tr>";
    }

    private static String badge(String text, String color, String background) {
        return "<span style=\"display:inline-block;padding:2px 10px;border-radius:999px;font-size:12px;font-weight:600;"
            + "color:" + color + ";background:" + background + ";\">" + esc(text) + "</span>";
    }

    private static String severityColor(Severity severity) {
        return switch (severity) {
            case CRITICAL -> "#b91c1c";
            case HIGH -> "#ea580c";
            case MEDIUM -> "#d97706";
            case LOW -> "#2563eb";
            case INFO -> "#6b7280";
        };
    }

    private static String esc(String value) {
        return value == null ? "-" : HtmlUtils.htmlEscape(value);
    }

    /** SCAN_APPROVED -> Scan approved. */
    private static String label(String action) {
        String words = action.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
