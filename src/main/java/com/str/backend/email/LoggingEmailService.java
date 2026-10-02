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
     * Vraća {@code false}: poruka nije poslana, pa {@code rn_email_sent_at} ostaje prazan i baza
     * govori istinu. U red ponovnog slanja ne ide — {@link MailRetryStore} dok je mail ugašen ne
     * bilježi ništa.
     */
    @Override
    public boolean sendRnIssuedNotification(RnIssuedMail mail) {
        log.info("[mail/mock] RN_ISSUED → to={}, ime={}, rn={}, objekt={}, dostavaMailom={}, pdf_bytes={}",
                mail.to(), mail.ime(), mail.rn(), mail.objekt(), mail.dostavaMailom(),
                mail.pdf() == null ? 0 : mail.pdf().length);
        return false;
    }

    /** {@code false} iz istog razloga kao {@link #sendRnIssuedNotification}. */
    @Override
    public boolean sendRnLifecycleNotification(RnLifecycleMail mail) {
        log.info("[mail/mock] RN_LIFECYCLE {} → to={}, rn={}, razlog={}, pdf_bytes={}",
                mail.template(), mail.to(), mail.rn(), mail.razlog(),
                mail.pdf() == null ? 0 : mail.pdf().length);
        return false;
    }
}
