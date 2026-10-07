package com.str.backend.email;

import com.str.backend.email.event.RegistrationRejectedEvent;
import com.str.backend.email.event.RegistrationSubmittedEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class RegistrationEmailListenerTest {

    private final EmailService emailService = mock(EmailService.class);
    private final RegistrationEmailListener listener = new RegistrationEmailListener(emailService);

    @Test
    void submitted_sendsCredentials() {
        listener.onSubmitted(new RegistrationSubmittedEvent(
                UUID.randomUUID(), "john@example.com", "John", "john@example.com", "Tajna123!"));

        verify(emailService).sendRegistrationNotification(
                "john@example.com", "John", "john@example.com", "Tajna123!");
    }

    @Test
    void submitted_withoutEmail_sendsNothing() {
        listener.onSubmitted(new RegistrationSubmittedEvent(
                UUID.randomUUID(), " ", "John", "john@example.com", "Tajna123!"));

        verify(emailService, never()).sendRegistrationNotification(any(), any(), any(), any());
    }

    @Test
    void rejected_withoutEmail_sendsNothing() {
        listener.onRejected(new RegistrationRejectedEvent(UUID.randomUUID(), null, "John", "john@example.com"));

        verifyNoInteractions(emailService);
    }
}
