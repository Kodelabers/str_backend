package com.str.backend.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.nio.charset.StandardCharsets;

public class SmtpEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailService.class);

    private static final String SUBJECT_APPROVED =
            "Registracija odobrena · Registration approved — eTurizam STR";
    private static final String SUBJECT_REJECTED =
            "Registracija odbijena · Registration rejected — eTurizam STR";

    private final JavaMailSender mailSender;
    private final MailProperties properties;

    public SmtpEmailService(JavaMailSender mailSender, MailProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void sendApprovalNotification(String to, String firstName, String username) {
        String html = EmailTemplates.approvalBody(firstName, username, properties.loginUrl());
        send(to, SUBJECT_APPROVED, html);
    }

    @Override
    public void sendRejectionNotification(String to, String firstName) {
        String html = EmailTemplates.rejectionBody(firstName);
        send(to, SUBJECT_REJECTED, html);
    }

    private void send(String to, String subject, String html) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.from());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
            log.info("Sent email to {} with subject '{}'", to, subject);
        } catch (MessagingException | MailException e) {
            // Caller (TransactionalEventListener) treats this as a notification failure —
            // the underlying status change has already been committed.
            log.error("Failed to send email to {} with subject '{}': {}", to, subject, e.getMessage(), e);
        }
    }
}
