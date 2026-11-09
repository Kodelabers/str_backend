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

    /** Iznajmljivač s OIB-om: poruka, preslika akta u privitku i poveznica na pretinac (MINTS, T4). */
    @Test
    void rnIssued_withOib_attachesActAndLinksToKp() throws Exception {
        boolean ok = service(null, "https://kp.example.hr/inbox").sendRnIssuedNotification(new RnIssuedMail(LESSOR,
                "Ana", "HR123456789012345678", "Apartman Sunce, Ilica 1, Zagreb", new byte[]{1, 2, 3}, false));

        MimeMessage sent = sent();
        sent.saveChanges();
        assertThat(ok).isTrue();
        assertThat(sent.getSubject()).isEqualTo("Izdan registracijski broj HR123456789012345678 — eTurizam STR");
        assertThat(attachmentNames((MimeMultipart) sent.getContent()))
                .containsExactly("dodjela-HR123456789012345678.pdf");
        assertThat(html(sent.getContent()))
                .contains("Apartman Sunce, Ilica 1, Zagreb")
                .contains("U privitku ove poruke nalazi se preslika akta")
                .contains("href=\"https://kp.example.hr/inbox\"");
    }

    /** Non-EU poruka ostaje ista: nema poveznice ni tvrdnje o pretincu ni uz postavljenu adresu. */
    @Test
    void rnIssued_nonEu_hasNoKpLinkEvenWhenConfigured() throws Exception {
        service(null, "https://kp.example.hr/inbox").sendRnIssuedNotification(new RnIssuedMail(LESSOR, "John",
                "HR123456789012345678", "Apartman Sunce", new byte[]{1, 2, 3}, true));

        MimeMessage sent = sent();
        sent.saveChanges();
        assertThat(html(sent.getContent()))
                .doesNotContain("kp.example.hr")
                .doesNotContain("korisnički pretinac")
                .contains("dostavlja se elektroničkom poštom");
    }

    /** Akt je razlog za privitak: prilog bez akta ne smije poruku natjerati da tvrdi da je akt tu. */
    @Test
    void rnLifecycle_attachmentsWithoutAct_doesNotClaimActAttached() throws Exception {
        service(null).sendRnLifecycleNotification(new RnLifecycleMail(MailTemplate.SUSPENZIJA,
                LESSOR, "Ana", "HR123456789012345678", "Apartman Sunce", "razlog", "rok",
                null, Dostava.PRETINAC, List.of(new MailPrilog("zapisnik.pdf", "application/pdf", new byte[]{4})),
                List.of()));

        MimeMessage sent = sent();
        sent.saveChanges();
        assertThat(html(sent.getContent())).doesNotContain("preslika akta");
    }

    /**
     * Iznajmljivač s OIB-om kad akta nema ni nakon čekanja: obavijest s brojem i objektom, bez
     * privitka — poruka tada ne smije spominjati privitak.
     */
    @Test
    void rnIssued_withOib_noAct_isNoticeWithoutAttachment() throws Exception {
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

    /** T17: akt i prilozi službenika idu u privitak, a poruka ih navodi iznad klauzule o dostavi. */
    @Test
    void rnLifecycle_withAttachments_attachesActAndAttachments() throws Exception {
        IshodSlanja ok = service(null).sendRnLifecycleNotification(new RnLifecycleMail(MailTemplate.SUSPENZIJA,
                LESSOR, "Ana", "HR123456789012345678", "Apartman Sunce", "nepotpuna dokumentacija", "rok",
                new byte[]{1, 2, 3}, Dostava.PRETINAC,
                List.of(new MailPrilog("zapisnik.pdf", "application/pdf", new byte[]{4}),
                        new MailPrilog("fotografija.jpg", "image/jpeg", new byte[]{5})),
                List.of()));

        MimeMessage sent = sent();
        sent.saveChanges();
        assertThat(ok).isEqualTo(IshodSlanja.POSLANA);
        assertThat(attachmentNames((MimeMultipart) sent.getContent()))
                .containsExactly("suspenzija-HR123456789012345678.pdf", "zapisnik.pdf", "fotografija.jpg");
        String html = html(sent.getContent());
        assertThat(html).contains("U privitku su i prilozi akta: zapisnik.pdf, fotografija.jpg.");
        assertThat(html.indexOf("prilozi akta")).isLessThan(html.indexOf("Ovo je obavijest, a ne dostava akta"));
    }

    /** Prilozi preveliki za e-poštu se samo navode, uz uputu gdje su; u privitku je samo akt. */
    @Test
    void rnLifecycle_attachmentsOverLimit_areListedWithPointer() throws Exception {
        service(null).sendRnLifecycleNotification(new RnLifecycleMail(MailTemplate.POVLACENJE,
                LESSOR, "John", "HR123456789012345678", "Apartman Sunce", "razlog", "rok",
                new byte[]{1, 2, 3}, Dostava.EPOSTA, List.of(), List.of("zapisnik.pdf", "fotografije.pdf")));

        MimeMessage sent = sent();
        sent.saveChanges();
        assertThat(attachmentNames((MimeMultipart) sent.getContent()))
                .containsExactly("povlacenje-HR123456789012345678.pdf");
        String html = html(sent.getContent());
        assertThat(html).contains("zapisnik.pdf, fotografije.pdf")
                .contains("u aplikaciji eTurizam STR pod „Moji registracijski brojevi“")
                .doesNotContain("korisničkom pretincu i u aplikaciji");
    }

    /** Bez priloga poruka nema ni rečenice o njima. */
    @Test
    void rnLifecycle_withoutAttachments_hasNoAttachmentSentence() throws Exception {
        service(null).sendRnLifecycleNotification(new RnLifecycleMail(MailTemplate.SUSPENZIJA,
                LESSOR, "Ana", "HR123456789012345678", "Apartman Sunce", "razlog", "rok",
                new byte[]{1, 2, 3}, Dostava.PRETINAC, List.of(), List.of()));

        MimeMessage sent = sent();
        sent.saveChanges();
        assertThat(html(sent.getContent())).doesNotContain("prilozi akta").doesNotContain("Prilozi akta");
    }

    /** Relay odbije poruku zbog veličine (552 / 5.3.4): ishod je PREVELIKA, ne obični neuspjeh. */
    @Test
    void rnLifecycle_sizeRejection_isTooLarge() {
        doThrow(new MailSendException("Failed messages",
                new jakarta.mail.MessagingException("552 5.3.4 Message size exceeds fixed limit")))
                .when(mailSender).send(any(MimeMessage.class));

        IshodSlanja ishod = service(null).sendRnLifecycleNotification(lifecycleWithAttachment());

        assertThat(ishod).isEqualTo(IshodSlanja.PREVELIKA);
    }

    @Test
    void rnLifecycle_otherFailure_isFailed() {
        doThrow(new MailSendException("Connection refused")).when(mailSender).send(any(MimeMessage.class));

        assertThat(service(null).sendRnLifecycleNotification(lifecycleWithAttachment()))
                .isEqualTo(IshodSlanja.NEUSPJELA);
    }

    @Test
    void isMessageTooLarge_recognizesServerReplies() {
        assertThat(SmtpEmailService.isMessageTooLarge(new RuntimeException("552 Message too large"))).isTrue();
        assertThat(SmtpEmailService.isMessageTooLarge(new RuntimeException("x",
                new RuntimeException("5.3.4 Message size exceeds fixed maximum message size")))).isTrue();
        assertThat(SmtpEmailService.isMessageTooLarge(new RuntimeException("550 5.1.1 User unknown"))).isFalse();
        assertThat(SmtpEmailService.isMessageTooLarge(new RuntimeException("timeout after 15520 ms"))).isFalse();
    }

    private static RnLifecycleMail lifecycleWithAttachment() {
        return new RnLifecycleMail(MailTemplate.SUSPENZIJA, LESSOR, "Ana", "HR123456789012345678", "Apartman Sunce",
                "razlog", "rok", new byte[]{1, 2, 3}, Dostava.PRETINAC,
                List.of(new MailPrilog("zapisnik.pdf", "application/pdf", new byte[]{4})), List.of());
    }

    /** Prazna env varijabla ({@code APP_MAIL_REDIRECT_TO=}) ne smije preusmjeriti na praznu adresu. */
    @Test
    void blankRedirect_meansNoRedirect() throws Exception {
        service("  ").sendRejectionNotification(LESSOR, "Ana");

        assertThat(recipients(sent())).containsExactly(LESSOR);
    }

    private SmtpEmailService service(String redirectTo) {
        return service(redirectTo, null);
    }

    private SmtpEmailService service(String redirectTo, String kpUrl) {
        MailProperties properties = new MailProperties(true, "str@example.com",
                "https://str.example.com/login", redirectTo, kpUrl);
        return new SmtpEmailService(mailSender, properties, new EmailTemplates(loader, properties));
    }

    private String subject(MailTemplate template) {
        return new EmailTemplates(loader, new MailProperties(true, "str@example.com",
                "https://str.example.com/login", null, null)).subject(template, Map.of());
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
