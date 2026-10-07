package com.str.backend.email;

import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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
        service(TESTER).sendRnIssuedNotification(new RnIssuedMail(LESSOR, "Ana", "HR123456789012345678",
                "Apartman Sunce", new byte[]{1, 2, 3}, true));

        MimeMessage sent = sent();
        assertThat(recipients(sent)).containsExactly(TESTER);
        assertThat(sent.getSubject()).startsWith("[za: " + LESSOR + "] ");
    }

    /** Non-EU: e-pošta je dostava, pa dokument ide u privitku. */
    @Test
    void rnIssued_nonEu_attachesPdf() throws Exception {
        boolean ok = service(null).sendRnIssuedNotification(new RnIssuedMail(LESSOR, "John",
                "HR123456789012345678", "Apartman Sunce", new byte[]{1, 2, 3}, true));

        MimeMessage sent = sent();
        sent.saveChanges();
        assertThat(ok).isTrue();
        assertThat(sent.getSubject()).isEqualTo(
                "Registracijski broj HR123456789012345678 je izdan · Registration number issued — eTurizam STR");
        assertThat(sent.getContent()).isInstanceOf(MimeMultipart.class);
        assertThat(attachmentNames((MimeMultipart) sent.getContent()))
                .containsExactly("dodjela-HR123456789012345678.pdf");
        assertThat(html(sent.getContent())).contains("dostavlja se elektroničkom poštom");
    }

    /**
     * Iznajmljivač s OIB-om: obavijest s brojem i objektom, bez privitka — obavijest o dodjeli
     * ide u korisnički pretinac, a poruka to mora reći.
     */
    @Test
    void rnIssued_withOib_isNoticeWithoutAttachment() throws Exception {
        boolean ok = service(null).sendRnIssuedNotification(new RnIssuedMail(LESSOR, "Ana",
                "HR123456789012345678", "Apartman Sunce, Ilica 1, Zagreb", null, false));

        MimeMessage sent = sent();
        sent.saveChanges();
        assertThat(ok).isTrue();
        assertThat(sent.getSubject()).isEqualTo("Izdan registracijski broj HR123456789012345678 — eTurizam STR");
        assertThat(sent.getContent()).isInstanceOf(String.class);
        String body = (String) sent.getContent();
        assertThat(body).contains("HR123456789012345678");
        assertThat(body).contains("Apartman Sunce, Ilica 1, Zagreb");
        assertThat(body).contains("korisnički pretinac");
        assertThat(body).doesNotContain("privitku");
    }

    /** Neuspjeh se mora vidjeti — inače pozivatelj označi obavijest kao poslanu. */
    @Test
    void rnIssued_smtpFailure_returnsFalse() {
        doThrow(new MailSendException("relay down")).when(mailSender).send(any(MimeMessage.class));

        boolean ok = service(null).sendRnIssuedNotification(new RnIssuedMail(LESSOR, "Ana",
                "HR123456789012345678", "Apartman Sunce", null, false));

        assertThat(ok).isFalse();
    }

    /** Mail po registraciji nosi pristupne podatke; lozinka je u tijelu, nikad u naslovu. */
    @Test
    void registration_bodyCarriesCredentials_subjectDoesNot() throws Exception {
        boolean ok = service(null).sendRegistrationNotification(LESSOR, "John", LESSOR, "Tajna<123>!");

        MimeMessage sent = sent();
        sent.saveChanges();
        assertThat(ok).isTrue();
        assertThat(recipients(sent)).containsExactly(LESSOR);
        assertThat(sent.getSubject()).isEqualTo(subject(MailTemplate.REGISTRACIJA)).doesNotContain("Tajna");
        String body = (String) sent.getContent();
        assertThat(body).contains(LESSOR);
        assertThat(body).contains("Tajna&lt;123&gt;!");
        assertThat(body).contains("https://str.example.com/login");
    }

    @Test
    void registration_smtpFailure_returnsFalse() {
        doThrow(new MailSendException("relay down")).when(mailSender).send(any(MimeMessage.class));

        assertThat(service(null).sendRegistrationNotification(LESSOR, "John", LESSOR, "x")).isFalse();
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
                "https://str.example.com/login", null)).subject(template, Map.of());
    }

    private MimeMessage sent() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }

    /** HTML tijelo poruke; s privitkom je ugniježđeno u multipart dijelove. */
    private static String html(Object content) throws Exception {
        if (content instanceof String s) {
            return s;
        }
        MimeMultipart multipart = (MimeMultipart) content;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            if (part.getFileName() == null) {
                out.append(html(part.getContent()));
            }
        }
        return out.toString();
    }

    private static List<String> attachmentNames(MimeMultipart multipart) throws Exception {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            if (part.getFileName() != null) {
                names.add(part.getFileName());
            }
        }
        return names;
    }

    private static String[] recipients(MimeMessage message) throws Exception {
        return Arrays.stream(message.getRecipients(Message.RecipientType.TO))
                .map(Object::toString)
                .toArray(String[]::new);
    }
}
