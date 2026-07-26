package com.str.backend.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class SmtpEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailService.class);

    private final JavaMailSender mailSender;
    private final MailProperties properties;
    private final EmailTemplates templates;

    public SmtpEmailService(JavaMailSender mailSender, MailProperties properties,
                            EmailTemplates templates) {
        this.mailSender = mailSender;
        this.properties = properties;
        this.templates = templates;
    }

    @Override
    public void sendApprovalNotification(String to, String firstName, String username) {
        send(to, MailTemplate.ODOBRENJE,
                Map.of("ime", nn(firstName), "korisnickoIme", nn(username)));
    }

    @Override
    public void sendRejectionNotification(String to, String firstName) {
        send(to, MailTemplate.ODBIJANJE, Map.of("ime", nn(firstName)));
    }

    @Override
    public void sendRnIssuedNotification(String to, String firstName, String registrationNumber,
                                         byte[] pdf) {
        sendWithAttachment(to, MailTemplate.RB_IZDAN,
                Map.of("ime", nn(firstName), "rn", nn(registrationNumber)),
                "registracija-" + registrationNumber + ".pdf", pdf);
    }

    @Override
    public void sendRnLifecycleNotification(RnLifecycleMail mail) {
        Map<String, String> values = new HashMap<>();
        values.put("ime", nn(mail.ime()));
        values.put("rn", nn(mail.rn()));
        values.put("objekt", nn(mail.objekt()));
        values.put("razlog", nn(mail.razlog()));
        values.put("rok", nn(mail.rok()));

        if (mail.pdf() == null || mail.pdf().length == 0) {
            send(mail.to(), mail.template(), values, mail.dostavaMailom());
        } else {
            sendWithAttachment(mail.to(), mail.template(), values,
                    mail.template().slug() + "-" + mail.rn() + ".pdf", mail.pdf(),
                    mail.dostavaMailom());
        }
    }

    private void send(String to, MailTemplate template, Map<String, String> values) {
        send(to, template, values, false);
    }

    private void send(String to, MailTemplate template, Map<String, String> values,
                      boolean dostavaMailom) {
        String subject = templates.subject(template);
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.from());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(templates.body(template, values, dostavaMailom), true);
            mailSender.send(message);
            log.info("Sent email to {} with subject '{}'", to, subject);
        } catch (MessagingException | MailException e) {
            // Caller (TransactionalEventListener) treats this as a notification failure —
            // the underlying status change has already been committed.
            log.error("Failed to send email to {} with subject '{}': {}", to, subject, e.getMessage(), e);
        }
    }

    private void sendWithAttachment(String to, MailTemplate template, Map<String, String> values,
                                    String attachmentName, byte[] attachment) {
        sendWithAttachment(to, template, values, attachmentName, attachment, false);
    }

    private void sendWithAttachment(String to, MailTemplate template, Map<String, String> values,
                                    String attachmentName, byte[] attachment,
                                    boolean dostavaMailom) {
        String subject = templates.subject(template);
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.from());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(templates.body(template, values, dostavaMailom), true);
            if (attachment != null && attachment.length > 0) {
                helper.addAttachment(attachmentName, new ByteArrayResource(attachment));
            }
            mailSender.send(message);
            log.info("Sent email to {} with subject '{}' and attachment '{}' ({} bytes)",
                    to, subject, attachmentName, attachment == null ? 0 : attachment.length);
        } catch (MessagingException | MailException e) {
            log.error("Failed to send email to {} with subject '{}': {}", to, subject, e.getMessage(), e);
        }
    }

    private static String nn(String value) {
        return value == null ? "" : value;
    }
}
