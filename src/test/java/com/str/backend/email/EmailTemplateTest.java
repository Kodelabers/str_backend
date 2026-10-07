package com.str.backend.email;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EmailTemplateTest {

    private EmailTemplates templates;

    @BeforeEach
    void setUp() {
        MailTemplateLoader loader = new MailTemplateLoader();
        loader.loadAll();
        templates = new EmailTemplates(loader,
                new MailProperties(true, "str@example.com", "https://str.example.com/login", null));
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

    /** Non-EU poruka nosi akt, pa mora reći da je to dostava i da rokovi teku od nje. */
    @Test
    void rnIssuedNonEu_statesServiceByEmail() {
        String html = templates.body(MailTemplate.RB_IZDAN_DOSTAVA, values(), true);

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
                    "REAKTIVACIJA", "POVLACENJE", "OPOZIV"})
    void lifecycleTemplates_stateThatEmailIsNotService(MailTemplate template) {
        String html = templates.body(template, values());

        assertThat(html).contains("a ne dostava akta");
        assertThat(html).contains("korisnički pretinac");
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
