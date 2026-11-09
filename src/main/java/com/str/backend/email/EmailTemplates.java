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
     * rokovi. Poruka <b>ne smije tvrditi da je dostava obavljena</b> — otprema u KP ide odvojeno i
     * može zakasniti — nego samo reći odakle rokovi teku.
     */
    private static final String KLAUZULA_PRETINAC = """
            <p style="color:#6C757D;font-size:13px;margin-top:20px;">
              Ovo je obavijest, a ne dostava akta. Akt se dostavlja u Vaš korisnički pretinac i
              rokovi teku od te dostave, sukladno članku 94. Zakona o općem upravnom postupku.
            </p>
            """;

    /**
     * Isto kao {@link #KLAUZULA_PRETINAC}, uz akt u privitku: MINTS je primijetio da poruka s aktom
     * tvrdi samo da se akt dostavlja u KP. Privitak je preslika, rokovi i dalje teku od pretinca.
     */
    private static final String KLAUZULA_PRETINAC_S_AKTOM = """
            <p style="color:#6C757D;font-size:13px;margin-top:20px;">
              Ovo je obavijest, a ne dostava akta. U privitku ove poruke nalazi se preslika akta, a akt
              se dostavlja u Vaš korisnički pretinac i rokovi teku od te dostave, sukladno članku 94.
              Zakona o općem upravnom postupku.
            </p>
            """;

    private static final String POVEZNICA_PRETINAC = """
            <p style="color:#6C757D;font-size:13px;">
              <a href="%s" style="color:#168ABF;">Otvorite svoj korisnički pretinac</a>
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

    /**
     * Vrijednost s rečenicom o prilozima akta; puni je {@code SmtpEmailService} za obavijesti o
     * promjeni statusa RB-a. Nije oznaka predloška — ugrađuje se u {@code klauzula.dostava}.
     */
    static final String PRILOZI = "prilozi";

    private final MailTemplateLoader loader;
    private final MailProperties properties;

    public EmailTemplates(MailTemplateLoader loader, MailProperties properties) {
        this.loader = loader;
        this.properties = properties;
    }

    /**
     * Naslov poruke. Ne escapira se — naslov je običan tekst, ne HTML — ali se prijelomi reda
     * uklanjaju: vrijednost s {@code \r\n} inače bi u zaglavlju poruke otvorila novi redak.
     */
    public String subject(MailTemplate template, Map<String, String> values) {
        Map<String, String> oneLine = new HashMap<>();
        values.forEach((k, v) -> oneLine.put(k, v == null ? null : v.replaceAll("[\\r\\n]+", " ")));
        return loader.subject(template, oneLine);
    }

    /** Gotov HTML poruke bez akta u privitku, uz dostavu u korisnički pretinac. */
    public String body(MailTemplate template, Map<String, String> values) {
        return body(template, values, Dostava.PRETINAC);
    }

    /**
     * Gotov HTML poruke bez akta u privitku.
     *
     * @param dostava kanal dostave akta, o kojem ovisi klauzula na dnu poruke
     */
    public String body(MailTemplate template, Map<String, String> values, Dostava dostava) {
        return body(template, values, dostava, false);
    }

    /**
     * Gotov HTML poruke; {@code values} su sirove vrijednosti, escapiranje je ovdje.
     *
     * @param dostava      kanal dostave akta, o kojem ovisi klauzula na dnu poruke
     * @param aktUPrivitku je li akt stvarno u privitku; poruka to smije reći samo tada
     */
    public String body(MailTemplate template, Map<String, String> values, Dostava dostava,
                       boolean aktUPrivitku) {
        Map<String, String> safe = new HashMap<>();
        values.forEach((k, v) -> safe.put(k, escape(v)));
        safe.put("klauzula.dostava", prilozi(safe.get(PRILOZI)) + switch (dostava) {
            case PRETINAC -> (aktUPrivitku ? KLAUZULA_PRETINAC_S_AKTOM : KLAUZULA_PRETINAC) + poveznicaNaPretinac();
            case EPOSTA -> KLAUZULA_EPOSTA;
            case BEZ_KLAUZULE -> "";
        });
        safe.put("gumb.prijava", button("Prijava", properties.loginUrl()));
        safe.put("gumb.logIn", button("Log in", properties.loginUrl()));
        return wrap(loader.body(template, safe));
    }

    /**
     * Rečenica o prilozima akta (T17) ide u isti blok na dnu kao klauzula o dostavi, ispred nje — oboje
     * govori gdje je što stranci dostavljeno. Bez priloga nema ni odlomka, pa predlošci ostaju isti.
     */
    private static String prilozi(String escapedTekst) {
        return escapedTekst == null || escapedTekst.isBlank() ? "" : "<p>" + escapedTekst + "</p>";
    }

    /** Bez konfigurirane adrese nema odlomka, a klauzula ostaje potpuna. */
    private String poveznicaNaPretinac() {
        return properties.kpUrl() == null ? "" : POVEZNICA_PRETINAC.formatted(escape(properties.kpUrl()));
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
