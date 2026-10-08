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
        LessorEntity lessor = legalEntityProfiles().toLegalLessor(
                "12345678903", "TESTNA TVRTKA d.o.o.", "98765432106", "OTAC", "PET");

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
        LessorEntity lessor = legalEntityProfiles().toLegalLessor(
                "12345678903", null, "98765432106", "OTAC", "PET");

        assertThatThrownBy(() -> lessorRepository.saveAndFlush(lessor))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void naturalPerson_isUnaffected() {
        LessorEntity lessor = LessorEntity.create("Ana", "Anić", "Ilica", "1", "Zagreb", "Grad Zagreb", null);
        lessor.setLessorOib("12345678903");

        lessorRepository.saveAndFlush(lessor);

        assertThat(lessorRepository.findById(lessor.getLessorId())).isPresent();
    }

    /**
     * {@code toLegalLessor} ne čita registar ni resolver županije — ovisnosti su tu samo za konstruktor.
     * Država ne ulazi u ograničenje, pa mock šifrarnika (bez Hrvatske) ne mijenja ishod.
     */
    private static SubjectProfileService legalEntityProfiles() {
        return new SubjectProfileService(mock(SubjectRegistry.class), mock(CountyByMunicipalityResolver.class),
                mock(CountryRepository.class));
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
