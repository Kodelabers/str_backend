package com.str.backend.objection;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.lessor.LessorRnSummaryDto;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RnService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * Prigovor nad stvarnom pohranom: status zadnjeg prigovora na „Mojim registracijskim brojevima"
 * (podupit u konstruktorskom izrazu jedinični test ne bi uhvatio), zatvaranje neobrađenog prigovora
 * u transakciji prijelaza RB-a i CHECK ograničenja primijenjena iz samog changeseta (test profil
 * gradi shemu iz entiteta, pa ih inače ne bi bilo).
 */
@SpringBootTest
@ActiveProfiles("test")
class SuspensionObjectionIntegrationTest {

    private static final String CHANGESET = "db/changelog/changes/145-suspension-objection.xml";
    private static final String OIB = "99999999990";
    private static final byte[] PDF = {0x25, 0x50, 0x44, 0x46};

    @Autowired private SuspensionObjectionRepository objectionRepository;
    @Autowired private RnRepository rnRepository;
    @Autowired private RnService rnService;
    @Autowired private AccommodationRepository accommodationRepository;
    @Autowired private SubmissionRepository submissionRepository;
    @Autowired private LessorRepository lessorRepository;
    @Autowired private JdbcTemplate jdbc;

    private UUID lessorId;

    @BeforeEach
    void seed() {
        objectionRepository.deleteAll();
        rnRepository.deleteAll();
        accommodationRepository.deleteAll();
        submissionRepository.deleteAll();
        lessorRepository.deleteAll();

        lessorId = lessor();
        seedRn("HR00000021");   // bez prigovora
        seedRn("HR00000022");   // odbijen pa ponovo predan
        seedRn("HR00000023");   // samo odbijen
    }

    @Test
    void summary_carriesStatusOfLatestObjection() throws InterruptedException {
        SuspensionObjectionEntity rejected = objectionRepository.saveAndFlush(objection("HR00000022"));
        rejected.reject("INTERNAL:12345678903", null);
        objectionRepository.saveAndFlush(rejected);
        Thread.sleep(5);    // dva prigovora istog RB-a ne dijele trenutak predaje
        objectionRepository.saveAndFlush(objection("HR00000022"));
        SuspensionObjectionEntity onlyRejected = objectionRepository.saveAndFlush(objection("HR00000023"));
        onlyRejected.reject("INTERNAL:12345678903", null);
        objectionRepository.saveAndFlush(onlyRejected);

        for (List<LessorRnSummaryDto> rows : List.of(rnRepository.findByLessorOib(OIB), rnRepository.findByLessorId(lessorId))) {
            assertThat(rows).extracting(LessorRnSummaryDto::rn, LessorRnSummaryDto::objectionStatus)
                    .containsExactlyInAnyOrder(
                            tuple("HR00000021", null),
                            tuple("HR00000022", SuspensionObjectionStatus.SUBMITTED),
                            tuple("HR00000023", SuspensionObjectionStatus.REJECTED));
        }
    }

    /** Službenik povuče RB s otvorenim prigovorom — prigovor se zatvara u istoj transakciji. */
    @Test
    void withdrawingRn_closesSubmittedObjection() {
        rnService.suspend("HR00000021", RnTrigger.INSPECTION, null, null, "INTERNAL:12345678903");
        UUID id = objectionRepository.saveAndFlush(objection("HR00000021")).getObjectionId();

        rnService.withdraw("HR00000021", null, "INTERNAL:12345678903");

        SuspensionObjectionEntity closed = objectionRepository.findById(id).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(SuspensionObjectionStatus.CLOSED);
        assertThat(closed.getResolvedBy()).isEqualTo("SYSTEM");
    }

    @Test
    void checkConstraints_fromChangeset() throws Exception {
        for (String statement : addConstraintStatements()) {
            jdbc.execute(statement);
        }
        try {
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO str_rn.suspension_objection (objection_id, rn, submitted_by, rn_status, file_name,"
                            + " content_type, file_size, file_content, status, submitted_at)"
                            + " VALUES (?, 'HR00000021', 'X', 'SUSPENDED', 'p.pdf', 'application/pdf', 1, ?,"
                            + " 'SUBMITTED', CURRENT_TIMESTAMP)", UUID.randomUUID(), PDF))
                    .as("bez OIB-a i lessor_id")
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO str_rn.suspension_objection (objection_id, rn, lessor_oib, submitted_by, rn_status,"
                            + " file_name, content_type, file_size, file_content, status, submitted_at)"
                            + " VALUES (?, 'HR00000021', ?, 'X', 'SUSPENDED', 'p.pdf', 'application/pdf', 1, ?,"
                            + " 'PENDING', CURRENT_TIMESTAMP)", UUID.randomUUID(), OIB, PDF))
                    .as("nepoznat status")
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("ALTER TABLE str_rn.suspension_objection DROP CONSTRAINT IF EXISTS ck_suspension_objection_lessor");
            jdbc.execute("ALTER TABLE str_rn.suspension_objection DROP CONSTRAINT IF EXISTS ck_suspension_objection_status");
        }
    }

    /**
     * {@code ADD CONSTRAINT} naredbe iz {@code <sql>} bloka changeseta. Parcijalni unique index
     * ({@code CREATE UNIQUE INDEX … WHERE}) H2 ne podržava — njega pokriva servisni test.
     */
    private static String[] addConstraintStatements() throws Exception {
        try (InputStream in = new ClassPathResource(CHANGESET).getInputStream()) {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            NodeList sqls = factory.newDocumentBuilder().parse(in).getElementsByTagNameNS("*", "sql");
            for (int i = 0; i < sqls.getLength(); i++) {
                if (!"rollback".equals(sqls.item(i).getParentNode().getLocalName())) {
                    return Arrays.stream(sqls.item(i).getTextContent().split(";"))
                            .map(String::trim)
                            .filter(s -> s.startsWith("ALTER TABLE") && s.contains("ADD CONSTRAINT"))
                            .toArray(String[]::new);
                }
            }
        }
        throw new IllegalStateException(CHANGESET + " nema <sql> blok");
    }

    private SuspensionObjectionEntity objection(String rn) {
        return SuspensionObjectionEntity.createForOib(OIB, rn, RnStatus.SUSPENSION_PROPOSED, null, "NIAS:" + OIB,
                "prigovor.pdf", "application/pdf", PDF);
    }

    private UUID lessor() {
        LessorEntity l = LessorEntity.create("Ivan", "Ivić", "Korzo", "2", "Rijeka",
                "Primorsko-goranska županija", OIB + "@example.hr");
        l.setLessorOib(OIB);
        return lessorRepository.save(l).getLessorId();
    }

    private void seedRn(String rn) {
        SubmissionEntity submission = submissionRepository.save(
                SubmissionEntity.create("FN-" + rn, lessorId, 1L, Instant.now(), null, null));
        AccommodationEntity acc = AccommodationEntity.create(submission.getSubmissionId(),
                "Primorsko-goranska županija", "Rijeka", "Korzo", rn.substring(rn.length() - 2),
                4, 8, OfferType.PRIMARY_RESIDENCE, Offering.WHOLE, true, false, true);
        acc.setName("Objekt " + rn);
        accommodationRepository.save(acc);
        rnRepository.save(RnEntity.issue(rn, submission.getSubmissionId(), acc.getAccommodationId(), LocalDate.now()));
    }
}
