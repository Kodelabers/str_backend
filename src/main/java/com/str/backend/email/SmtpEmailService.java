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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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
    public boolean sendRegistrationNotification(String to, String firstName, String username,
                                                String password) {
        return send(to, MailTemplate.REGISTRACIJA,
                Map.of("ime", nn(firstName), "korisnickoIme", nn(username), "lozinka", nn(password)));
    }

    @Override
    public void sendRejectionNotification(String to, String firstName) {
        send(to, MailTemplate.ODBIJANJE, Map.of("ime", nn(firstName)));
    }

    @Override
    public boolean sendRnIssuedNotification(RnIssuedMail mail) {
        if (mail.dostavaMailom()) {
            // Privitak je akt (obavijest o dodjeli), pa poruka nosi klauzulu o dostavi e-poštom.
            return sendWithAttachment(mail.to(), MailTemplate.RB_IZDAN_DOSTAVA,
                    Map.of("ime", nn(mail.ime()), "rn", nn(mail.rn())),
                    "dodjela-" + mail.rn() + ".pdf", mail.pdf(), Dostava.EPOSTA);
        }
        Map<String, String> values = Map.of("ime", nn(mail.ime()), "rn", nn(mail.rn()), "objekt", nn(mail.objekt()));
        if (mail.pdf() == null || mail.pdf().length == 0) {
            return send(mail.to(), MailTemplate.RB_IZDAN, values);
        }
        return sendWithAttachment(mail.to(), MailTemplate.RB_IZDAN, values,
                "dodjela-" + mail.rn() + ".pdf", mail.pdf(), Dostava.PRETINAC);
    }

    @Override
    public IshodSlanja sendRnLifecycleNotification(RnLifecycleMail mail) {
        Map<String, String> values = new HashMap<>();
        values.put("ime", nn(mail.ime()));
        values.put("rn", nn(mail.rn()));
        values.put("objekt", nn(mail.objekt()));
        values.put("razlog", nn(mail.razlog()));
        values.put("rok", nn(mail.rok()));
        values.put(EmailTemplates.PRILOZI, priloziTekst(mail));

        boolean aktUPrivitku = mail.pdf() != null && mail.pdf().length > 0;
        List<Privitak> privitci = new ArrayList<>();
        if (aktUPrivitku) {
            privitci.add(new Privitak(mail.template().slug() + "-" + mail.rn() + ".pdf", mail.pdf(), null));
        }
        mail.prilozi().forEach(p -> privitci.add(new Privitak(p.naziv(), p.sadrzaj(), p.contentType())));
        if (privitci.isEmpty()) {
            return send(mail.to(), mail.template(), values, mail.dostava()) ? IshodSlanja.POSLANA : IshodSlanja.NEUSPJELA;
        }
        try {
            deliverWithAttachments(mail.to(), mail.template(), values, privitci, mail.dostava(), aktUPrivitku);
            return IshodSlanja.POSLANA;
        } catch (MessagingException | MailException e) {
            boolean prevelika = isMessageTooLarge(e);
            log.error("Failed to send email to {} with subject '{}'{}: {}", mail.to(),
                    templates.subject(mail.template(), values), prevelika ? " (message too large)" : "",
                    e.getMessage(), e);
            return prevelika ? IshodSlanja.PREVELIKA : IshodSlanja.NEUSPJELA;
        }
    }

    /**
     * Je li poslužitelj odbio poruku zbog veličine. JavaMail kod vraća samo u tekstu iznimke
     * ({@code SMTPSendFailedException} je u implementaciji, ne u API-ju), pa se čita odgovor
     * poslužitelja: {@code 552} / {@code 5.3.4} (RFC 1870, RFC 3463) ili uobičajeni opis. Lanac uzroka
     * pokriva i {@code MessagingException#getNextException}, koji je ondje {@code getCause}.
     */
    static boolean isMessageTooLarge(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause() == c ? null : c.getCause()) {
            String poruka = c.getMessage();
            if (poruka != null && PREVELIKA.matcher(poruka).find()) {
                return true;
            }
        }
        return false;
    }

    private static final Pattern PREVELIKA = Pattern.compile(
            "(^|\\D)552(\\D|$)|5\\.3\\.4|message size|too large|too big|size exceeds|exceeds .*size",
            Pattern.CASE_INSENSITIVE);

    /**
     * Rečenica o prilozima akta (T17); prazno kad ih nema. Prilozi preveliki za e-poštu samo se
     * navode, uz uputu gdje su — non-EU iznajmljivač nema korisnički pretinac.
     */
    private static String priloziTekst(RnLifecycleMail mail) {
        if (!mail.prilozi().isEmpty()) {
            return "U privitku su i prilozi akta: "
                    + String.join(", ", mail.prilozi().stream().map(MailPrilog::naziv).toList()) + ".";
        }
        if (!mail.priloziIzvanPoruke().isEmpty()) {
            String gdje = mail.dostava() == Dostava.PRETINAC
                    ? "Dostupni su uz akt u Vašem korisničkom pretincu i u aplikaciji eTurizam STR"
                    : "Dostupni su uz akt u aplikaciji eTurizam STR";
            return "Prilozi akta nisu u privitku ove poruke jer su zajedno preveliki za e-poštu: "
                    + String.join(", ", mail.priloziIzvanPoruke()) + ". "
                    + gdje + " pod „Moji registracijski brojevi“.";
        }
        return "";
    }

    /** Datoteka u privitku; {@code contentType} {@code null} = po nazivu datoteke. */
    private record Privitak(String naziv, byte[] sadrzaj, String contentType) {}

    private boolean send(String to, MailTemplate template, Map<String, String> values) {
        return send(to, template, values, Dostava.PRETINAC);
    }

    /** @return je li poruka predana SMTP poslužitelju; greška se bilježi, ne propagira. */
    private boolean send(String to, MailTemplate template, Map<String, String> values,
                         Dostava dostava) {
        String subject = templates.subject(template, values);
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.from());
            String recipient = address(helper, to, subject);
            helper.setText(templates.body(template, values, dostava), true);
            mailSender.send(message);
            log.info("Sent email to {} with subject '{}'", recipient, subject);
            return true;
        } catch (MessagingException | MailException e) {
            // Caller (TransactionalEventListener) treats this as a notification failure —
            // the underlying status change has already been committed.
            log.error("Failed to send email to {} with subject '{}': {}", to, subject, e.getMessage(), e);
            return false;
        }
    }

    private boolean sendWithAttachment(String to, MailTemplate template, Map<String, String> values,
                                       String attachmentName, byte[] attachment) {
        return sendWithAttachment(to, template, values, attachmentName, attachment, Dostava.PRETINAC);
    }

    private boolean sendWithAttachment(String to, MailTemplate template, Map<String, String> values,
                                       String attachmentName, byte[] attachment,
                                       Dostava dostava) {
        List<Privitak> privitci = attachment == null || attachment.length == 0
                ? List.of()
                : List.of(new Privitak(attachmentName, attachment, null));
        return sendWithAttachments(to, template, values, privitci, dostava);
    }

    /** @return je li poruka predana SMTP poslužitelju; greška se bilježi, ne propagira. */
    private boolean sendWithAttachments(String to, MailTemplate template, Map<String, String> values,
                                        List<Privitak> privitci, Dostava dostava) {
        try {
            deliverWithAttachments(to, template, values, privitci, dostava, !privitci.isEmpty());
            return true;
        } catch (MessagingException | MailException e) {
            log.error("Failed to send email to {} with subject '{}': {}", to, templates.subject(template, values),
                    e.getMessage(), e);
            return false;
        }
    }

    private void deliverWithAttachments(String to, MailTemplate template, Map<String, String> values,
                                        List<Privitak> privitci, Dostava dostava,
                                        boolean aktUPrivitku) throws MessagingException {
        String subject = templates.subject(template, values);
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
        helper.setFrom(properties.from());
        String recipient = address(helper, to, subject);
        helper.setText(templates.body(template, values, dostava, aktUPrivitku), true);
        for (Privitak privitak : privitci) {
            ByteArrayResource resource = new ByteArrayResource(privitak.sadrzaj());
            if (privitak.contentType() == null) {
                helper.addAttachment(privitak.naziv(), resource);
            } else {
                helper.addAttachment(privitak.naziv(), resource, privitak.contentType());
            }
        }
        mailSender.send(message);
        log.info("Sent email to {} with subject '{}' and attachments {} ({} bytes)",
                recipient, subject, privitci.stream().map(Privitak::naziv).toList(),
                privitci.stream().mapToLong(p -> p.sadrzaj().length).sum());
    }

    /**
     * Postavlja primatelja i predmet. Uz {@code app.mail.redirect-to} poruka ide na tu adresu, a
     * stvarni primatelj ide u predmet: testna okolina nad stvarnim podacima eTurizma tako ne šalje
     * poštu stvarnim iznajmljivačima. Vraća opis primatelja za log.
     */
    private String address(MimeMessageHelper helper, String to, String subject) throws MessagingException {
        String redirectTo = properties.redirectTo();
        if (redirectTo == null) {
            helper.setTo(to);
            helper.setSubject(subject);
            return to;
        }
        helper.setTo(redirectTo);
        helper.setSubject("[za: " + to + "] " + subject);
        return redirectTo + " (redirected, intended for " + to + ")";
    }

    private static String nn(String value) {
        return value == null ? "" : value;
    }
}
