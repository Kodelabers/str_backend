package com.str.backend.email;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailTemplateTest {

    private EmailTemplates templates;

    @BeforeEach
    void setUp() {
        templates = templates(null);
    }

    private EmailTemplates templates(String kpUrl) {
        MailTemplateLoader loader = new MailTemplateLoader();
        loader.loadAll();
        return new EmailTemplates(loader,
                new MailProperties(true, "str@example.com", "https://str.example.com/login", null, kpUrl));
    }

    @ParameterizedTest
    @EnumSource(MailTemplate.class)
    void everyTemplate_rendersWithoutLeftoverPlaceholders(MailTemplate template) {
        String html = templates.body(template, values());

        assertThat(templates.subject(template, values())).isNotBlank().doesNotContain("${");
        assertThat(html).doesNotContain("${");
        assertThat(html).contains("<!doctype html>");
    }

    /**
     * Ključna provjera: predložak se renderira <b>samo</b> s oznakama koje servis stvarno
     * puni za tu poruku, ne s punom mapom. Renderiranje sa svime bi previdjelo oznaku dodanu
     * u predložak a nikad napunjenu — a na profilima s LoggingEmailService predlošci se ne
     * renderiraju uopće, pa se to ne bi vidjelo ni ručno.
     */
    @ParameterizedTest
    @EnumSource(MailTemplate.class)
    void everyTemplate_rendersWithOnlyItsDeclaredPlaceholders(MailTemplate template) {
        Map<String, String> only = new HashMap<>();
        template.placeholders().forEach(k -> only.put(k, "x"));

        String html = templates.body(template, only);

        assertThat(html).doesNotContain("${");
        assertThat(templates.subject(template, only)).doesNotContain("${");
    }

    /** Broj je u naslovu da se vidi već u popisu poruka. */
    @ParameterizedTest
    @EnumSource(value = MailTemplate.class, names = {"RB_IZDAN", "RB_IZDAN_DOSTAVA"})
    void rnIssuedSubject_containsRegistrationNumber(MailTemplate template) {
        assertThat(templates.subject(template, values())).contains("HR180000123456789001");
    }

    /** Prijelom reda u vrijednosti ne smije u zaglavlju poruke otvoriti novi redak. */
    @Test
    void subject_stripsLineBreaksFromValues() {
        Map<String, String> values = values();
        values.put("rn", "HR1\r\nBcc: netko@example.com");

        assertThat(templates.subject(MailTemplate.RB_IZDAN, values)).doesNotContain("\r", "\n");
    }

    /**
     * Obavijest o opozivu je od T15 izlazni akt i ide u pretinac, pa poruka nosi klauzulu kao i
     * ostale. Bez klauzule (ulazno pismeno) ne tvrdi ni jednu dostavu — grana ostaje kao zaštita.
     */
    @Test
    void revocation_carriesDeliveryClause_likeOtherNotices() {
        assertThat(templates.body(MailTemplate.OPOZIV, values(), Dostava.PRETINAC))
                .contains("opozvan").contains("korisnički pretinac");

        String bez = templates.body(MailTemplate.OPOZIV, values(), Dostava.BEZ_KLAUZULE);
        assertThat(bez).doesNotContain("korisnički pretinac");
        assertThat(bez).doesNotContain("elektroničkom poštom");
    }

    /** Non-EU poruka nosi akt, pa mora reći da je to dostava i da rokovi teku od nje. */
    @Test
    void rnIssuedNonEu_statesServiceByEmail() {
        String html = templates.body(MailTemplate.RB_IZDAN_DOSTAVA, values(), Dostava.EPOSTA);

        assertThat(html).contains("obavijest o dodjeli registracijskog broja");
        assertThat(html).contains("dostavlja se elektroničkom poštom");
        assertThat(html).contains("served by e-mail");
        assertThat(html).doesNotContain("PDF zahtjeva");
    }

    /**
     * Čl. 94. ZUP-a: dostava u korisnički pretinac je osobna dostava i od nje teku rokovi.
     * Bez ove klauzule stranka bi rok računala od maila — dakle od krivog dana.
     */
    @ParameterizedTest
    @EnumSource(value = MailTemplate.class,
            names = {"RB_IZDAN", "PRIJEDLOG_SUSPENZIJE", "SUSPENZIJA", "OBUSTAVA_SUSPENZIJE",
                    "REAKTIVACIJA", "POVLACENJE", "OPOZIV", "PRIJEDLOG_POVLACENJA", "OBUSTAVA_POVLACENJA",
                    "REAKTIVACIJA_POVLACENJA"})
    void lifecycleTemplates_stateThatEmailIsNotService(MailTemplate template) {
        String html = templates.body(template, values());

        assertThat(html).contains("a ne dostava akta");
        assertThat(html).contains("korisnički pretinac");
    }

    /** T12: povučen broj više nije trajno izgubljen — poruka upućuje na prigovor, ne na novi zahtjev. */
    @Test
    void withdrawalMail_pointsToObjection_notPermanence() {
        String html = templates.body(MailTemplate.POVLACENJE, values()).replaceAll("\\s+", " ");

        assertThat(html).contains("možete izjaviti prigovor").doesNotContain("trajno")
                .doesNotContain("ne može se reaktivirati");
        assertThat(templates.body(MailTemplate.PRIJEDLOG_POVLACENJA, values()).replaceAll("\\s+", " "))
                .contains("automatski će se povući");
    }

    /** MINTS: poruka s aktom ne smije tvrditi samo da je akt u pretincu, a bez akta ne smije spominjati privitak. */
    @Test
    void pretinac_clauseFollowsWhetherActIsAttached() {
        String sAktom = templates.body(MailTemplate.SUSPENZIJA, values(), Dostava.PRETINAC, true);
        assertThat(sAktom).contains("U privitku ove poruke nalazi se preslika akta")
                .contains("a ne dostava akta").contains("korisnički pretinac");

        assertThat(templates.body(MailTemplate.SUSPENZIJA, values(), Dostava.PRETINAC, false))
                .doesNotContain("privitku");
    }

    @ParameterizedTest
    @EnumSource(value = MailTemplate.class,
            names = {"RB_IZDAN", "PRIJEDLOG_SUSPENZIJE", "SUSPENZIJA", "OBUSTAVA_SUSPENZIJE",
                    "REAKTIVACIJA", "POVLACENJE", "OPOZIV", "PRIJEDLOG_POVLACENJA", "OBUSTAVA_POVLACENJA",
                    "REAKTIVACIJA_POVLACENJA"})
    void pretinac_carriesKpLinkWhenConfigured(MailTemplate template) {
        assertThat(templates("https://kp.example.hr/inbox").body(template, values(), Dostava.PRETINAC, true))
                .contains("<a href=\"https://kp.example.hr/inbox\"");
    }

    @Test
    void pretinac_withoutConfiguredUrl_hasNoLinkAndStaysComplete() {
        String html = templates.body(MailTemplate.RB_IZDAN, values(), Dostava.PRETINAC, true);

        assertThat(html).doesNotContain("Otvorite svoj korisnički pretinac").doesNotContain("href=\"\"");
        assertThat(html).contains("korisnički pretinac");
    }

    /** Ampersand u upitu mora biti escapiran, inače je atribut neispravan HTML. */
    @Test
    void kpLink_isEscapedInHtml() {
        assertThat(templates("https://kp.example.hr/i?a=1&b=2").body(MailTemplate.SUSPENZIJA, values()))
                .contains("href=\"https://kp.example.hr/i?a=1&amp;b=2\"");
    }

    @Test
    void kpUrl_blankMeansNoLink_invalidFailsFast() {
        assertThat(new MailProperties(true, "a@b.hr", "http://x", null, "  ").kpUrl()).isNull();
        assertThat(new MailProperties(true, "a@b.hr", "http://x", null, " https://kp.example.hr/ ").kpUrl())
                .isEqualTo("https://kp.example.hr/");

        for (String neispravna : new String[]{"javascript:alert(1)", "kp.example.hr/inbox", "https://",
                "https://kp.example.hr/\"><script>"}) {
            assertThatThrownBy(() -> new MailProperties(true, "a@b.hr", "http://x", null, neispravna))
                    .as(neispravna)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** Isti put kao {@code app.mail.kp-url=${APP_MAIL_KP_URL:}} u application.properties. */
    @Test
    void kpUrl_bindsFromProperty_emptyValueMeansNoLink() {
        assertThat(bind("https://kp.example.hr/").kpUrl()).isEqualTo("https://kp.example.hr/");
        assertThat(bind("").kpUrl()).isNull();
    }

    private static MailProperties bind(String kpUrl) {
        return new Binder(new MapConfigurationPropertySource(Map.of(
                "app.mail.enabled", "true", "app.mail.from", "a@b.hr",
                "app.mail.login-url", "http://x", "app.mail.kp-url", kpUrl)))
                .bindOrCreate("app.mail", MailProperties.class);
    }

    /** Non-EU nema pretinac: ni poveznice, ni tvrdnje o njemu, ni kad je adresa postavljena. */
    @ParameterizedTest
    @EnumSource(value = MailTemplate.class, names = {"RB_IZDAN_DOSTAVA", "SUSPENZIJA"})
    void epostaAndNoClause_neverLinkToKp(MailTemplate template) {
        EmailTemplates saAdresom = templates("https://kp.example.hr/inbox");

        assertThat(saAdresom.body(template, values(), Dostava.EPOSTA, true))
                .doesNotContain("kp.example.hr").doesNotContain("korisnički pretinac")
                .contains("dostavlja se elektroničkom poštom");
        assertThat(saAdresom.body(template, values(), Dostava.BEZ_KLAUZULE, true))
                .doesNotContain("kp.example.hr");
    }

    /** Ime iznajmljivača s HTML znakovima ne smije razbiti poruku. */
    @Test
    void body_escapesValues() {
        Map<String, String> values = values();
        values.put("ime", "<script>alert(1)</script>");

        String html = templates.body(MailTemplate.SUSPENZIJA, values);

        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    private static Map<String, String> values() {
        Map<String, String> values = new HashMap<>();
        values.put("ime", "Ana");
        values.put("korisnickoIme", "ana.anic");
        values.put("lozinka", "Lozinka123!");
        values.put("rn", "HR180000123456789001");
        values.put("objekt", "Apartman Sunce, Ilica 1");
        values.put("razlog", "nepotpuna dokumentacija");
        // Natpis nosi završnu točku (vidi labels.properties) — predložak je ne dodaje.
        values.put("rok", "najkasnije do 15.08.2026.");
        return values;
    }
}
