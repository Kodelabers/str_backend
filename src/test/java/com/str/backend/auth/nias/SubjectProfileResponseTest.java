package com.str.backend.auth.nias;

import com.str.backend.lessor.LegalEntityProfile;
import com.str.backend.lessor.SubjectDataSource;
import com.str.backend.lessor.SubjectProfile;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/nias/subject} u ime tvrtke nosi svih šest podataka iz Uredbe (EU) 2024/1028,
 * čl. 5(1)(c): naziv, MBS, ime zastupnika, adresu (sjedište i zastupnik), telefon i e-mail.
 */
class SubjectProfileResponseTest {

    private static final ActingSubject ACTING = new ActingSubject("45645645646", "PERINA TESTNA FIRMA D.O.O",
            List.of("Direktor"), "70000000004", "Ana", "Horvat", Instant.parse("2026-10-08T10:00:00Z"));

    private static SubjectContact contact() {
        return new SubjectContact("0911234567", "012345678", "Recepcija", "info@firma.hr",
                SubjectContact.Source.DOKUMENT);
    }

    private static LegalEntityProfile profile() {
        SubjectProfile representative = new SubjectProfile("11111111119", "PERO", "PERIĆ",
                SubjectDataSource.OIB_REGISTAR, null, "SJENJAK", "19", "OSIJEK", "31000", "OSIJEK",
                "Osječko-baranjska", SubjectDataSource.OIB_REGISTAR);
        return new LegalEntityProfile("45645645646", "PERINA TESTNA FIRMA D.O.O", "080123456",
                "ULICA CVIJETE ZUZORIĆ", "3", "ZAGREB", "10000", "GRAD ZAGREB", "Grad Zagreb",
                SubjectDataSource.OIB_REGISTAR, representative);
    }

    @Test
    void legalEntity_carriesAllSixRegulationItems() {
        SubjectProfileResponse r = SubjectProfileResponse.ofLegalEntity(ACTING, profile(), contact());

        ActingSubjectResponse company = r.pravnaOsoba();
        assertThat(company.naziv()).isEqualTo("PERINA TESTNA FIRMA D.O.O");          // 1
        assertThat(company.mbs()).isEqualTo("080123456");                             // 2
        assertThat(r.ime()).isEqualTo("PERO");                                        // 3
        assertThat(r.prezime()).isEqualTo("PERIĆ");
        assertThat(company.ulica()).isEqualTo("ULICA CVIJETE ZUZORIĆ");               // 4 — sjedište
        assertThat(company.zupanija()).isEqualTo("Grad Zagreb");
        assertThat(company.adresaIzvor()).isEqualTo(SubjectDataSource.OIB_REGISTAR);
        assertThat(r.ulica()).isEqualTo("SJENJAK");                                   // 4 — zastupnik
        assertThat(r.adresaIzvor()).isEqualTo(SubjectDataSource.OIB_REGISTAR);
        assertThat(r.kontaktTelefon()).isEqualTo("012345678");                        // 5
        assertThat(r.kontaktMobitel()).isEqualTo("0911234567");
        assertThat(r.kontaktEmail()).isEqualTo("info@firma.hr");                      // 6
        assertThat(r.kontaktIzvor()).isEqualTo(SubjectContact.Source.DOKUMENT);
    }

    /** Zastupnik s dokumenta nije nužno NIAS osoba; funkcije i provjera ostaju njezine. */
    @Test
    void legalEntity_representativeIsOne_niasPersonStaysInVerification() {
        SubjectProfileResponse r = SubjectProfileResponse.ofLegalEntity(ACTING, profile(), SubjectContact.NONE);

        assertThat(r.oib()).isEqualTo("11111111119");
        assertThat(r.pravnaOsoba().zastupnikOib()).isEqualTo("70000000004");
        assertThat(r.pravnaOsoba().izvor()).isEqualTo("E_OVLASTENJA");
        assertThat(r.kontaktEmail()).isNull();
        assertThat(r.kontaktIzvor()).isNull();
    }

    /** Odabir tvrtke ({@code /acting-subject}) ne dohvaća sjedište. */
    @Test
    void actingSubjectSelection_hasNoSeat() {
        ActingSubjectResponse r = ActingSubjectResponse.of(ACTING);

        assertThat(r.mbs()).isNull();
        assertThat(r.ulica()).isNull();
        assertThat(r.adresaIzvor()).isNull();
    }
}
