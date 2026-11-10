package com.str.backend.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZupTemplateLoaderTest {

    private static final DocumentProperties PROPERTIES =
            new DocumentProperties(null, null, null, Map.of(), false, false, Map.of());

    private final ZupTemplateLoader loader = loaded();

    private static ZupTemplateLoader loaded() {
        ZupTemplateLoader l = new ZupTemplateLoader(PROPERTIES);
        l.loadAll();
        return l;
    }

    static Set<StrDocumentType> templateBackedTypes() {
        return StrDocumentType.templateBackedTypes();
    }

    /**
     * Ovo je zaštita koja opravdava provjeru na startu: rješenje bez upute o pravnom lijeku
     * po čl. 111. ZUP-a ide na štetu tijela, a bez ove provjere otkrilo bi se tek kad akt
     * već ode stranci.
     */
    @ParameterizedTest
    @MethodSource("templateBackedTypes")
    void everyTemplate_hasAllRequiredSections(StrDocumentType type) {
        ZupTemplate template = loader.get(type);

        for (ZupSection section : type.requiredSections()) {
            assertThat(template.has(section))
                    .as("%s mora imati sekciju %s", type, section)
                    .isTrue();
        }
    }

    /** Čl. 98. st. 3: „Kad odluka sadržava rok ... to treba biti sadržano u izreci." */
    @Test
    void prijedlogSuspenzije_carriesDeadlineInTheOperativePart() {
        String izreka = loader.get(StrDocumentType.PRIJEDLOG_SUSPENZIJE)
                .section(ZupSection.IZREKA).orElseThrow();

        assertThat(ZupPlaceholders.keysIn(izreka)).contains("rok.ispravak");
    }

    /**
     * Čl. 98. st. 2 traži OIB stranke — nosi ga ${stranka.identifikator}. Akti službenika imenuju
     * stranku u točki 1. („dodijeljen na zahtjev stranke: …", MINTS, studeni 2026.), ostali u uvodu.
     */
    @ParameterizedTest
    @MethodSource("templateBackedTypes")
    void everyTemplate_identifiesTheParty(StrDocumentType type) {
        ZupTemplate template = loader.get(type);
        String uvod = template.section(ZupSection.UVOD).orElseThrow();
        String izreka = template.section(ZupSection.IZREKA).orElse("");

        assertThat(ZupPlaceholders.keysIn(uvod + "\n" + izreka)).contains("stranka.identifikator");
    }

    /**
     * MINTS (studeni 2026.): suspenzija i povlačenje nose prigovor kao točku izreke, pa nijedna
     * obavijest više ne traži sekciju upute o pravnom lijeku — ali pravni lijek ne smije nestati.
     */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = StrDocumentType.class, names = {"SUSPENZIJA", "POVLACENJE"})
    void adverseActs_carryObjectionInOperativePart(StrDocumentType type) {
        assertThat(type.requiredSections()).doesNotContain(ZupSection.UPUTA_O_PRAVNOM_LIJEKU);
        assertThat(loader.get(type).section(ZupSection.IZREKA).orElseThrow())
                .contains("može izjaviti prigovor nadležnom upravnom tijelu u roku od osam dana");
    }

    /**
     * Predložak MINT-a od 11.09.2026. izbacio je adresata, naslov izreke, priloge i dostavnu
     * listu — i to vrijedi za <b>svaku</b> obavijest tijela, ne samo za dodjelu. Podnesak stranke
     * (prigovor) nije obavijest i zadržava adresata po čl. 71. st. 2.
     */
    @ParameterizedTest
    @MethodSource("obavijesti")
    void everyNotice_hasNoAddresseeOrDistributionList(StrDocumentType type) {
        assertThat(type.requiredSections())
                .doesNotContain(ZupSection.NASLOV, ZupSection.PRILOZI, ZupSection.DOSTAVNA_LISTA);
        ZupTemplate template = loader.get(type);
        assertThat(template.has(ZupSection.NASLOV)).isFalse();
        assertThat(template.has(ZupSection.PRILOZI)).isFalse();
        assertThat(template.has(ZupSection.DOSTAVNA_LISTA)).isFalse();
    }

    /** Izreka se nigdje ne naslovljuje; naslovi ostaju samo obrazloženju i uputi. */
    @Test
    void dispositionHasNoHeading() {
        assertThat(ZupSection.IZREKA.hasHeading()).isFalse();
        assertThat(ZupSection.OBRAZLOZENJE.hasHeading()).isTrue();
        assertThat(ZupSection.UPUTA_O_PRAVNOM_LIJEKU.hasHeading()).isTrue();
    }

    @Test
    void partySubmission_keepsAddressee() {
        assertThat(StrDocumentType.PRIGOVOR.requiredSections()).contains(ZupSection.NASLOV);
        assertThat(loader.get(StrDocumentType.PRIGOVOR).has(ZupSection.NASLOV)).isTrue();
    }

    static java.util.stream.Stream<StrDocumentType> obavijesti() {
        return StrDocumentType.templateBackedTypes().stream()
                .filter(StrDocumentType::aktTijela);
    }

    /**
     * Renderer prolazi kroz {@code ZupSection.values()}, pa poredak konstanti mora biti poredak
     * na papiru. Ako netko premjesti konstantu, ovaj test padne prije nego akt izađe s izrekom
     * iza obrazloženja.
     */
    @Test
    void sectionOrder_matchesZupArticle98() {
        assertThat(ZupSection.values()).containsExactly(
                ZupSection.ZAGLAVLJE,
                ZupSection.NASLOV,
                ZupSection.UVOD,
                ZupSection.IZREKA,
                ZupSection.OBRAZLOZENJE,
                ZupSection.UPUTA_O_PRAVNOM_LIJEKU,
                ZupSection.NAPOMENA,
                ZupSection.PRILOZI,
                ZupSection.POTPISNIK,
                ZupSection.DOSTAVNA_LISTA);
    }

    @Test
    void get_forZahtjev_throws_becauseItHasItsOwnGenerator() {
        assertThatThrownBy(() -> loader.get(StrDocumentType.ZAHTJEV))
                .isInstanceOf(DocumentTemplateException.class)
                .hasMessageContaining("vlastiti generator");
    }

    @Test
    void slugLookup_acceptsLegacyNames() {
        assertThat(StrDocumentType.fromSlug("nalog-suspenzija"))
                .contains(StrDocumentType.SUSPENZIJA);
        assertThat(StrDocumentType.fromSlug("dopis-namjere"))
                .contains(StrDocumentType.PRIJEDLOG_SUSPENZIJE);
        assertThat(StrDocumentType.fromSlug("nepostojeci")).isEmpty();
    }

    /**
     * Nazivi vrsta pismena u eGOP šifrarniku (MINT, 22.10.2026.) — po njima se razrješava šifra,
     * pa se moraju poklapati sa šifrarnikom (do normalizacije razmaka, velikih slova i dijakritika).
     */
    @Test
    void documentTypes_matchEgopCodebookNames() {
        assertThat(StrDocumentType.values())
                .extracting(StrDocumentType::egopVrstaNaziv)
                .contains(
                        "Zahtjev za dodjelu registracijskog broja",
                        "Obavijest o dodjeli registracijskog broja",
                        "Obavijest o opozivu registracijskog broja",
                        "Zahtjev za opoziv registracijskog broja",
                        "Obavijest o prijedlogu suspenzije registracijskog broja",
                        "Obavijest o obustavi postupka suspenzije registracijskog broja",
                        "Obavijest o suspenziji registracijskog broja",
                        "Obavijest o reaktiviranju registracijskog broja",
                        "Obavijest o povlačenju registracijskog broja",
                        "Prigovor na prijedlog suspenzije");
    }

    /**
     * Tipovi bez vrste u eGOP šifrarniku: zahtjev za promjenu podataka vodi se u eTurizmu i
     * nikad se ne urudžbira ({@code EgopAktiBezSifre.UVIJEK_BEZ_SIFRE}). Tri akta postupka povlačenja
     * (T12) MINT još nije unio — do tada idu pod zamjenskom šifrom ili na popis
     * {@code EGOP_AKTI_BEZ_SIFRE} (DEPLOY-*.md); kad ih unese, premjestiti ih u šifrarnik ovdje i gore.
     *
     * <p>Popis, a ne broj tipova: novi tip mora natjerati autora da svjesno odluči ima li vrstu
     * u eGOP šifrarniku, a ne samo da podigne brojku.
     */
    @Test
    void typesOutsideEgopCodebook_areKnownAndUnfiled() {
        Set<String> uSifrarniku = Set.of(
                "Zahtjev za dodjelu registracijskog broja",
                "Obavijest o dodjeli registracijskog broja",
                "Obavijest o opozivu registracijskog broja",
                // T15: novu vrstu MINT mora unijeti na svakoj okolini (docs/eGOP-endpoint-analiza.md).
                "Zahtjev za opoziv registracijskog broja",
                "Obavijest o prijedlogu suspenzije registracijskog broja",
                "Obavijest o obustavi postupka suspenzije registracijskog broja",
                "Obavijest o suspenziji registracijskog broja",
                "Obavijest o reaktiviranju registracijskog broja",
                "Obavijest o povlačenju registracijskog broja",
                "Prigovor na prijedlog suspenzije");

        assertThat(StrDocumentType.values())
                .filteredOn(t -> !uSifrarniku.contains(t.egopVrstaNaziv()))
                .containsExactly(StrDocumentType.PRIJEDLOG_POVLACENJA, StrDocumentType.OBUSTAVA_POVLACENJA,
                        StrDocumentType.REAKTIVACIJA_POVLACENJA, StrDocumentType.ZAHTJEV_PROMJENE_PODATAKA);
    }
}
