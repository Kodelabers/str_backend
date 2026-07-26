package com.str.backend.email;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Generator uzoraka poruka — nije test, vidi AktPreviewGenerator. */
class MailPreviewGenerator {

    private static final String RN = "HR180000123456789001";
    private static final Path OUT = Path.of("target", "preview");

    @Disabled("Alat, ne test — pokrenuti ručno: mvn test -Dtest=MailPreviewGenerator nakon izmjene predloška")
    @Test
    void generate() throws IOException {
        Files.createDirectories(OUT);
        MailTemplateLoader loader = new MailTemplateLoader();
        loader.loadAll();
        EmailTemplates templates = new EmailTemplates(loader,
                new MailProperties(true, "str@mint.hr", "https://str-test-eturizam.gov.hr/login"));

        for (MailTemplate t : MailTemplate.values()) {
            String html = "<!-- SUBJECT: " + templates.subject(t) + " -->\n"
                    + templates.body(t, values("Ana"), false);
            Files.writeString(OUT.resolve("mail-" + t.slug() + ".html"), html);
        }
        String nonEu = "<!-- SUBJECT: " + templates.subject(MailTemplate.SUSPENZIJA) + " -->\n"
                + templates.body(MailTemplate.SUSPENZIJA, values("John"), true);
        Files.writeString(OUT.resolve("mail-suspenzija-nonEU.html"), nonEu);
        System.out.println("MAIL PREVIEW OK -> " + OUT.toAbsolutePath());
    }

    private static Map<String, String> values(String ime) {
        Map<String, String> v = new HashMap<>();
        v.put("ime", ime);
        v.put("korisnickoIme", "ana.anic");
        v.put("rn", RN);
        v.put("objekt", "Apartman Sunce, Ilica 1");
        v.put("razlog", "istek suglasnosti suvlasnika");
        v.put("rok", "najkasnije do 15.08.2026.");
        return v;
    }
}
