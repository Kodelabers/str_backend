package com.str.backend.lessor;

import com.str.backend.address.CountryRepository;
import com.str.backend.address.CountyByMunicipalityResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * {@code chk_lessor_legal_entity_owner} nad stvarnom pohranom iznajmljivača.
 *
 * <p>Test profil gradi shemu iz entiteta (Liquibase je ugašen), pa CHECK ograničenja iz
 * changeloga u testovima inače ne postoje — upravo zato je pravna osoba iz e-Zastupanja
 * prolazila sve testove, a na PostgreSQL-u pucala s 500. Ovdje se ograničenje primjenjuje
 * <b>iz samog changeseta</b>, da test ne može otići u drugom smjeru od baze.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class LessorLegalEntityCheckConstraintTest {

    /** Zadnji changeset koji definira ograničenje — mijenja li ga netko, ovdje ide novi. */
    private static final String CHANGESET = "db/changelog/changes/130-lessor-legal-entity-check-eovlastenja.xml";
    private static final String CONSTRAINT = "chk_lessor_legal_entity_owner";

    @Autowired
    private LessorRepository lessorRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void applyConstraintFromChangeset() throws Exception {
        jdbc.execute("ALTER TABLE str_rn.lessor DROP CONSTRAINT IF EXISTS " + CONSTRAINT);
        jdbc.execute(addConstraintSql());
    }

    @Test
    void legalEntityFromEZastupanja_withoutForeignSeatData_isStored() {
        // Točno ono što RegistrationService gradi kad NIAS osoba djeluje u ime tvrtke.
        LessorEntity lessor = legalLessor("TESTNA TVRTKA d.o.o.", null);

        lessorRepository.saveAndFlush(lessor);

        assertThat(lessorRepository.findById(lessor.getLessorId()))
                .get()
                .satisfies(saved -> {
                    assertThat(saved.isLegalEntityOwner()).isTrue();
                    assertThat(saved.getLessorOib()).isEqualTo("12345678903");
                    assertThat(saved.getLegalEntityName()).isEqualTo("TESTNA TVRTKA d.o.o.");
                });
    }

    @Test
    void foreignLegalEntity_withoutSeatData_isStillRejected() {
        LessorEntity lessor = nonEu();
        lessor.applyLegalEntityOwner("Foreign Ltd", null, null, null);

        assertThatThrownBy(() -> lessorRepository.saveAndFlush(lessor))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void foreignLegalEntity_withSeatData_isStored() {
        LessorEntity lessor = nonEu();
        lessor.applyLegalEntityOwner("Foreign Ltd", 276, "Berlin", "HRB 12345");

        lessorRepository.saveAndFlush(lessor);

        assertThat(lessorRepository.findById(lessor.getLessorId())).isPresent();
    }

    @Test
    void legalEntityWithOib_withoutName_isRejected() {
        LessorEntity lessor = legalLessor(null, null);

        assertThatThrownBy(() -> lessorRepository.saveAndFlush(lessor))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Preduge vrijednosti iz registara režu se na duljinu stupca, pa pohrana (i izdavanje RB-a) ne pada. */
    @Test
    void legalEntity_withOverlongRegistryValues_isStored() {
        SubjectProfile representative = new SubjectProfile("98765432106", "OTAC", "PET",
                SubjectDataSource.OIB_REGISTAR, null, "V".repeat(300), "2", "Split", "21000", "Split",
                "Splitsko-dalmatinska", SubjectDataSource.OIB_REGISTAR);
        LegalEntityProfile profile = new LegalEntityProfile("12345678903", "TESTNA TVRTKA d.o.o.", "M".repeat(60),
                "I".repeat(600), "12345678901234567890", "Z".repeat(200), "10000", "GRAD ZAGREB", "Grad Zagreb",
                SubjectDataSource.OIB_REGISTAR, representative);
        LessorEntity lessor = new SubjectProfileService(mock(SubjectRegistry.class), mock(LegalEntityRegistry.class),
                mock(LegalRepresentativeSource.class), mock(CountyByMunicipalityResolver.class),
                mock(CountryRepository.class)).toLegalLessor(profile);

        lessorRepository.saveAndFlush(lessor);

        assertThat(lessorRepository.findById(lessor.getLessorId())).isPresent();
    }

    /** T8: tvrtka iz e-Zastupanja sa sjedištem i MBS-om iz OIB sustava (bez države i grada). */
    @Test
    void legalEntityFromEZastupanja_withSeatAndMbs_isStored() {
        LessorEntity lessor = legalLessor("TESTNA TVRTKA d.o.o.", "080123456");

        lessorRepository.saveAndFlush(lessor);

        assertThat(lessorRepository.findById(lessor.getLessorId()))
                .get()
                .satisfies(saved -> {
                    assertThat(saved.getLegalEntityRegistrationNumber()).isEqualTo("080123456");
                    assertThat(saved.getStreet()).isEqualTo("Ilica");
                    assertThat(saved.getRepresentativeAddress()).isEqualTo("Vukovarska 2, 21000 Split");
                });
    }

    @Test
    void naturalPerson_isUnaffected() {
        LessorEntity lessor = LessorEntity.create("Ana", "Anić", "Ilica", "1", "Zagreb", "Grad Zagreb", null);
        lessor.setLessorOib("12345678903");

        lessorRepository.saveAndFlush(lessor);

        assertThat(lessorRepository.findById(lessor.getLessorId())).isPresent();
    }

    /**
     * Točno ono što RegistrationService gradi kad NIAS osoba djeluje u ime tvrtke.
     * {@code toLegalLessor} ne čita registre — ovisnosti su tu samo za konstruktor. Država ne ulazi
     * u ograničenje, pa mock šifrarnika (bez Hrvatske) ne mijenja ishod.
     */
    private static LessorEntity legalLessor(String name, String mbs) {
        SubjectProfile representative = new SubjectProfile("98765432106", "OTAC", "PET",
                SubjectDataSource.OIB_REGISTAR, null, "Vukovarska", "2", "Split", "21000", "Split",
                "Splitsko-dalmatinska", SubjectDataSource.OIB_REGISTAR);
        LegalEntityProfile profile = new LegalEntityProfile("12345678903", name, mbs,
                mbs == null ? null : "Ilica", mbs == null ? null : "1", mbs == null ? null : "Zagreb",
                mbs == null ? null : "10000", mbs == null ? null : "GRAD ZAGREB",
                mbs == null ? null : "Grad Zagreb", mbs == null ? null : SubjectDataSource.OIB_REGISTAR,
                representative);
        return new SubjectProfileService(mock(SubjectRegistry.class), mock(LegalEntityRegistry.class),
                mock(LegalRepresentativeSource.class), mock(CountyByMunicipalityResolver.class),
                mock(CountryRepository.class))
                .toLegalLessor(profile);
    }

    private static LessorEntity nonEu() {
        return LessorEntity.createNonEuRegistration("John", "Doe", "Main St 1",
                "john@example.com", "john@example.com", "hash",
                LocalDate.of(1980, 1, 1), 276, "DE123", "+49123");
    }

    /** {@code ADD CONSTRAINT} iz {@code <sql>} bloka changeseta (ne iz {@code <rollback>}). */
    private static String addConstraintSql() throws Exception {
        try (InputStream in = new ClassPathResource(CHANGESET).getInputStream()) {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            NodeList sqls = factory.newDocumentBuilder().parse(in)
                    .getElementsByTagNameNS("*", "sql");
            for (int i = 0; i < sqls.getLength(); i++) {
                String text = sqls.item(i).getTextContent();
                boolean inRollback = "rollback".equals(sqls.item(i).getParentNode().getLocalName());
                if (!inRollback && text.contains("ADD CONSTRAINT " + CONSTRAINT)) {
                    return text.trim();
                }
            }
        }
        throw new IllegalStateException(CHANGESET + " nema ADD CONSTRAINT " + CONSTRAINT);
    }
}
