package com.picopilot.audit.service;

import com.picopilot.audit.model.AuditEvent;
import com.picopilot.common.model.Severity;
import com.picopilot.identity.repository.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditEmailNotifierTest {

    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);

    @SuppressWarnings("unchecked")
    private AuditEmailNotifier notifier(boolean enabled, String to, String actions, String minSeverity) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        when(workspaces.findById(any())).thenReturn(Optional.empty());
        return new AuditEmailNotifier(provider, workspaces, enabled, "pi@x.io", to, actions, minSeverity, "[PI]");
    }

    private static AuditEvent event(String action, Severity severity) {
        AuditEvent e = new AuditEvent();
        e.setAction(action);
        e.setActor("local:admin");
        e.setTargetType("SCAN_JOB");
        e.setTargetId("1");
        e.setTargetName("Nightly scan");
        e.setOutcome("SUCCESS");
        e.setSeverity(severity);
        return e;
    }

    @Test
    void filtersByEnabledRecipientsActionAndSeverity() {
        assertThat(notifier(false, "a@x.io", "*", "INFO").shouldSend(event("SCAN_APPROVED", Severity.INFO))).isFalse();
        assertThat(notifier(true, "", "*", "INFO").shouldSend(event("SCAN_APPROVED", Severity.INFO))).isFalse();
        assertThat(notifier(true, "a@x.io", "*", "INFO").shouldSend(event("SCAN_APPROVED", Severity.INFO))).isTrue();

        AuditEmailNotifier selected = notifier(true, "a@x.io", "scan_approved, FINAL_REVIEW_APPROVE", "INFO");
        assertThat(selected.shouldSend(event("SCAN_APPROVED", Severity.INFO))).isTrue();
        assertThat(selected.shouldSend(event("SCAN_STARTED", Severity.INFO))).isFalse();

        AuditEmailNotifier medium = notifier(true, "a@x.io", "*", "MEDIUM");
        assertThat(medium.shouldSend(event("X", Severity.INFO))).isFalse();
        assertThat(medium.shouldSend(event("X", Severity.MEDIUM))).isTrue();
        assertThat(medium.shouldSend(event("X", Severity.CRITICAL))).isTrue();
    }

    @Test
    void sendsReadableEmailToEveryRecipient() {
        notifier(true, "a@x.io, b@x.io", "*", "INFO")
            .onAuditRecorded(new AuditEmailNotifier.AuditRecorded(event("SCAN_APPROVED", Severity.INFO)));
        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(mail.capture());
        assertThat(mail.getValue().getTo()).containsExactly("a@x.io", "b@x.io");
        assertThat(mail.getValue().getFrom()).isEqualTo("pi@x.io");
        assertThat(mail.getValue().getSubject()).isEqualTo("[PI] Scan approved: Nightly scan");
        assertThat(mail.getValue().getText()).contains("Action:     Scan approved (SCAN_APPROVED)").contains("Actor:      local:admin");
    }

    @Test
    void doesNotSendWhenDisabled() {
        notifier(false, "a@x.io", "*", "INFO")
            .onAuditRecorded(new AuditEmailNotifier.AuditRecorded(event("SCAN_APPROVED", Severity.INFO)));
        verify(sender, never()).send(any(SimpleMailMessage.class));
    }
}
