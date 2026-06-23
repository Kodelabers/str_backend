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

    @Override
    public void sendRnIssuedNotification(String to, String firstName, String registrationNumber, byte[] pdf) {
        log.info("[mail/mock] RN_ISSUED → to={}, firstName={}, rn={}, pdf_bytes={}",
                to, firstName, registrationNumber, pdf == null ? 0 : pdf.length);
    }
}
