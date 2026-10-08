package com.str.backend.registries.oib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.str.backend.exception.ExternalRegistryException;
import com.str.backend.lessor.RegistryLegalEntity;
import com.str.backend.lessor.RegistrySubject;
import com.str.backend.lessor.SubjectDataSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mapiranje odgovora OIB sustava: {@code fizickaOsoba.dohvatiF*} u {@link RegistrySubject} i
 * {@code pravnaOsoba.dohvatiP*} u {@link RegistryLegalEntity}.
 */
class OibRegistryResponseMapperTest {

    private static final String OIB = "12312312316";
    private static final String LEGAL_OIB = "45645645646";
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void mapsNameAndAddress_fromFilledVariant() throws IOException {
        RegistrySubject s = OibRegistryResponseMapper.toSubject(OIB, fixture("fizicka-osoba-fg.json")).orElseThrow();

        assertThat(s.oib()).isEqualTo(OIB);
        assertThat(s.firstName()).isEqualTo("PERO");
        assertThat(s.lastName()).isEqualTo("PERIĆ");
        assertThat(s.street()).isEqualTo("ULICA KRALJA TOMISLAVA");
        assertThat(s.streetNumber()).isEqualTo("14A");
        assertThat(s.place()).isEqualTo("SPLIT");
        assertThat(s.postalCode()).isEqualTo("21000");
    }

    /**
     * Shema nema županiju — ne smije se izmisliti; nosi se općina, iz koje je
     * SubjectProfileService izvede (GO-1 o njoj ovisi).
     */
    @Test
    void carriesMunicipality_notCounty() throws IOException {
        RegistrySubject s = OibRegistryResponseMapper.toSubject(OIB, fixture("fizicka-osoba-fg.json")).orElseThrow();

        assertThat(s.municipality()).isEqualTo("SPLIT");
        assertThat(s.county()).isNull();
        assertThat(s.legalEntityName()).isNull();
        assertThat(s.source()).isEqualTo(SubjectDataSource.OIB_REGISTAR);
    }

    /** FE nosi ime bez adrese, FH adresu bez imena — spajaju se, ne uzima se samo prva varijanta. */
    @Test
    void combinesNameAndAddress_fromDifferentVariants() throws IOException {
        JsonNode body = json.readTree("""
                {"fizickaOsoba": {
                   "dohvatiFE": {"oib": "12312312316", "ime": "Ana", "prezime": "Anić"},
                   "dohvatiFH": {"oib": "12312312316",
                                 "adresaPrebivalista": {"ulica": "Ilica", "kucniBroj": "5",
                                                        "naselje": "Zagreb", "brojPoste": "10000"}}}}
                """);

        RegistrySubject s = OibRegistryResponseMapper.toSubject(OIB, body).orElseThrow();

        assertThat(s.firstName()).isEqualTo("Ana");
        assertThat(s.street()).isEqualTo("Ilica");
        assertThat(s.streetNumber()).isEqualTo("5");
    }

    /** Prazni stringovi iz swagger-šablone su „nema podatka", ne vrijednost. */
    @Test
    void blankValues_areMissing() throws IOException {
        JsonNode body = json.readTree("""
                {"fizickaOsoba": {"dohvatiFG": {"ime": "Ana", "prezime": " ",
                   "adresaPrebivalista": {"ulica": "", "kucniBroj": "5", "kucniBrojDodatak": ""}}}}
                """);

        RegistrySubject s = OibRegistryResponseMapper.toSubject(OIB, body).orElseThrow();

        assertThat(s.lastName()).isNull();
        assertThat(s.street()).isNull();
        assertThat(s.streetNumber()).isEqualTo("5");
    }

    @Test
    void noPersonAndNoErrors_meansUnknown() throws IOException {
        JsonNode body = json.readTree("""
                {"fizickaOsoba": {}, "greske": {"imaGresaka": false, "greska": []}}
                """);

        assertThat(OibRegistryResponseMapper.toSubject(OIB, body)).isEmpty();
    }

