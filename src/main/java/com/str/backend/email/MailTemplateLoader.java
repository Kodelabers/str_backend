package com.str.backend.email;

import com.str.backend.document.DocumentTemplateException;
import com.str.backend.document.ZupPlaceholders;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Učitava predloške poruka e-pošte. Format je namjerno minimalan:
 *
 * <pre>
 * [SUBJECT]
 * Naslov poruke
 *
 * [BODY]
 * &lt;h2&gt;...&lt;/h2&gt;
 * </pre>
 *
 * <p>Kao i kod akata, provjera je na startu — nedostajući ili nepotpun predložak ruši
 * podizanje aplikacije umjesto da se otkrije tek kad netko čeka obavijest.
 */
@Component
public class MailTemplateLoader {

    private static final String BASE_PATH = "documents/mail/";
    private static final String SUBJECT_MARKER = "[SUBJECT]";
    private static final String BODY_MARKER = "[BODY]";

    private final Map<MailTemplate, Parsed> cache = new EnumMap<>(MailTemplate.class);

    private record Parsed(String subject, String body) {}

    @PostConstruct
    void loadAll() {
        for (MailTemplate template : MailTemplate.values()) {
            cache.put(template, read(template));
        }
    }

    public String subject(MailTemplate template) {
        return cache.get(template).subject();
    }

    /** Tijelo poruke s vezanim placeholderima; nepoznata oznaka baca, kao i kod akata. */
    public String body(MailTemplate template, Map<String, String> values) {
        Parsed parsed = cache.get(template);
        return ZupPlaceholders.bind(parsed.body(), values, path(template) + " [BODY]");
    }

    private static Parsed read(MailTemplate template) {
        String path = path(template);
        String raw;
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DocumentTemplateException("Predložak poruke nije čitljiv: " + path, e);
        }
        int subjectAt = raw.indexOf(SUBJECT_MARKER);
        int bodyAt = raw.indexOf(BODY_MARKER);
        if (subjectAt < 0 || bodyAt < 0 || bodyAt < subjectAt) {
            throw new DocumentTemplateException(
                    "Predložak " + path + " mora sadržavati " + SUBJECT_MARKER + " pa "
                            + BODY_MARKER + ".");
        }
        String subject = raw.substring(subjectAt + SUBJECT_MARKER.length(), bodyAt).strip();
        String body = raw.substring(bodyAt + BODY_MARKER.length()).strip();
        if (subject.isEmpty() || body.isEmpty()) {
            throw new DocumentTemplateException("Predložak " + path + " ima praznu sekciju.");
        }
        validatePlaceholders(template, subject, body, path);
        return new Parsed(subject, body);
    }

    /**
     * Predložak smije koristiti samo oznake koje mu servis zna napuniti. Provjera je na startu
     * jer bi inače nepoznata oznaka pukla tek pri stvarnom slanju — a na profilima s
     * {@code LoggingEmailService} predlošci se ne renderiraju uopće, pa se kvar ne bi vidio ni
     * u ručnom testiranju.
     */
    private static void validatePlaceholders(MailTemplate template, String subject, String body,
                                             String path) {
        if (!ZupPlaceholders.keysIn(subject).isEmpty()) {
            throw new DocumentTemplateException(
                    "Naslov poruke u " + path + " ne podržava ${...} oznake.");
        }
        Set<String> dopustene = new TreeSet<>(template.placeholders());
        dopustene.addAll(MailTemplate.Keys.ZAJEDNICKE);
        Set<String> nepoznate = new TreeSet<>(ZupPlaceholders.keysIn(body));
        nepoznate.removeAll(dopustene);
        if (!nepoznate.isEmpty()) {
            throw new DocumentTemplateException(
                    "Predložak " + path + " koristi oznake koje servis ne puni: " + nepoznate
                            + ". Dopuštene: " + dopustene
                            + ". Vidi MailTemplate.placeholders() i docs/ZUP-predlosci.md.");
        }
    }

    private static String path(MailTemplate template) {
        return BASE_PATH + template.slug() + ".html";
    }
}
