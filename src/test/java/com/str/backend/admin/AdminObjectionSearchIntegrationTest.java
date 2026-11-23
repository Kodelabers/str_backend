package com.str.backend.admin;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.common.Zagreb;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.domain.RnStatus;
import com.str.backend.egop.EgopPismenoEntity;
import com.str.backend.egop.EgopPismenoRepository;
import com.str.backend.exception.BusinessException;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.objection.AdminObjectionRowDto;
import com.str.backend.objection.AdminObjectionStatsDto;
import com.str.backend.objection.ObjectionKind;
import com.str.backend.objection.ObjectionResolution;
import com.str.backend.objection.SuspensionObjectionEntity;
import com.str.backend.objection.SuspensionObjectionRepository;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.RegistrationNumberLogEntity;
import com.str.backend.rn.RegistrationNumberLogRepository;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RucniAkt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Popis očitovanja i prigovora preko svih RB-ova (MINTS 17.11., točka 1) nad stvarnom pohranom: pravilo
 * neriješenosti (predan; županijski zatvoren bez urudžbe) iz JPQL konstanti, filtri, kartice i opseg županije.
 *
 * <p>Podnesci: 91 predan očitovanje (prije 10 dana, Rijeka); 92 županijski prigovor zatvoren bez urudžbe (danas,
 * Split); 93 isto, ali urudžbiran (Split); 94 uvažen prigovor na povlačenje; 95 zatvoren u postupku MINTS-a.
 */
@SpringBootTest
@ActiveProfiles("test")
class AdminObjectionSearchIntegrationTest {

    private static final String OIB = "99999999990";
    private static final String PGZ = "Primorsko-goranska županija";
    private static final String SDZ = "Splitsko-dalmatinska županija";
    private static final byte[] PDF = {0x25, 0x50, 0x44, 0x46};
    private static final AdminObjectionService.Filter UNRESOLVED =
            new AdminObjectionService.Filter(null, null, null, null, null, null, null);

    @Autowired private AdminObjectionService service;
    @Autowired private SuspensionObjectionRepository objectionRepository;
    @Autowired private RnRepository rnRepository;
    @Autowired private AccommodationRepository accommodationRepository;
    @Autowired private SubmissionRepository submissionRepository;
    @Autowired private LessorRepository lessorRepository;
    @Autowired private RegistrationNumberLogRepository logRepository;
    @Autowired private EgopPismenoRepository pismenoRepository;
    @Autowired private JdbcTemplate jdbc;

    private UUID lessorId;
    private final Map<String, UUID> submissions = new HashMap<>();

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM str_rn.egop_pismeno WHERE rn LIKE 'HR000000%'");
        objectionRepository.deleteAll();
        jdbc.update("DELETE FROM str_rn.registration_number_log WHERE rn LIKE 'HR000000%'");
        rnRepository.deleteAll();
        accommodationRepository.deleteAll();
        submissionRepository.deleteAll();
        lessorRepository.deleteAll();

        LessorEntity l = LessorEntity.create("Ivan", "Ivić", "Korzo", "2", "Rijeka", PGZ, "ivan@example.hr");
        l.setLessorOib(OIB);
        lessorId = lessorRepository.save(l).getLessorId();
        seedRn("HR00000091", PGZ);
        seedRn("HR00000092", SDZ);
        seedRn("HR00000093", SDZ);
        seedRn("HR00000094", PGZ);
        seedRn("HR00000095", PGZ);

        SuspensionObjectionEntity o91 = objection("HR00000091", RnStatus.SUSPENSION_PROPOSED, null);
        submittedAt(o91, Instant.now().minus(10, ChronoUnit.DAYS));

        SuspensionObjectionEntity o92 = objection("HR00000092", RnStatus.SUSPENDED, rucniPrijelaz("HR00000092"));
        o92.close("SYSTEM");
        objectionRepository.saveAndFlush(o92);

        SuspensionObjectionEntity o93 = objection("HR00000093", RnStatus.SUSPENDED, rucniPrijelaz("HR00000093"));
        o93.close("SYSTEM");
        objectionRepository.saveAndFlush(o93);
        pismenoRepository.saveAndFlush(EgopPismenoEntity.rucniPodnesak(submissions.get("HR00000093"), "HR00000093",
                o93.getObjectionId(), "Prigovor na suspenziju registracijskog broja", "UP/I-334-06/26-01/1",
                "2181-01-26-1", PDF));