    /**
     * Šifre grešaka još nisu poznate, pa greška bez podataka ide kao kvar (503) — sa šiframa u
     * poruci, da ih prvi stvarni poziv otkrije u logu.
     */
    @Test
    void errorsWithoutPerson_areRegistryFailure_withCodesInMessage() throws IOException {
        JsonNode body = json.readTree("""
                {"fizickaOsoba": null,
                 "greske": {"imaGresaka": true, "greska": [{"sifra": "E101", "poruka": "..."}]}}
                """);

        assertThatThrownBy(() -> OibRegistryResponseMapper.toSubject(OIB, body))
                .isInstanceOf(ExternalRegistryException.class)
                .hasMessageContaining("E101");
    }

    /** Stvarni odgovor test okoline za {@code PO/45645645646} (08.10.2026.): puni se PB, MBS-a nema. */
    @Test
    void legalEntity_mapsNameAndSeat_fromFilledVariant() throws IOException {
        RegistryLegalEntity e = OibRegistryResponseMapper.toLegalEntity(LEGAL_OIB, fixture("pravna-osoba-pb.json"))
                .orElseThrow();

        assertThat(e.oib()).isEqualTo(LEGAL_OIB);
        assertThat(e.name()).isEqualTo("PERINA TESTNA FIRMA D.O.O");
        assertThat(e.registrationNumber()).isNull();
        assertThat(e.street()).isEqualTo("ULICA CVIJETE ZUZORIĆ");
        assertThat(e.streetNumber()).isEqualTo("3");
        assertThat(e.place()).isEqualTo("ZAGREB");
        assertThat(e.postalCode()).isEqualTo("10000");
        assertThat(e.municipality()).isEqualTo("GRAD ZAGREB");
        assertThat(e.county()).isNull();
        assertThat(e.source()).isEqualTo(SubjectDataSource.OIB_REGISTAR);
    }

    /** Uredba 2024/1028, čl. 5(1)(c)(ii): nacionalni registracijski broj je MBS. MB (DZS) se ne čita. */
    @Test
    void legalEntity_mapsMbs_notMb() throws IOException {
        JsonNode body = json.readTree("""
                {"pravnaOsoba": {"dohvatiPA": {"nazivTvrtke": "ADRIA d.o.o.", "mb": "01234567",
                   "mbs": "080123456",
                   "adresaSjedista": {"ulica": "Ilica", "kucniBroj": "1", "kucniBrojDodatak": "b",
                                      "naselje": "Zagreb", "brojPoste": "10000", "opcina": "GRAD ZAGREB"}}}}
                """);

        RegistryLegalEntity e = OibRegistryResponseMapper.toLegalEntity(LEGAL_OIB, body).orElseThrow();

        assertThat(e.registrationNumber()).isEqualTo("080123456");
        assertThat(e.streetNumber()).isEqualTo("1b");
    }

    @Test
    void legalEntity_numericMbs_isNotDropped() throws IOException {
        JsonNode body = json.readTree("""
                {"pravnaOsoba": {"dohvatiPB": {"nazivTvrtke": "ADRIA d.o.o.", "mbs": 80123456}}}
                """);

        assertThat(OibRegistryResponseMapper.toLegalEntity(LEGAL_OIB, body).orElseThrow().registrationNumber())
                .isEqualTo("80123456");
    }

    @Test
    void legalEntity_noCompanyAndNoErrors_meansUnknown() throws IOException {
        JsonNode body = json.readTree("""
                {"pravnaOsoba": {"dohvatiPA": null, "dohvatiPB": null},
                 "greske": {"imaGresaka": false, "greska": []}}
                """);

        assertThat(OibRegistryResponseMapper.toLegalEntity(LEGAL_OIB, body)).isEmpty();
    }

    @Test
    void legalEntity_errorsWithoutCompany_areRegistryFailure() throws IOException {
        JsonNode body = json.readTree("""
                {"pravnaOsoba": null,
                 "greske": {"imaGresaka": true, "greska": [{"sifra": "E202", "poruka": "..."}]}}
                """);

        assertThatThrownBy(() -> OibRegistryResponseMapper.toLegalEntity(LEGAL_OIB, body))
                .isInstanceOf(ExternalRegistryException.class)
                .hasMessageContaining("E202");
    }

    private JsonNode fixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/oib-registry/" + name)) {
            return json.readTree(in);
        }
    }
}
