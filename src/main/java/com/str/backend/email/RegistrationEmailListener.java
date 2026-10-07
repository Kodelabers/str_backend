package com.str.backend.email;

import com.str.backend.email.event.RegistrationRejectedEvent;
import com.str.backend.email.event.RegistrationSubmittedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class RegistrationEmailListener {

    private static final Logger log = LoggerFactory.getLogger(RegistrationEmailListener.class);

    private final EmailService emailService;

    public RegistrationEmailListener(EmailService emailService) {
        this.emailService = emailService;
    }

    /**
     * Pristupni podaci odmah po registraciji — iznajmljivač se prijavljuje bez čekanja na
     * odobrenje. AFTER_COMMIT: lozinka se ne šalje za račun koji nije spremljen.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmitted(RegistrationSubmittedEvent event) {
        if (event.email() == null || event.email().isBlank()) {
            log.warn("Skipping registration email for lessor {} — no email on record", event.lessorId());
            return;
        }
        if (!emailService.sendRegistrationNotification(
                event.email(), event.firstName(), event.username(), event.password())) {
            log.warn("Registration email for lessor {} not sent — no retry, the password is not stored",
                    event.lessorId());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRejected(RegistrationRejectedEvent event) {
        if (event.email() == null || event.email().isBlank()) {
            log.warn("Skipping rejection email for lessor {} — no email on record", event.lessorId());
            return;
        }
        emailService.sendRejectionNotification(event.email(), event.firstName());
    }
}
