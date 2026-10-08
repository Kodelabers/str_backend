package com.str.backend.str;

import com.str.backend.lessor.LegalRepresentativeSource.Representative;
import com.str.backend.str.StrSubjectRepository.DocumentContactRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Native upiti za tvrtku (T8) na pravoj bazi (H2): jedan zastupnik preko
 * {@code document.subject_representative_id → subject_version} i kontakt s dokumenta.
 *
 * <p>{@code str.document} i {@code str.document_contact} nemaju entitete, pa ih test kreira sam,
 * sa stupcima koje upiti čitaju (struktura kao u eTurizmu, provjerena na CDU-u 08.10.2026.).
 */
@SpringBootTest
@ActiveProfiles("test")
class StrSubjectRepositoryQueryTest {

    private static final String LEGAL_OIB = "45645645646";
    private static final String NIAS_OIB = "70000000004";
    private static final String OTHER_REP = "11111111119";

    @Autowired private StrSubjectRepository repository;
    @Autowired private StrLegalRepresentativeSource representativeSource;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUpSchemaAndData() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS str.document (
                  id BIGINT PRIMARY KEY, active BOOLEAN, business_case_id BIGINT)
                """);
        jdbc.execute("ALTER TABLE str.document ADD COLUMN IF NOT EXISTS subject_version_id BIGINT");
        jdbc.execute("ALTER TABLE str.document ADD COLUMN IF NOT EXISTS subject_representative_id BIGINT");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS str.document_contact (
                  id BIGINT PRIMARY KEY, active BOOLEAN, document_id BIGINT, name VARCHAR(255),
                  phone VARCHAR(255), mobile VARCHAR(255), email VARCHAR(255))
                """);
        for (String table : List.of("document_contact", "document", "subject_version", "subject")) {
            jdbc.execute("DELETE FROM str." + table + " WHERE id BETWEEN 7000 AND 7999");
        }

        // Tvrtka: aktivna verzija 7010 i povijesna 7009; zastupnici su verzije fizičkih osoba.
        jdbc.execute("INSERT INTO str.subject (id, active, jips) VALUES (7001, true, '" + LEGAL_OIB + "')");
        jdbc.execute("""
                INSERT INTO str.subject_version (id, active, subject_id, name, pin, historical) VALUES
                  (7009, true, 7001, 'STARI NAZIV', '45645645646', true),
                  (7010, true, 7001, 'PERINA TESTNA FIRMA D.O.O', '45645645646', false),
                  (7020, true, 7002, NULL, '70000000004', false),
                  (7021, true, 7003, NULL, '11111111119', false),
                  (7022, true, 7004, NULL, '22222222228', false),
                  (7023, true, 7005, NULL, 'X1234567', false),
                  (7024, false, 7006, NULL, '33333333336', false)
                """);
        jdbc.execute("UPDATE str.subject_version SET first_name = 'Ana', last_name = 'Horvat' WHERE id = 7020");
        jdbc.execute("UPDATE str.subject_version SET first_name = 'Iva', last_name = 'Ivić' WHERE id = 7021");
    }

    private void document(long id, boolean active, long subjectVersionId, Long representativeVersionId) {
        jdbc.update("INSERT INTO str.document (id, active, subject_version_id, subject_representative_id) "
                + "VALUES (?, ?, ?, ?)", id, active, subjectVersionId, representativeVersionId);
    }

    @Test
    void representative_fromNewestDocument_whenNiasPersonIsNotListed() {
        document(7100, true, 7010, 7020L);
        document(7101, true, 7010, 7021L);

        assertThat(representativeSource.findRepresentative(LEGAL_OIB, "99999999990"))
                .contains(new Representative(OTHER_REP, "Iva", "Ivić"));
    }

    /** Tvrtka s više zastupnika (na CDU-u 1 od 61): NIAS osoba ima prednost i kad nije na najnovijem dokumentu. */
    @Test
    void representative_niasPersonPreferred_whenListed() {
        document(7100, true, 7010, 7020L);
        document(7101, true, 7010, 7021L);

        assertThat(representativeSource.findRepresentative(LEGAL_OIB, NIAS_OIB))
                .map(Representative::oib).contains(NIAS_OIB);
    }

    @Test
    void representative_ignoresInactiveDocuments_andHistoricalCompanyVersions() {
        document(7100, true, 7010, 7020L);
        document(7101, false, 7010, 7021L);
        document(7102, true, 7009, 7022L);

        assertThat(representativeSource.findRepresentative(LEGAL_OIB, null))
                .map(Representative::oib).contains(NIAS_OIB);
    }

    /** pin strane osobe nije OIB: ne smije ni u lessor (CHECK) ni u OIB sustav — zastupnik je tada NIAS osoba. */
    @Test
    void representative_withNonOibPin_isTreatedAsNotListed() {
        document(7100, true, 7010, 7023L);

        assertThat(representativeSource.findRepresentative(LEGAL_OIB, NIAS_OIB)).isEmpty();
    }

    @Test
    void representative_ignoresInactiveRepresentativeVersion() {
        document(7100, true, 7010, 7020L);
        document(7101, true, 7010, 7024L);

        assertThat(representativeSource.findRepresentative(LEGAL_OIB, null))
                .map(Representative::oib).contains(NIAS_OIB);
    }

    @Test
    void representative_absent_whenNoDocumentHasOne() {
        document(7100, true, 7010, null);

        assertThat(representativeSource.findRepresentative(LEGAL_OIB, NIAS_OIB)).isEmpty();
        assertThat(representativeSource.findRepresentative("00000000001", NIAS_OIB)).isEmpty();
    }

    /** Kontakt (pa i e-mail, podatak 6) je s najnovijeg dokumenta tvrtke koji ga ima. */
    @Test
    void contact_includesEmail_fromNewestDocumentWithContact() {
        document(7100, true, 7010, 7020L);
        document(7101, true, 7010, 7020L);
        document(7102, true, 7010, 7020L);
        jdbc.execute("""
                INSERT INTO str.document_contact (id, active, document_id, name, phone, mobile, email) VALUES
                  (7200, true, 7100, 'Stari', '011', '091', 'stari@firma.hr'),
                  (7201, true, 7101, 'Recepcija', '012345678', '0911234567', 'info@firma.hr')
                """);

        DocumentContactRow contact = repository.findDocumentContactByOib(LEGAL_OIB).orElseThrow();

        assertThat(contact.getEmail()).isEqualTo("info@firma.hr");
        assertThat(contact.getPhone()).isEqualTo("012345678");
        assertThat(contact.getMobile()).isEqualTo("0911234567");
        assertThat(contact.getName()).isEqualTo("Recepcija");
    }
}
