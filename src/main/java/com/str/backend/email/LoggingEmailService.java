package com.str.backend.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoggingEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailService.class);

    @Override
    public void sendApprovalNotification(String to, String firstName, String username) {
        log.info("[mail/mock] APPROVAL → to={}, firstName={}, username={}", to, firstName, username);
    }

    @Override
    public void sendRejectionNotification(String to, String firstName) {
        log.info("[mail/mock] REJECTION → to={}, firstName={}", to, firstName);
    }

    /**
     * Vraća {@code false}: poruka nije poslana, pa se ne smije zabilježiti kao poslana. Inače
     * RB izdan dok je mail ugašen ne bi dobio obavijest ni nakon što se SMTP upali.
     */
    @Override
    public boolean sendRnIssuedNotification(RnIssuedMail mail) {
        log.info("[mail/mock] RN_ISSUED → to={}, ime={}, rn={}, objekt={}, dostavaMailom={}, pdf_bytes={}",
                mail.to(), mail.ime(), mail.rn(), mail.objekt(), mail.dostavaMailom(),
                mail.pdf() == null ? 0 : mail.pdf().length);
        return false;
    }

    @Override
    public void sendRnLifecycleNotification(RnLifecycleMail mail) {
        log.info("[mail/mock] RN_LIFECYCLE {} → to={}, rn={}, razlog={}, pdf_bytes={}",
                mail.template(), mail.to(), mail.rn(), mail.razlog(),
                mail.pdf() == null ? 0 : mail.pdf().length);
    }
}
