package com.str.backend.email;

import com.str.backend.email.event.RegistrationApprovedEvent;
import com.str.backend.email.event.RegistrationRejectedEvent;
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

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onApproved(RegistrationApprovedEvent event) {
        if (event.email() == null || event.email().isBlank()) {
            log.warn("Skipping approval email for lessor {} — no email on record", event.lessorId());
            return;
        }
        emailService.sendApprovalNotification(event.email(), event.firstName(), event.username());
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
