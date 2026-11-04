package com.str.backend.objection;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.domain.WithdrawalReason;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.lessor.LessorRnSummaryDto;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RnService;
import com.str.backend.rn.SuspensionDeadlineJob;
import com.str.backend.rn.event.RnLifecycleEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
@RecordApplicationEvents
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
    @Autowired private SuspensionObjectionAdminService adminService;
    @Autowired private SuspensionDeadlineJob deadlineJob;
    @Autowired private ApplicationEvents events;

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
        rnService.suspend("HR00000021", RnTrigger.INSPECTION, LocalDate.now().plusDays(15), null,
                "INTERNAL:12345678903", List.of());
        UUID id = objectionRepository.saveAndFlush(objection("HR00000021")).getObjectionId();

        rnService.withdraw("HR00000021", WithdrawalReason.DATA_NOT_CORRECTED, null, "INTERNAL:12345678903", List.of());

        SuspensionObjectionEntity closed = objectionRepository.findById(id).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(SuspensionObjectionStatus.CLOSED);
        assertThat(closed.getResolvedBy()).isEqualTo("SYSTEM");
    }

    /**
     * Rok je istekao, a prigovor predan u roku zadržao je job. Neuvažavanje odmah suspendira u ime
     * službenika — jedan prijelaz, dakle jedan akt o suspenziji — a prigovor ostaje REJECTED.
     * Noćni job nakon toga RB više ne dira.
     */
    @Test
    void rejectAfterDeadline_suspendsOnce_andKeepsRejection() {
        String official = "INTERNAL:12345678903";
        rnService.suspend("HR00000021", RnTrigger.INSPECTION, LocalDate.now(), null, official, List.of());
        UUID id = objectionRepository.saveAndFlush(objection("HR00000021")).getObjectionId();
        LocalDate yesterday = LocalDate.now().minusDays(1);
        jdbc.update("UPDATE str_rn.registration_number SET suspension_deadline = ? WHERE rn = ?",
                yesterday, "HR00000021");
        jdbc.update("UPDATE str_rn.suspension_objection SET submitted_at = ? WHERE objection_id = ?",
                Timestamp.from(yesterday.atTime(12, 0).atZone(ZoneId.of("Europe/Zagreb")).toInstant()), id);

        // Prigovor se odnosi na ovaj prijedlog (servis to upisuje sam; helper ga stvara bez veze).
        jdbc.update("UPDATE str_rn.suspension_objection SET rn_log_id = (SELECT log_id FROM"
                + " str_rn.registration_number_log WHERE rn = ? AND to_status = 'SUSPENSION_PROPOSED')"
                + " WHERE objection_id = ?", "HR00000021", id);

        deadlineJob.run();
        assertThat(rnRepository.findById("HR00000021").orElseThrow().getStatus())
                .as("prigovor predan u roku zadržava suspenziju")
                .isEqualTo(RnStatus.SUSPENSION_PROPOSED);

        adminService.reject("HR00000021", id, official, "Nije dostavljena suglasnost");
        deadlineJob.run();

        assertThat(rnRepository.findById("HR00000021").orElseThrow().getStatus()).isEqualTo(RnStatus.SUSPENDED);
        assertThat(events.stream(RnLifecycleEvent.class).filter(e -> e.to() == RnStatus.SUSPENDED))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.trigger()).isEqualTo(RnTrigger.DEADLINE_EXCEEDED);
                    assertThat(e.actor()).isEqualTo(official);
                });
        SuspensionObjectionEntity rejected = objectionRepository.findById(id).orElseThrow();
        assertThat(rejected.getStatus()).isEqualTo(SuspensionObjectionStatus.REJECTED);
        assertThat(rejected.getResolvedBy()).isEqualTo(official);
    }

    /** Dok rok teče, neuvažavanje RB ostavlja pred suspenzijom. */
    @Test
    void rejectBeforeDeadline_keepsProposal() {
        rnService.suspend("HR00000021", RnTrigger.INSPECTION, LocalDate.now(), null, "INTERNAL:12345678903", List.of());
        UUID id = objectionRepository.saveAndFlush(objection("HR00000021")).getObjectionId();

        adminService.reject("HR00000021", id, "INTERNAL:12345678903", null);

        assertThat(rnRepository.findById("HR00000021").orElseThrow().getStatus())
                .isEqualTo(RnStatus.SUSPENSION_PROPOSED);
        assertThat(events.stream(RnLifecycleEvent.class).filter(e -> e.to() == RnStatus.SUSPENDED)).isEmpty();
    }

    /** Upit joba: prigovor predan do kraja dana roka (po hrvatskom vremenu) je u roku, kasniji nije. */
    @Test
    void submittedInTimeQuery_usesEndOfDeadlineDay() {
        LocalDate deadline = LocalDate.of(2026, 7, 10);
        Instant endOfDay = deadline.plusDays(1).atStartOfDay(ZoneId.of("Europe/Zagreb")).toInstant();
        UUID id = objectionRepository.saveAndFlush(objection("HR00000022")).getObjectionId();

        jdbc.update("UPDATE str_rn.suspension_objection SET submitted_at = ? WHERE objection_id = ?",
                Timestamp.from(endOfDay.minusSeconds(1)), id);
        assertThat(objectionRepository.existsByRnAndStatusAndSubmittedAtBefore(
                "HR00000022", SuspensionObjectionStatus.SUBMITTED, endOfDay)).isTrue();

        jdbc.update("UPDATE str_rn.suspension_objection SET submitted_at = ? WHERE objection_id = ?",
                Timestamp.from(endOfDay), id);
        assertThat(objectionRepository.existsByRnAndStatusAndSubmittedAtBefore(
                "HR00000022", SuspensionObjectionStatus.SUBMITTED, endOfDay)).isFalse();
    }

    /**
     * Upit joba drži prijedlog samo prigovorom na <b>taj</b> prijedlog: po {@code rn_log_id}, a stariji
     * prigovor bez njega po vremenu predaje. Zaostali prigovor na raniji prijedlog ga ne drži.
     */
    @Test
    void submittedForProposalQuery_matchesOnlyCurrentProposal() {
        Instant prijedlogAt = Instant.parse("2026-07-01T08:00:00Z");
        Instant before = Instant.parse("2026-07-11T00:00:00Z");
        UUID prijedlog = UUID.randomUUID();
        UUID raniji = UUID.randomUUID();

        UUID naRaniji = objectionRepository.saveAndFlush(SuspensionObjectionEntity.createForOib(OIB, "HR00000022",
                RnStatus.SUSPENSION_PROPOSED, raniji, "NIAS:" + OIB, "p.pdf", "application/pdf", PDF)).getObjectionId();
        jdbc.update("UPDATE str_rn.suspension_objection SET submitted_at = ? WHERE objection_id = ?",
                Timestamp.from(prijedlogAt.plusSeconds(60)), naRaniji);
        assertThat(objectionRepository.existsSubmittedForProposal("HR00000022", prijedlog, prijedlogAt, before))
                .as("prigovor na raniji prijedlog").isFalse();

        jdbc.update("UPDATE str_rn.suspension_objection SET rn_log_id = ? WHERE objection_id = ?", prijedlog, naRaniji);
        assertThat(objectionRepository.existsSubmittedForProposal("HR00000022", prijedlog, prijedlogAt, before))
                .as("prigovor na ovaj prijedlog").isTrue();

        jdbc.update("UPDATE str_rn.suspension_objection SET rn_log_id = NULL, submitted_at = ? WHERE objection_id = ?",
                Timestamp.from(prijedlogAt.minusSeconds(60)), naRaniji);
        assertThat(objectionRepository.existsSubmittedForProposal("HR00000022", prijedlog, prijedlogAt, before))
                .as("stari prigovor bez rn_log_id, predan prije prijedloga").isFalse();

        jdbc.update("UPDATE str_rn.suspension_objection SET submitted_at = ? WHERE objection_id = ?",
                Timestamp.from(prijedlogAt.plusSeconds(60)), naRaniji);
        assertThat(objectionRepository.existsSubmittedForProposal("HR00000022", prijedlog, prijedlogAt, before))
                .as("stari prigovor bez rn_log_id, predan nakon prijedloga").isTrue();
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
