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
            new DocumentProperties(null, null, null, Map.of(), false);

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

    /** Čl. 98. st. 2 traži OIB stranke u uvodu — nosi ga ${stranka.identifikator}. */
    @ParameterizedTest
    @MethodSource("templateBackedTypes")
    void everyTemplate_identifiesThePartyInTheIntroduction(StrDocumentType type) {
        String uvod = loader.get(type).section(ZupSection.UVOD).orElseThrow();

        assertThat(ZupPlaceholders.keysIn(uvod)).contains("stranka.identifikator");
    }

    /** Akti koji diraju u prava stranke moraju imati uputu; obavijesti o činjenici ne moraju. */
    @Test
    void onlyAdverseActs_requireLegalRemedySection() {
        assertThat(StrDocumentType.SUSPENZIJA.requiredSections())
                .contains(ZupSection.UPUTA_O_PRAVNOM_LIJEKU);
        assertThat(StrDocumentType.POVLACENJE.requiredSections())
                .contains(ZupSection.UPUTA_O_PRAVNOM_LIJEKU);
        assertThat(StrDocumentType.DODJELA.requiredSections())
                .doesNotContain(ZupSection.UPUTA_O_PRAVNOM_LIJEKU);
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
     * Sedam vrsta pismena iz InfoDomovog maila (22.07.2026.) mora se poklapati sa šifrarnikom
     * znak za znak — po nazivu se razrješava šifra.
     */
    @Test
    void documentTypes_matchEgopCodebookNames() {
        assertThat(StrDocumentType.values())
                .extracting(StrDocumentType::vrstaPismenaNaziv)
                .contains(
                        "Zahtjev za registracijski broj",
                        "Obavijest o dodjeli registracijskog broja",
                        "Obavijest o opozivu registracijskog broja",
                        "Obavijest o prijedlogu suspenzije registracijskog broja",
                        "Obavijest o suspenziji registracijskog broja",
                        "Obavijest o povlačenju registracijskog broja",
                        "Prigovor na prijedlog suspenzije");
    }

    /**
     * Reaktivacija je osmi tip i namjerno <b>nije</b> među 7 iz maila — nema šifru u šifrarniku,
     * pa joj je urudžbiranje iza zastavice. Ako je InfoDom naknadno potvrdi, ovaj test se briše,
     * a naziv seli u popis gore.
     */
    @Test
    void reactivation_isNotPartOfTheAgreedCodebookSet() {
        assertThat(StrDocumentType.REAKTIVACIJA.vrstaPismenaNaziv())
                .isEqualTo("Obavijest o reaktivaciji registracijskog broja");
        assertThat(StrDocumentType.values()).hasSize(8);
    }
}
