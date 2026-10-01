package com.str.backend.email;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Arrays;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmtpEmailServiceTest {

    private static final String LESSOR = "iznajmljivac@example.com";
    private static final String TESTER = "tester@example.com";

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private MailTemplateLoader loader;

    @BeforeEach
    void setUp() {
        loader = new MailTemplateLoader();
        loader.loadAll();
        when(mailSender.createMimeMessage())
                .thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
    }

    @Test
    void withoutRedirect_sendsToActualRecipientWithTemplateSubject() throws Exception {
        service(null).sendRejectionNotification(LESSOR, "Ana");

        MimeMessage sent = sent();
        assertThat(recipients(sent)).containsExactly(LESSOR);
        assertThat(sent.getSubject()).isEqualTo(subject(MailTemplate.ODBIJANJE));
    }

    @Test
    void withRedirect_sendsToRedirectAddressAndNamesActualRecipientInSubject() throws Exception {
        service(TESTER).sendRejectionNotification(LESSOR, "Ana");

        MimeMessage sent = sent();
        assertThat(recipients(sent)).containsExactly(TESTER);
        assertThat(sent.getSubject()).isEqualTo("[za: " + LESSOR + "] " + subject(MailTemplate.ODBIJANJE));
    }

    /** Poruke s privitkom (RB izdan, akti životnog ciklusa) idu drugom granom slanja. */
    @Test
    void withRedirect_alsoRedirectsMessagesWithAttachment() throws Exception {
        service(TESTER).sendRnIssuedNotification(LESSOR, "Ana", "HR123456789012345678", new byte[]{1, 2, 3});

        MimeMessage sent = sent();
        assertThat(recipients(sent)).containsExactly(TESTER);
        assertThat(sent.getSubject()).startsWith("[za: " + LESSOR + "] ");
    }

    /** Prazna env varijabla ({@code APP_MAIL_REDIRECT_TO=}) ne smije preusmjeriti na praznu adresu. */
    @Test
    void blankRedirect_meansNoRedirect() throws Exception {
        service("  ").sendRejectionNotification(LESSOR, "Ana");

        assertThat(recipients(sent())).containsExactly(LESSOR);
    }

    private SmtpEmailService service(String redirectTo) {
        MailProperties properties = new MailProperties(true, "str@example.com",
                "https://str.example.com/login", redirectTo);
        return new SmtpEmailService(mailSender, properties, new EmailTemplates(loader, properties));
    }

    private String subject(MailTemplate template) {
        return new EmailTemplates(loader, new MailProperties(true, "str@example.com",
                "https://str.example.com/login", null)).subject(template);
    }

    private MimeMessage sent() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }

    private static String[] recipients(MimeMessage message) throws Exception {
        return Arrays.stream(message.getRecipients(Message.RecipientType.TO))
                .map(Object::toString)
                .toArray(String[]::new);
    }
}
