package com.str.backend.lessor;

import com.str.backend.address.CountryRepository;
import com.str.backend.address.CountyByMunicipalityResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Država prebivališta NIAS iznajmljivača nad stvarnom pohranom: novi iznajmljivač je dobiva pri
 * kreiranju, a postojeće popunjava changeset 134. SQL changeseta izvršava se <b>iz same datoteke</b>,
 * kao u {@link LessorLegalEntityCheckConstraintTest}, da test ne može otići u drugom smjeru od baze.
 *
 * <p>ID Hrvatske namjerno nije onaj iz lokalnog seeda — oba puta ga moraju naći po ISO kodu.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class NiasLessorCountryOfResidenceTest {

    private static final String CHANGESET = "db/changelog/changes/134-lessor-nias-country-of-residence.xml";
    private static final int CROATIA_ID = 191;
    private static final int GERMANY_ID = 276;
    private static final String OIB = "12312312316";

    @Autowired
    private LessorRepository lessorRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void countries() {
        jdbc.update("DELETE FROM str.country");
        jdbc.update("INSERT INTO str.country (id, name, iso2_alpha, active) VALUES (?,?,?,?)",
                GERMANY_ID, "Njemačka", "DE", true);
        jdbc.update("INSERT INTO str.country (id, name, iso2_alpha, active) VALUES (?,?,?,?)",
                CROATIA_ID, "Hrvatska", "HR", true);
    }

    // ── kreiranje ──────────────────────────────────────────────────────────

    @Test
    void newNiasLessor_isStoredWithCroatia() {
        SubjectRegistry registry = mock(SubjectRegistry.class);
        when(registry.findByOib(OIB)).thenReturn(Optional.of(new RegistrySubject(OIB, "Pero", "Perić", null,
                "Ilica", "1", "Zagreb", null, null, "Grad Zagreb", SubjectDataSource.STR_SUBJEKT)));

        LessorEntity lessor = profiles(registry).resolveLessor(OIB, "Pero", "Perić", null);
        lessorRepository.saveAndFlush(lessor);

        assertThat(storedCountry(lessor.getLessorId())).isEqualTo(CROATIA_ID);
    }

    @Test
    void newNiasLegalEntity_isStoredWithCroatia() {
        LessorEntity lessor = profiles(mock(SubjectRegistry.class)).resolveLegalLessor(
                "33333333360", "TESTNA TVRTKA d.o.o.", "70000000004", "Ana", "Horvat",
                new EnteredAddress("Ilica", "1", "10000", "Zagreb", null, "Grad Zagreb", "080123456"));
        lessorRepository.saveAndFlush(lessor);

        assertThat(storedCountry(lessor.getLessorId())).isEqualTo(CROATIA_ID);
    }

    // ── changeset 134 ──────────────────────────────────────────────────────

    @Test
    void changeset_setsCroatia_onlyForNiasLessorsWithoutCountry() throws Exception {
        LessorEntity nias = LessorEntity.create("Pero", "Perić", "Ilica", "1", "Zagreb", "Grad Zagreb", null);
        nias.setLessorOib(OIB);
        LessorEntity niasWithCountry = LessorEntity.create("Iva", "Ivić", "Korzo", "2", "Rijeka",
                "Primorsko-goranska", null);
        niasWithCountry.setLessorOib("33333333360");
        niasWithCountry.applyCountryOfResidence(GERMANY_ID);
        LessorEntity nonEu = LessorEntity.createNonEuRegistration("John", "Doe", "Main St 1",
                "john@example.com", "john@example.com", "hash",
                LocalDate.of(1980, 1, 1), null, "DE123", "+49123");
        lessorRepository.saveAndFlush(nias);
        lessorRepository.saveAndFlush(niasWithCountry);
        lessorRepository.saveAndFlush(nonEu);

        assertThat(jdbc.queryForObject(changesetSql("sqlCheck"), Integer.class)).isEqualTo(1);
        jdbc.update(changesetSql("sql"));

        assertThat(storedCountry(nias.getLessorId())).isEqualTo(CROATIA_ID);
        assertThat(storedCountry(niasWithCountry.getLessorId())).isEqualTo(GERMANY_ID);
        assertThat(storedCountry(nonEu.getLessorId())).isNull();
    }

    /** Bez Hrvatske u šifrarniku preduvjet ne prolazi, pa se changeset preskače (onFail=CONTINUE). */
    @Test
    void changesetPrecondition_failsWithoutCroatia() throws Exception {
        jdbc.update("DELETE FROM str.country WHERE iso2_alpha = 'HR'");

        assertThat(jdbc.queryForObject(changesetSql("sqlCheck"), Integer.class)).isZero();
    }

    private SubjectProfileService profiles(SubjectRegistry registry) {
        return new SubjectProfileService(registry, mock(LegalEntityRegistry.class),
                mock(LegalRepresentativeSource.class), mock(CountyByMunicipalityResolver.class), countryRepository);
    }

    /** Iz baze, ne iz konteksta perzistencije — stupac je {@code updatable = false}. */
    private Integer storedCountry(UUID lessorId) {
        return jdbc.queryForObject("SELECT country_of_residence_id FROM str_rn.lessor WHERE lessor_id = ?",
                Integer.class, lessorId);
    }

    /** Prvi element zadanog imena izvan {@code <rollback>}. */
    private static String changesetSql(String element) throws Exception {
        try (InputStream in = new ClassPathResource(CHANGESET).getInputStream()) {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            NodeList nodes = factory.newDocumentBuilder().parse(in).getElementsByTagNameNS("*", element);
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                if (!"rollback".equals(node.getParentNode().getLocalName())) {
                    return node.getTextContent().trim();
                }
            }
        }
        throw new IllegalStateException(CHANGESET + " nema <" + element + ">");
    }
}