        SuspensionObjectionEntity o94 = objection("HR00000094", RnStatus.WITHDRAWN, null);
        o94.accept("INTERNAL:12345678903", null);
        objectionRepository.saveAndFlush(o94);

        SuspensionObjectionEntity o95 = objection("HR00000095", RnStatus.SUSPENSION_PROPOSED, null);
        o95.close("SYSTEM");
        objectionRepository.saveAndFlush(o95);
    }

    @Test
    void defaultsToUnresolved_includingCountyClosedWithoutRegistration_oldestFirst() {
        Page<AdminObjectionRowDto> page = service.search(UNRESOLVED, null, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(AdminObjectionRowDto::rn)
                .containsExactly("HR00000091", "HR00000092");
        assertThat(page.getTotalElements()).isEqualTo(2);
        AdminObjectionRowDto first = page.getContent().get(0);
        assertThat(first.resolved()).isFalse();
        assertThat(first.kind()).isEqualTo(ObjectionKind.OCITOVANJE_SUSPENZIJE);
        assertThat(first.lessorName()).isEqualTo("Ivan Ivić");
        assertThat(first.lessorOib()).isEqualTo(OIB);
        assertThat(first.accommodationName()).isEqualTo("Objekt HR00000091");
        assertThat(first.county()).isEqualTo(PGZ);
        assertThat(first.countyProcedure()).isFalse();
        assertThat(page.getContent().get(1).kind()).isEqualTo(ObjectionKind.PRIGOVOR_SUSPENZIJE);
        assertThat(page.getContent().get(1).countyProcedure()).isTrue();
    }

    @Test
    void resolvedAndAll() {
        Page<AdminObjectionRowDto> resolved = service.search(filter(null, ObjectionResolution.RESOLVED, null),
                null, PageRequest.of(0, 20));
        assertThat(resolved.getContent()).extracting(AdminObjectionRowDto::rn)
                .containsExactlyInAnyOrder("HR00000093", "HR00000094", "HR00000095");
        assertThat(resolved.getContent()).allMatch(AdminObjectionRowDto::resolved);
        AdminObjectionRowDto accepted = resolved.getContent().stream()
                .filter(r -> r.rn().equals("HR00000094")).findFirst().orElseThrow();
        assertThat(accepted.kind()).isEqualTo(ObjectionKind.PRIGOVOR_POVLACENJA);
        assertThat(accepted.resolvedAt()).isNotNull();

        assertThat(service.search(filter(null, ObjectionResolution.ALL, null), null, PageRequest.of(0, 20))
                .getTotalElements()).isEqualTo(5);
    }

    @Test
    void kindFilter_usesRnStatusAtSubmission() {
        assertThat(service.search(filter(ObjectionKind.PRIGOVOR_SUSPENZIJE, ObjectionResolution.ALL, null), null,
                        PageRequest.of(0, 20)).getContent())
                .extracting(AdminObjectionRowDto::rn).containsExactlyInAnyOrder("HR00000092", "HR00000093");
        assertThat(service.search(filter(ObjectionKind.OCITOVANJE_POVLACENJA, ObjectionResolution.ALL, null), null,
                PageRequest.of(0, 20)).getTotalElements()).isZero();
    }

    @Test
    void olderThanDays_onlyAllowedThresholds() {
        assertThat(service.search(filter(null, null, 5), null, PageRequest.of(0, 20)).getContent())
                .extracting(AdminObjectionRowDto::rn).containsExactly("HR00000091");
        assertThat(service.search(filter(null, null, 15), null, PageRequest.of(0, 20)).getTotalElements()).isZero();
        assertThatThrownBy(() -> service.search(filter(null, null, 7), null, PageRequest.of(0, 20)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.objections.older.than.days");
    }

    @Test
    void textFilters() {
        AdminObjectionService.Filter q = new AdminObjectionService.Filter("ivić hr00000092", null, null, null, null,
                ObjectionResolution.ALL, null);
        assertThat(service.search(q, null, PageRequest.of(0, 20)).getContent())
                .extracting(AdminObjectionRowDto::rn).containsExactly("HR00000092");

        AdminObjectionService.Filter fields = new AdminObjectionService.Filter(null, "IVAN", "9999", "0094", null,
                ObjectionResolution.ALL, null);
        assertThat(service.search(fields, null, PageRequest.of(0, 20)).getContent())
                .extracting(AdminObjectionRowDto::rn).containsExactly("HR00000094");
    }

    @Test
    void countyScope_hidesOtherCounties() {
        List<String> split = List.of(SDZ);
        assertThat(service.search(UNRESOLVED, split, PageRequest.of(0, 20)).getContent())
                .extracting(AdminObjectionRowDto::rn).containsExactly("HR00000092");
        assertThat(service.stats(split)).isEqualTo(new AdminObjectionStatsDto(1, 1, 0));
        assertThat(service.search(UNRESOLVED, List.of(), PageRequest.of(0, 20)).getTotalElements()).isZero();
    }

    @Test
    void stats_countUnresolvedOnly() {
        assertThat(service.stats(null)).isEqualTo(new AdminObjectionStatsDto(2, 1, 1));
    }

    @Test
    void sort_onlyBySubmittedAt() {
        assertThat(service.search(UNRESOLVED, null, PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "submittedAt")))
                .getContent()).extracting(AdminObjectionRowDto::rn).containsExactly("HR00000092", "HR00000091");
        // Drugo svojstvo ne prolazi do upita — vrijedi zadano, najstarije prvo.
        assertThat(service.search(UNRESOLVED, null, PageRequest.of(0, 20, Sort.by("fileContent")))
                .getContent()).extracting(AdminObjectionRowDto::rn).containsExactly("HR00000091", "HR00000092");
    }

    @Test
    void export_writesWorkbook() {
        byte[] xlsx = service.exportXlsx(filter(null, ObjectionResolution.ALL, null), null, null);
        assertThat(xlsx).isNotEmpty();
        // XLSX je ZIP: potpis PK.
        assertThat(xlsx[0]).isEqualTo((byte) 'P');
        assertThat(xlsx[1]).isEqualTo((byte) 'K');
    }

    private static AdminObjectionService.Filter filter(ObjectionKind kind, ObjectionResolution resolution,
                                                       Integer olderThanDays) {
        return new AdminObjectionService.Filter(null, null, null, null, kind, resolution, olderThanDays);
    }

    private SuspensionObjectionEntity objection(String rn, RnStatus rnStatus, UUID rnLogId) {
        return objectionRepository.saveAndFlush(SuspensionObjectionEntity.createForOib(OIB, rn, rnStatus, rnLogId,
                "NIAS:" + OIB, "podnesak.pdf", "application/pdf", PDF));
    }

    private void submittedAt(SuspensionObjectionEntity o, Instant at) {
        jdbc.update("UPDATE str_rn.suspension_objection SET submitted_at = ? WHERE objection_id = ?",
                Timestamp.from(at), o.getObjectionId());
    }

    /** Prijelaz s ručnim aktom županije — podnesak na njega je u županijskom postupku. */
    private UUID rucniPrijelaz(String rn) {
        RucniAkt akt = new RucniAkt("UP/I-334-06/26-01/1", "2181-01-26-1", "Splitsko-dalmatinska županija",
                "12345678903", "Upravni odjel za turizam", null);
        return logRepository.saveAndFlush(RegistrationNumberLogEntity.transition(rn, "ACTIVE", "SUSPENDED",
                "INSPECTION", "INTERNAL:12345678903", null, akt)).getLogId();
    }

    private void seedRn(String rn, String county) {
        SubmissionEntity submission = submissionRepository.save(
                SubmissionEntity.create("FN-" + rn, lessorId, 1L, Instant.now(), null, null));
        submissions.put(rn, submission.getSubmissionId());
        AccommodationEntity acc = AccommodationEntity.create(submission.getSubmissionId(), county, "Grad",
                "Ulica", rn.substring(rn.length() - 2), 4, 8, OfferType.PRIMARY_RESIDENCE, Offering.WHOLE,
                true, false, true);
        acc.setName("Objekt " + rn);
        accommodationRepository.save(acc);
        rnRepository.save(RnEntity.issue(rn, submission.getSubmissionId(), acc.getAccommodationId(), Zagreb.today()));
    }
}
