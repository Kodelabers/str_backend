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
}
