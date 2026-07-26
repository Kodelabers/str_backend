package com.str.backend.email;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Sastavlja poruku e-pošte: tekst dolazi iz predloška ({@link MailTemplateLoader}), a okvir
 * (omot, gumb, escapiranje) ostaje ovdje jer je izgled, ne tekst.
 *
 * <p>Vrijednosti se escapiraju <b>prije</b> vezanja u predložak — bez toga bi ime s
 * {@code <} u sebi razbilo HTML poruke.
 */
@Component
public class EmailTemplates {

    /**
     * Čl. 94. st. 5–6 ZUP-a: dostava u korisnički pretinac je osobna dostava i od nje teku
     * rokovi. Poruka <b>ne smije tvrditi da je dostava obavljena</b> — KP klijent još ne
     * postoji (BX1) — nego samo reći odakle rokovi teku.
     */
    private static final String KLAUZULA_PRETINAC = """
            <p style="color:#6C757D;font-size:13px;margin-top:20px;">
              Ovo je obavijest, a ne dostava akta. Akt se dostavlja u Vaš korisnički pretinac i
              rokovi teku od te dostave, sukladno članku 94. Zakona o općem upravnom postupku.
            </p>
            """;

    /**
     * Non-EU iznajmljivač nema korisnički pretinac, pa je e-pošta kanal dostave — čl. 94.
     * st. 4 to dopušta kad je stranka zahtjev podnijela elektronički. Za njega rokovi teku
     * od ove poruke i to mu se mora reći.
     */
    private static final String KLAUZULA_EPOSTA = """
            <p style="color:#6C757D;font-size:13px;margin-top:20px;">
              Akt u privitku dostavlja se elektroničkom poštom sukladno članku 94. Zakona o općem
              upravnom postupku; rokovi teku od dana zaprimanja ove poruke.
            </p>
            """;

    private final MailTemplateLoader loader;
    private final MailProperties properties;

    public EmailTemplates(MailTemplateLoader loader, MailProperties properties) {
        this.loader = loader;
        this.properties = properties;
    }

    public String subject(MailTemplate template) {
        return loader.subject(template);
    }

    /** Gotov HTML poruke uz dostavu u korisnički pretinac. */
    public String body(MailTemplate template, Map<String, String> values) {
        return body(template, values, false);
    }

    /**
     * Gotov HTML poruke; {@code values} su sirove vrijednosti, escapiranje je ovdje.
     *
     * @param dostavaMailom je li e-pošta kanal dostave (non-EU) ili samo obavijest
     */
    public String body(MailTemplate template, Map<String, String> values, boolean dostavaMailom) {
        Map<String, String> safe = new HashMap<>();
        values.forEach((k, v) -> safe.put(k, escape(v)));
        safe.put("klauzula.dostava", dostavaMailom ? KLAUZULA_EPOSTA : KLAUZULA_PRETINAC);
        safe.put("gumb.prijava", button("Prijava", properties.loginUrl()));
        safe.put("gumb.logIn", button("Log in", properties.loginUrl()));
        return wrap(loader.body(template, safe));
    }

    private static String wrap(String inner) {
        return """
                <!doctype html>
                <html><head><meta charset="utf-8"></head>
                <body style="margin:0;padding:24px;background-color:#F8F9FA;font-family:Arial,sans-serif;color:#212529;line-height:1.5;">
                  <div style="max-width:560px;margin:0 auto;background:#ffffff;border:1px solid #DEE2E6;padding:32px;">
                    %s
                    <p style="margin-top:32px;color:#6C757D;font-size:12px;">
                      Ovo je automatska poruka — molimo ne odgovarajte. ·
                      This is an automated message — please do not reply.
                    </p>
                  </div>
                </body></html>
                """.formatted(inner);
    }

    private static String button(String label, String url) {
        return """
                <p style="margin:24px 0;">
                  <a href="%s" style="display:inline-block;padding:12px 24px;background:#168ABF;color:#ffffff;text-decoration:none;font-weight:600;">%s</a>
                </p>
                """.formatted(escape(url), escape(label));
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
