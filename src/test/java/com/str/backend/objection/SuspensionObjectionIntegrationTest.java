package com.str.backend.objection;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.common.Zagreb;
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
import com.str.backend.rn.WithdrawalDeadlineJob;
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
    @Autowired private WithdrawalDeadlineJob withdrawalJob;
    @Autowired private com.str.backend.rn.RnReissueGuard reissueGuard;
    @Autowired private com.str.backend.rn.RnVerification verification;
    @Autowired private ApplicationEvents events;

    private UUID lessorId;

    @BeforeEach
    void seed() {
        objectionRepository.deleteAll();
        // Trag ostaje između testova, a brojevi se ponavljaju: stari zapisi bi pomiješali prijedloge.
        jdbc.update("DELETE FROM str_rn.registration_number_log WHERE rn LIKE 'HR000000%'");
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
        rnService.suspend("HR00000022", RnTrigger.INSPECTION, Zagreb.today().plusDays(15), null, "INTERNAL:1", List.of());
        rnService.suspend("HR00000023", RnTrigger.INSPECTION, Zagreb.today().plusDays(15), null, "INTERNAL:1", List.of());
        Thread.sleep(5);    // prigovor je predan nakon prijedloga
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

    /**
     * T12: službenik predloži povlačenje RB-a s otvorenim prigovorom na prijedlog suspenzije — prigovor
     * se zatvara u istoj transakciji, a postupak suspenzije završava (rok suspenzije se briše).
     */
    @Test
    void proposingWithdrawal_closesSubmittedObjection_andEndsSuspensionProcedure() {
        rnService.suspend("HR00000021", RnTrigger.INSPECTION, Zagreb.today().plusDays(15), null,
                "INTERNAL:12345678903", List.of());
        UUID id = objectionRepository.saveAndFlush(objection("HR00000021")).getObjectionId();

        rnService.proposeWithdrawal("HR00000021", WithdrawalReason.DATA_NOT_CORRECTED, Zagreb.today().plusDays(8),
                null, "INTERNAL:12345678903", List.of());

        SuspensionObjectionEntity closed = objectionRepository.findById(id).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(SuspensionObjectionStatus.CLOSED);
        assertThat(closed.getResolvedBy()).isEqualTo("SYSTEM");
        RnEntity rn = rnRepository.findById("HR00000021").orElseThrow();
        assertThat(rn.getStatus()).isEqualTo(RnStatus.WITHDRAWAL_PROPOSED);
        assertThat(rn.getSuspensionDeadline()).isNull();
        assertThat(rn.getWithdrawalDeadline()).isEqualTo(Zagreb.today().plusDays(8));
        assertThat(rn.getStatus().isActiveForPublicUse()).isTrue();
    }

    /** Obustava povlačenja uvijek vraća broj u ACTIVE — i kad je pokrenuto iz bilo kojeg stojećeg statusa. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = RnStatus.class,
            names = {"ACTIVE", "SUSPENSION_PROPOSED", "SUSPENDED"})
    void revokingWithdrawalProposal_alwaysReturnsToActive(RnStatus from) {
        String official = "INTERNAL:12345678903";
        if (from != RnStatus.ACTIVE) {
            rnService.suspend("HR00000021", RnTrigger.INSPECTION, Zagreb.today(), null, official, List.of());
        }
        if (from == RnStatus.SUSPENDED) {
            jdbc.update("UPDATE str_rn.registration_number SET suspension_deadline = ? WHERE rn = ?",
                    Zagreb.today().minusDays(1), "HR00000021");
            deadlineJob.run();
        }
        assertThat(rnRepository.findById("HR00000021").orElseThrow().getStatus()).isEqualTo(from);

        rnService.proposeWithdrawal("HR00000021", WithdrawalReason.DATA_NOT_CORRECTED, Zagreb.today().plusDays(8),
                null, official, List.of());
        rnService.revokeWithdrawalProposal("HR00000021", official);

        RnEntity back = rnRepository.findById("HR00000021").orElseThrow();
        assertThat(back.getStatus()).isEqualTo(RnStatus.ACTIVE);
        assertThat(back.getWithdrawalDeadline()).isNull();
        assertThat(back.getSuspensionDeadline()).isNull();
        deadlineJob.run();
        assertThat(rnRepository.findById("HR00000021").orElseThrow().getStatus())
                .as("postupak suspenzije je završio").isEqualTo(RnStatus.ACTIVE);
    }

    /** Prijedlog povlačenja ne nastaje iz konačnog statusa ni iz već postojećeg prijedloga (409). */
    @Test
    void proposeWithdrawal_fromWithdrawalProposedRevokedOrWithdrawn_is409() {
        String official = "INTERNAL:12345678903";
        rnService.proposeWithdrawal("HR00000021", WithdrawalReason.DATA_NOT_CORRECTED, Zagreb.today().plusDays(8),
                null, official, List.of());
        assertThatThrownBy(() -> rnService.proposeWithdrawal("HR00000021", WithdrawalReason.DATA_NOT_CORRECTED,
                Zagreb.today().plusDays(8), null, official, List.of()))
                .as("WITHDRAWAL_PROPOSED")
                .isInstanceOf(com.str.backend.exception.IllegalStatusTransitionException.class);

        withdrawByDeadline("HR00000021");
        assertThatThrownBy(() -> rnService.proposeWithdrawal("HR00000021", WithdrawalReason.DATA_NOT_CORRECTED,
                Zagreb.today().plusDays(8), null, official, List.of()))
                .as("WITHDRAWN")
                .isInstanceOf(com.str.backend.exception.IllegalStatusTransitionException.class);

        jdbc.update("UPDATE str_rn.registration_number SET status = 'REVOKED' WHERE rn = ?", "HR00000022");
        assertThatThrownBy(() -> rnService.proposeWithdrawal("HR00000022", WithdrawalReason.DATA_NOT_CORRECTED,
                Zagreb.today().plusDays(8), null, official, List.of()))
                .as("REVOKED")
                .isInstanceOf(com.str.backend.exception.IllegalStatusTransitionException.class);
    }

    /**
     * Prigovor (očitovanje) na prijedlog povlačenja ostaje otvoren kroz WITHDRAWAL_PROPOSED → WITHDRAWN
     * (istek roka): službenik o njemu i dalje odlučuje, a uvažavanje broj vraća u ACTIVE (REINSTATE).
     */
    @Test
    void objectionSurvivesWithdrawal_andAcceptReinstates() {
        String official = "INTERNAL:12345678903";
        rnService.proposeWithdrawal("HR00000021", WithdrawalReason.DATA_NOT_CORRECTED, Zagreb.today().plusDays(8),
                null, official, List.of());
        UUID id = objectionRepository.saveAndFlush(objection("HR00000021", RnStatus.WITHDRAWAL_PROPOSED))
                .getObjectionId();

        withdrawByDeadline("HR00000021");
        assertThat(objectionRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(SuspensionObjectionStatus.SUBMITTED);
        RnEntity withdrawn = rnRepository.findById("HR00000021").orElseThrow();
        assertThat(withdrawn.getStatus()).isEqualTo(RnStatus.WITHDRAWN);
        assertThat(withdrawn.getValidTo()).isNotNull();

        adminService.accept("HR00000021", id, official, null);

        RnEntity reinstated = rnRepository.findById("HR00000021").orElseThrow();
        assertThat(reinstated.getStatus()).isEqualTo(RnStatus.ACTIVE);
        assertThat(reinstated.getValidTo()).isNull();
        assertThat(objectionRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(SuspensionObjectionStatus.ACCEPTED);
        assertThat(events.stream(RnLifecycleEvent.class).filter(e -> e.trigger() == RnTrigger.REINSTATE))
                .singleElement();
    }

    /**
     * Uvažavanje prigovora na povučen broj odbija se kad isti eTurizam objekt već ima drugi važeći
     * broj: 409 OBJECTION_RN_REISSUED, a prigovor i status broja ostaju kakvi jesu (rollback).
     */
    @Test
    void acceptOnWithdrawn_whenFacilityReissued_is409AndChangesNothing() {
        String official = "INTERNAL:12345678903";
        rnService.proposeWithdrawal("HR00000021", WithdrawalReason.DATA_NOT_CORRECTED, Zagreb.today().plusDays(8),
                null, official, List.of());
        withdrawByDeadline("HR00000021");
        jdbc.update("UPDATE str_rn.accommodation SET facility_id = '777' WHERE accommodation_id IN"
                + " (SELECT accommodation_id FROM str_rn.registration_number WHERE rn IN (?, ?))",
                "HR00000021", "HR00000022");
        UUID id = objectionRepository.saveAndFlush(objection("HR00000021", RnStatus.WITHDRAWN)).getObjectionId();

        assertThatThrownBy(() -> adminService.accept("HR00000021", id, official, null))
                .isInstanceOfSatisfying(com.str.backend.exception.ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("OBJECTION_RN_REISSUED"));

        assertThat(rnRepository.findById("HR00000021").orElseThrow().getStatus()).isEqualTo(RnStatus.WITHDRAWN);
        assertThat(objectionRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(SuspensionObjectionStatus.SUBMITTED);
    }

    /**
     * Novi broj istog iznajmljivača na istoj adresi (svjež zahtjev bez facility_id i previous_rn) samo
     * upozorava (T12): prigovor se smije predati i uvažiti, a službenik u popisu prigovora vidi
     * {@code brojeviNaIstojAdresi}. Broj drugog iznajmljivača u istoj zgradi se ne navodi.
     */
    @Test
    void sameAddressSameLessor_onlyWarnsInObjectionList() {
        String official = "INTERNAL:12345678903";
        rnService.proposeWithdrawal("HR00000021", WithdrawalReason.DATA_NOT_CORRECTED, Zagreb.today().plusDays(8),
                null, official, List.of());
        withdrawByDeadline("HR00000021");
        objectionRepository.saveAndFlush(objection("HR00000021", RnStatus.WITHDRAWN));
        assertThat(reissueGuard.brojeviNaIstojAdresi("HR00000021")).isEmpty();

        jdbc.update("UPDATE str_rn.accommodation SET street_number = '21' WHERE accommodation_id ="
                + " (SELECT accommodation_id FROM str_rn.registration_number WHERE rn = ?)", "HR00000022");
        assertThat(reissueGuard.noviBrojIzdan("HR00000021")).as("adresa ne blokira").isFalse();
        assertThat(adminService.list("HR00000021"))
                .singleElement()
                .satisfies(d -> assertThat(d.brojeviNaIstojAdresi()).containsExactly("HR00000022"));

        // Ista adresa ne razlikuje jedinice u zgradi: broj drugog iznajmljivača se ne navodi.
        LessorEntity susjed = LessorEntity.create("Ana", "Anić", "Korzo", "21", "Rijeka",
                "Primorsko-goranska županija", "susjed@example.hr");
        susjed.setLessorOib("98765432106");
        UUID susjedId = lessorRepository.save(susjed).getLessorId();
        jdbc.update("UPDATE str_rn.submission SET lessor_id = ? WHERE submission_id ="
                + " (SELECT submission_id FROM str_rn.registration_number WHERE rn = ?)", susjedId, "HR00000022");
        assertThat(reissueGuard.brojeviNaIstojAdresi("HR00000021")).isEmpty();
    }

    /** Upozorenje o adresi puni se samo uz povučen broj; inače je prazna lista. */
    @Test
    void sameAddressWarning_onlyForWithdrawnRn() {
        rnService.suspend("HR00000021", RnTrigger.INSPECTION, Zagreb.today().plusDays(15), null,
                "INTERNAL:12345678903", List.of());
        objectionRepository.saveAndFlush(objection("HR00000021"));
        jdbc.update("UPDATE str_rn.accommodation SET street_number = '21' WHERE accommodation_id ="
                + " (SELECT accommodation_id FROM str_rn.registration_number WHERE rn = ?)", "HR00000022");

        assertThat(adminService.list("HR00000021"))
                .singleElement()
                .satisfies(d -> assertThat(d.brojeviNaIstojAdresi()).isEmpty());
    }

    /**
     * T12: novi broj za isti objekt zatvara otvoreni prigovor na povučeni broj u transakciji izdavanja — i za
     * ponovni zahtjev (previous_rn) i za isti eTurizam objekt (facility_id).
     */
    @Test
    void issuingNewNumber_closesObjectionOnWithdrawnNumber() {
        String official = "INTERNAL:12345678903";
        for (String rn : List.of("HR00000021", "HR00000022")) {
            rnService.proposeWithdrawal(rn, WithdrawalReason.DATA_NOT_CORRECTED, Zagreb.today().plusDays(8), null,
                    official, List.of());
            withdrawByDeadline(rn);
        }
        UUID naPrethodni = objectionRepository.saveAndFlush(objection("HR00000021", RnStatus.WITHDRAWN)).getObjectionId();
        UUID naObjekt = objectionRepository.saveAndFlush(objection("HR00000022", RnStatus.WITHDRAWN)).getObjectionId();

        // Ponovni zahtjev (previous_rn = HR00000021).
        RnEntity novi = issueNew(a -> a.markReissueOf("HR00000021"));
        SuspensionObjectionEntity zatvoren = objectionRepository.findById(naPrethodni).orElseThrow();
        assertThat(zatvoren.getStatus()).isEqualTo(SuspensionObjectionStatus.CLOSED);
        assertThat(zatvoren.getResolvedBy()).isEqualTo("SYSTEM");
        assertThat(zatvoren.getResolutionNote()).contains("Izdan je novi registracijski broj " + novi.getRn());
        assertThat(objectionRepository.findById(naObjekt).orElseThrow().getStatus())
                .as("prigovor na drugi objekt ostaje").isEqualTo(SuspensionObjectionStatus.SUBMITTED);

        // Isti eTurizam objekt (facility_id).
        jdbc.update("UPDATE str_rn.accommodation SET facility_id = '555' WHERE accommodation_id ="
                + " (SELECT accommodation_id FROM str_rn.registration_number WHERE rn = ?)", "HR00000022");
        issueNew(a -> a.setFacilityId("555"));
        assertThat(objectionRepository.findById(naObjekt).orElseThrow().getStatus())
                .isEqualTo(SuspensionObjectionStatus.CLOSED);
    }

    /**
     * Status prigovora na popisu iznajmljivača vezan je uz postupak u tijeku: prigovor iz postupka
     * suspenzije ne prikazuje se u postupku povlačenja, prigovor na prijedlog povlačenja vrijedi i u
     * WITHDRAWN, a u ACTIVE statusa nema.
     */
    @Test
    void lessorSummary_objectionStatusFollowsCurrentProcedure() throws InterruptedException {
        String official = "INTERNAL:12345678903";
        rnService.suspend("HR00000021", RnTrigger.INSPECTION, Zagreb.today().plusDays(15), null, official, List.of());
        Thread.sleep(5);
        SuspensionObjectionEntity naSuspenziju = objectionRepository.saveAndFlush(objection("HR00000021"));
        naSuspenziju.reject(official, null);
        objectionRepository.saveAndFlush(naSuspenziju);
        assertThat(objectionStatusOf("HR00000021")).isEqualTo(SuspensionObjectionStatus.REJECTED);

        rnService.proposeWithdrawal("HR00000021", WithdrawalReason.DATA_NOT_CORRECTED, Zagreb.today().plusDays(8),
                null, official, List.of());
        assertThat(objectionStatusOf("HR00000021")).as("prigovor iz postupka suspenzije").isNull();

        Thread.sleep(5);
        UUID ocitovanje = objectionRepository.saveAndFlush(objection("HR00000021", RnStatus.WITHDRAWAL_PROPOSED))
                .getObjectionId();
        assertThat(objectionStatusOf("HR00000021")).isEqualTo(SuspensionObjectionStatus.SUBMITTED);
        withdrawByDeadline("HR00000021");
        assertThat(objectionStatusOf("HR00000021")).as("očitovanje vrijedi i u WITHDRAWN")
                .isEqualTo(SuspensionObjectionStatus.SUBMITTED);

        adminService.accept("HR00000021", ocitovanje, official, null);
        assertThat(objectionStatusOf("HR00000021")).as("ACTIVE").isNull();
    }

    /**
     * T12: prijedlog povlačenja iz suspenzije javno ostaje suspendiran (verify, inactive, popis
     * iznajmljivača), a obustava ga ipak vraća u ACTIVE.
     */
    @Test
    void withdrawalProposalFromSuspended_staysPubliclySuspended_untilRevoked() {
        String official = "INTERNAL:12345678903";
        rnService.suspend("HR00000021", RnTrigger.INSPECTION, Zagreb.today(), null, official, List.of());
        jdbc.update("UPDATE str_rn.registration_number SET suspension_deadline = ? WHERE rn = ?",
                Zagreb.today().minusDays(1), "HR00000021");
        deadlineJob.run();
        rnService.proposeWithdrawal("HR00000021", WithdrawalReason.DATA_NOT_CORRECTED, Zagreb.today().plusDays(8),
                null, official, List.of());

        RnEntity rn = rnRepository.findById("HR00000021").orElseThrow();
        assertThat(rn.isWithdrawalFromSuspended()).isTrue();
        assertThat(rn.isValidForPublicUse()).isFalse();
        assertThat(rn.publicStatus()).isEqualTo(RnStatus.SUSPENDED);
        assertThat(verification.verify("HR00000021").status()).isEqualTo(RnStatus.SUSPENDED);
        assertThat(verification.verify("HR00000021").registrationNumber()).isNull();
        assertThat(rnService.inactive(null)).extracting(RnEntity::getRn).contains("HR00000021");
        assertThat(rnRepository.findByLessorOib(OIB)).filteredOn(r -> r.rn().equals("HR00000021"))
                .singleElement().satisfies(r -> assertThat(r.prijedlogIzSuspenzije()).isTrue());

        rnService.revokeWithdrawalProposal("HR00000021", official);
        RnEntity back = rnRepository.findById("HR00000021").orElseThrow();
        assertThat(back.getStatus()).isEqualTo(RnStatus.ACTIVE);
        assertThat(back.isWithdrawalFromSuspended()).isFalse();
        assertThat(verification.verify("HR00000021").status()).isEqualTo(RnStatus.ACTIVE);
    }

    /**
     * Očitovanje predano u roku zadržava noćno povlačenje; neuvažavanje nakon isteka roka odmah povlači
     * (jedan prijelaz WITHDRAWAL_DEADLINE_EXCEEDED, u ime službenika), a prigovor ostaje REJECTED.
     */
    @Test
    void withdrawalDeadline_objectionInTimeHoldsJob_rejectWithdrawsOnce() {
        String official = "INTERNAL:12345678903";
        rnService.proposeWithdrawal("HR00000021", WithdrawalReason.DATA_NOT_CORRECTED, Zagreb.today(), null,
                official, List.of());
        UUID id = objectionRepository.saveAndFlush(objection("HR00000021", RnStatus.WITHDRAWAL_PROPOSED))
                .getObjectionId();
        LocalDate yesterday = Zagreb.today().minusDays(1);
        jdbc.update("UPDATE str_rn.registration_number SET withdrawal_deadline = ? WHERE rn = ?",
                yesterday, "HR00000021");
        jdbc.update("UPDATE str_rn.suspension_objection SET submitted_at = ?, rn_log_id = (SELECT log_id FROM"
                        + " str_rn.registration_number_log WHERE rn = ? AND to_status = 'WITHDRAWAL_PROPOSED')"
                        + " WHERE objection_id = ?",
                Timestamp.from(yesterday.atTime(12, 0).atZone(ZoneId.of("Europe/Zagreb")).toInstant()),
                "HR00000021", id);

        withdrawalJob.run();
        assertThat(rnRepository.findById("HR00000021").orElseThrow().getStatus())
                .as("očitovanje predano u roku zadržava povlačenje")
                .isEqualTo(RnStatus.WITHDRAWAL_PROPOSED);

        adminService.reject("HR00000021", id, official, null);
        withdrawalJob.run();

        assertThat(rnRepository.findById("HR00000021").orElseThrow().getStatus()).isEqualTo(RnStatus.WITHDRAWN);
        assertThat(events.stream(RnLifecycleEvent.class).filter(e -> e.to() == RnStatus.WITHDRAWN))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.trigger()).isEqualTo(RnTrigger.WITHDRAWAL_DEADLINE_EXCEEDED);
                    assertThat(e.actor()).isEqualTo(official);
                });
        assertThat(objectionRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(SuspensionObjectionStatus.REJECTED);
    }

    /** Bez očitovanja u roku noćni posao povlači; očitovanje predano nakon roka ne zadržava. */
    @Test
    void withdrawalJob_withdrawsWhenObjectionIsLate() {
        rnService.proposeWithdrawal("HR00000021", WithdrawalReason.DATA_NOT_CORRECTED, Zagreb.today(), null,
                "INTERNAL:12345678903", List.of());
        UUID id = objectionRepository.saveAndFlush(objection("HR00000021", RnStatus.WITHDRAWAL_PROPOSED))
                .getObjectionId();
        jdbc.update("UPDATE str_rn.registration_number SET withdrawal_deadline = ? WHERE rn = ?",
                Zagreb.today().minusDays(2), "HR00000021");

        withdrawalJob.run();

        assertThat(rnRepository.findById("HR00000021").orElseThrow().getStatus()).isEqualTo(RnStatus.WITHDRAWN);
        assertThat(events.stream(RnLifecycleEvent.class).filter(e -> e.to() == RnStatus.WITHDRAWN))
                .singleElement()
                .satisfies(e -> assertThat(e.actor()).isEqualTo("SYSTEM"));
        assertThat(objectionRepository.findById(id).orElseThrow().getStatus())
                .as("prigovor ostaje otvoren za službenika").isEqualTo(SuspensionObjectionStatus.SUBMITTED);
    }

    /**
     * Rok je istekao, a prigovor predan u roku zadržao je job. Neuvažavanje odmah suspendira u ime
     * službenika — jedan prijelaz, dakle jedan akt o suspenziji — a prigovor ostaje REJECTED.
     * Noćni job nakon toga RB više ne dira.
     */
    @Test
    void rejectAfterDeadline_suspendsOnce_andKeepsRejection() {
        String official = "INTERNAL:12345678903";
        rnService.suspend("HR00000021", RnTrigger.INSPECTION, Zagreb.today(), null, official, List.of());
        UUID id = objectionRepository.saveAndFlush(objection("HR00000021")).getObjectionId();
        LocalDate yesterday = Zagreb.today().minusDays(1);
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
        rnService.suspend("HR00000021", RnTrigger.INSPECTION, Zagreb.today(), null, "INTERNAL:12345678903", List.of());
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

    private SuspensionObjectionStatus objectionStatusOf(String rn) {
        return rnRepository.findByLessorOib(OIB).stream().filter(r -> r.rn().equals(rn)).findFirst()
                .orElseThrow().objectionStatus();
    }

    /** Novi RB kroz {@link RnService#issue}, za smještaj uređen zadanom izmjenom (npr. previous_rn). */
    private RnEntity issueNew(java.util.function.Consumer<AccommodationEntity> izmjena) {
        SubmissionEntity submission = submissionRepository.save(
                SubmissionEntity.create("FN-NEW-" + UUID.randomUUID(), lessorId, 1L, Instant.now(), null, null));
        AccommodationEntity acc = AccommodationEntity.create(submission.getSubmissionId(),
                "Primorsko-goranska županija", "Rijeka", "Obala", "1",
                4, 8, OfferType.PRIMARY_RESIDENCE, Offering.WHOLE, true, false, true);
        izmjena.accept(acc);
        accommodationRepository.save(acc);
        return rnService.issue(submission.getSubmissionId(), acc.getAccommodationId());
    }

    /** Povlačenje istekom roka: rok je jučer, noćni posao povlači. */
    private void withdrawByDeadline(String rn) {
        jdbc.update("UPDATE str_rn.registration_number SET withdrawal_deadline = ? WHERE rn = ?",
                Zagreb.today().minusDays(1), rn);
        withdrawalJob.run();
        assertThat(rnRepository.findById(rn).orElseThrow().getStatus()).isEqualTo(RnStatus.WITHDRAWN);
    }

    private SuspensionObjectionEntity objection(String rn) {
        return objection(rn, RnStatus.SUSPENSION_PROPOSED);
    }

    private SuspensionObjectionEntity objection(String rn, RnStatus rnStatus) {
        return SuspensionObjectionEntity.createForOib(OIB, rn, rnStatus, null, "NIAS:" + OIB,
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
        rnRepository.save(RnEntity.issue(rn, submission.getSubmissionId(), acc.getAccommodationId(), Zagreb.today()));
    }
}
