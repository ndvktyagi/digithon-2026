package com.picopilot.audit.service;

import com.picopilot.audit.model.AuditEvent;
import com.picopilot.common.model.Severity;
import com.picopilot.identity.repository.WorkspaceRepository;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
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
        when(sender.createMimeMessage()).thenAnswer(inv -> new MimeMessage((Session) null));
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
    void sendsHtmlEmailWithPlainTextFallbackToEveryRecipient() throws Exception {
        AuditEvent event = event("SCAN_APPROVED", Severity.INFO);
        event.setTargetName("<script>alert(1)</script>");
        notifier(true, "a@x.io, b@x.io", "*", "INFO").onAuditRecorded(new AuditEmailNotifier.AuditRecorded(event));
        ArgumentCaptor<MimeMessage> mail = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(mail.capture());
        MimeMessage message = mail.getValue();
        message.saveChanges();

        assertThat(Arrays.stream(message.getRecipients(Message.RecipientType.TO)).map(Address::toString))
            .containsExactly("a@x.io", "b@x.io");
        assertThat(message.getFrom()[0].toString()).isEqualTo("pi@x.io");
        assertThat(message.getSubject()).isEqualTo("[PI] Scan approved: <script>alert(1)</script>");

        // Round-trip through the wire format so the parts are read back exactly as a mail client would
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        message.writeTo(raw);
        MimeMessage received = new MimeMessage(null, new ByteArrayInputStream(raw.toByteArray()));
        assertThat(textPart(received, "text/plain")).contains("Action:     Scan approved (SCAN_APPROVED)");
        assertThat(textPart(received, "text/html")).contains("Audit trail notification")
            .contains("Scan approved: &lt;script&gt;").doesNotContain("<script>");
    }

    private static String textPart(Part part, String mimeType) throws Exception {
        if (part.isMimeType(mimeType)) return (String) part.getContent();
        if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                String found = textPart(multipart.getBodyPart(i), mimeType);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    void doesNotSendWhenDisabled() {
        notifier(false, "a@x.io", "*", "INFO")
            .onAuditRecorded(new AuditEmailNotifier.AuditRecorded(event("SCAN_APPROVED", Severity.INFO)));
        verify(sender, never()).send(any(MimeMessage.class));
    }
}
