package com.str.backend.objection;

import java.util.List;
import com.str.backend.attachment.VrstaVlasnika;
import com.str.backend.attachment.TestPrilog;
import com.str.backend.attachment.PrilogDto;
import com.str.backend.attachment.PrilogService;
import com.str.backend.admin.AdminAuditService;
import com.str.backend.auth.internal.InternalAccessService;
import com.str.backend.domain.RnStatus;
import com.str.backend.exception.ConflictException;
import com.str.backend.exception.ForbiddenException;
import com.str.backend.exception.IllegalStatusTransitionException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.document.StrDocumentType;
import com.str.backend.domain.EgopSyncStatus;
import com.str.backend.egop.EgopPismenoEntity;
import com.str.backend.egop.EgopPismenoRepository;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.RegistrationNumberLogEntity;
import com.str.backend.rn.RegistrationNumberLogRepository;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RnService;
import com.str.backend.rn.RucniAkt;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SuspensionObjectionAdminServiceTest {

    private static final String RN = "HR120001000000000123";
    private static final String ACTOR = "INTERNAL:12345678903";

    private final SuspensionObjectionRepository repository = mock(SuspensionObjectionRepository.class);
    private final RnRepository rnRepository = mock(RnRepository.class);
    private final RnService rnService = mock(RnService.class);
    private final AdminAuditService auditService = mock(AdminAuditService.class);
    private final InternalAccessService internalAccess = mock(InternalAccessService.class);
    private final LessorRepository lessorRepository = mock(LessorRepository.class);
    private final PrilogService prilogService = mock(PrilogService.class);
    private final com.str.backend.rn.RnReissueGuard reissueGuard = mock(com.str.backend.rn.RnReissueGuard.class);
    private final RegistrationNumberLogRepository logRepository = mock(RegistrationNumberLogRepository.class);
    private final EgopPismenoRepository pismenoRepository = mock(EgopPismenoRepository.class);
    private final SubmissionRepository submissionRepository = mock(SubmissionRepository.class);
    private final SuspensionObjectionAdminService service = new SuspensionObjectionAdminService(
            repository, rnRepository, rnService, auditService, internalAccess, lessorRepository, prilogService,
            reissueGuard, logRepository, pismenoRepository, submissionRepository);

    private final RnEntity rn = mock(RnEntity.class);
    private SuspensionObjectionEntity objection;

    @BeforeEach
    void submittedObjectionOnProposedRn() {
        objection = SuspensionObjectionEntity.createForOib("99999999990", RN, RnStatus.SUSPENSION_PROPOSED, null,
                "NIAS:99999999990", "prigovor.pdf", "application/pdf",
                "%PDF".getBytes(StandardCharsets.US_ASCII));
        when(repository.findByObjectionIdAndRn(objection.getObjectionId(), RN)).thenReturn(Optional.of(objection));
        when(rnRepository.findForUpdate(RN)).thenReturn(Optional.of(rn));
        when(rn.getStatus()).thenReturn(RnStatus.SUSPENSION_PROPOSED);
    }

    @Test
    void accept_onProposal_revokesProposal() {
        service.accept(RN, objection.getObjectionId(), ACTOR, "  Dostavljena suglasnost  ", null);

        assertThat(objection.getStatus()).isEqualTo(SuspensionObjectionStatus.ACCEPTED);
        assertThat(objection.getResolvedBy()).isEqualTo(ACTOR);
        assertThat(objection.getResolvedAt()).isNotNull();
        assertThat(objection.getResolutionNote()).isEqualTo("Dostavljena suglasnost");
        verify(rnService).revokeProposal(RN, ACTOR, null);
        verify(rnService, never()).reactivate(anyString(), anyString());
        verify(auditService).record(ACTOR, "OBJECTION_ACCEPT", "SUSPENSION_OBJECTION",
                objection.getObjectionId().toString(), "Dostavljena suglasnost");
    }

    @Test
    void accept_onSuspension_reactivates() {
        when(rn.getStatus()).thenReturn(RnStatus.SUSPENDED);

        service.accept(RN, objection.getObjectionId(), ACTOR, null, null);

        assertThat(objection.getStatus()).isEqualTo(SuspensionObjectionStatus.ACCEPTED);
        assertThat(objection.getResolutionNote()).isNull();
        verify(rnService).reactivate(RN, ACTOR, null);
        verify(rnService, never()).revokeProposal(anyString(), anyString());
    }

    @ParameterizedTest
    @EnumSource(value = RnStatus.class, names = {"ACTIVE", "REVOKED"})
    void accept_whenRnLeftProposalAndSuspension_is409(RnStatus status) {
        when(rn.getStatus()).thenReturn(status);

        assertThatThrownBy(() -> service.accept(RN, objection.getObjectionId(), ACTOR, null, null))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("OBJECTION_RN_STATUS"));
        assertThat(objection.getStatus()).isEqualTo(SuspensionObjectionStatus.SUBMITTED);
        verifyNoInteractions(rnService, auditService);
    }

    /**
     * Neuvažavanje na prijedlogu: o suspenziji odlučuje {@link RnService#suspendIfDeadlineExpired}
     * (suspendira samo ako je rok istekao), uvijek tek nakon što je prigovor REJECTED.
     */
    @Test
    void reject_onProposal_recordsNote_andSuspendsIfDeadlineExpired() {
        when(rnService.suspendIfDeadlineExpired(RN, ACTOR)).thenAnswer(inv -> {
            assertThat(objection.getStatus()).isEqualTo(SuspensionObjectionStatus.REJECTED);
            return true;
        });

        service.reject(RN, objection.getObjectionId(), ACTOR, "Nije dostavljena suglasnost", false);

        assertThat(objection.getStatus()).isEqualTo(SuspensionObjectionStatus.REJECTED);
        assertThat(objection.getResolutionNote()).isEqualTo("Nije dostavljena suglasnost");
        verify(rnService, times(1)).suspendIfDeadlineExpired(RN, ACTOR);
        verifyNoMoreInteractions(rnService);
        verify(auditService).record(ACTOR, "OBJECTION_REJECT", "SUSPENSION_OBJECTION",
                objection.getObjectionId().toString(), "Nije dostavljena suglasnost");
    }

    // ---- T12: prigovor (očitovanje) na prijedlog povlačenja i na povučen broj

    /** Uvaženo očitovanje na prijedlog povlačenja obustavlja postupak (povratak u status prije prijedloga). */
    @Test
    void accept_onWithdrawalProposal_revokesWithdrawalProposal() {
        when(rn.getStatus()).thenReturn(RnStatus.WITHDRAWAL_PROPOSED);

        service.accept(RN, objection.getObjectionId(), ACTOR, null, null);

        assertThat(objection.getStatus()).isEqualTo(SuspensionObjectionStatus.ACCEPTED);
        verify(rnService).revokeWithdrawalProposal(RN, ACTOR, null);
        verifyNoMoreInteractions(rnService);
    }

    /** Uvaženi prigovor na povučen broj vraća ga u ACTIVE. */
    @Test
    void accept_onWithdrawn_reinstates() {
        when(rn.getStatus()).thenReturn(RnStatus.WITHDRAWN);

        service.accept(RN, objection.getObjectionId(), ACTOR, null, null);

        assertThat(objection.getStatus()).isEqualTo(SuspensionObjectionStatus.ACCEPTED);
        verify(rnService).reinstate(RN, ACTOR, null);
        verifyNoMoreInteractions(rnService);
    }

    /**
     * Za objekt je u međuvremenu izdan novi broj: 409 OBJECTION_RN_REISSUED izlazi iz transakcijske
     * metode (rollback i prigovora) i ništa se ne audita.
     */
    @Test
    void accept_onWithdrawn_reissued_is409() {
        when(rn.getStatus()).thenReturn(RnStatus.WITHDRAWN);
        when(rnService.reinstate(RN, ACTOR, null))
                .thenThrow(new ConflictException("error.objection.rnReissued", "OBJECTION_RN_REISSUED"));

        assertThatThrownBy(() -> service.accept(RN, objection.getObjectionId(), ACTOR, null, null))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("OBJECTION_RN_REISSUED"));
        verifyNoInteractions(auditService);
    }

    /** Neuvaženo očitovanje na prijedlog povlačenja: povlači odmah ako je rok istekao. */
    @Test
    void reject_onWithdrawalProposal_withdrawsIfDeadlineExpired() {
        when(rn.getStatus()).thenReturn(RnStatus.WITHDRAWAL_PROPOSED);
        when(rnService.withdrawIfDeadlineExpired(RN, ACTOR)).thenAnswer(inv -> {
            assertThat(objection.getStatus()).isEqualTo(SuspensionObjectionStatus.REJECTED);
            return true;
        });

        service.reject(RN, objection.getObjectionId(), ACTOR, null, false);

        verify(rnService).withdrawIfDeadlineExpired(RN, ACTOR);
        verifyNoMoreInteractions(rnService);
    }

    /** Neuvaženi prigovor na povučen broj ne mijenja broj. */
    @Test
    void reject_onWithdrawn_keepsRn() {
        when(rn.getStatus()).thenReturn(RnStatus.WITHDRAWN);

        service.reject(RN, objection.getObjectionId(), ACTOR, null, false);

        assertThat(objection.getStatus()).isEqualTo(SuspensionObjectionStatus.REJECTED);
        verifyNoInteractions(rnService);
    }

    /** Na suspendiranom RB-u neuvažavanje ne radi ništa s RB-om. */
    @Test
    void reject_onSuspension_keepsRn() {
        when(rn.getStatus()).thenReturn(RnStatus.SUSPENDED);

        service.reject(RN, objection.getObjectionId(), ACTOR, null, false);

        assertThat(objection.getStatus()).isEqualTo(SuspensionObjectionStatus.REJECTED);
        verifyNoInteractions(rnService);
    }

    @Test
    void resolvedObjection_cannotBeResolvedAgain() {
        when(rn.getStatus()).thenReturn(RnStatus.SUSPENDED);
        service.reject(RN, objection.getObjectionId(), ACTOR, null, false);

        assertThatThrownBy(() -> service.accept(RN, objection.getObjectionId(), ACTOR, null, null))
                .isInstanceOf(IllegalStatusTransitionException.class);
        assertThatThrownBy(() -> service.reject(RN, objection.getObjectionId(), ACTOR, null, false))
                .isInstanceOf(IllegalStatusTransitionException.class);
        verifyNoInteractions(rnService);
    }

    /** Id prigovora drugog RB-a pod ovim RB-om ne postoji — 404. */
    @Test
    void objectionOfOtherRn_is404() {
        UUID other = UUID.randomUUID();
        when(repository.findByObjectionIdAndRn(other, RN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reject(RN, other, ACTOR, null, false))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    /** Povijest nosi sve dodatne datoteke prigovora, s poveznicom za preuzimanje uz RB. */
    @Test
    void list_carriesObjectionAttachments() {
        UUID id = objection.getObjectionId();
        SuspensionObjectionRepository.ObjectionSummary row = mock(SuspensionObjectionRepository.ObjectionSummary.class);
        when(row.getObjectionId()).thenReturn(id);
        when(row.getRn()).thenReturn(RN);
        when(rnRepository.findById(RN)).thenReturn(Optional.of(rn));
        when(repository.findSummariesByRn(RN)).thenReturn(List.of(row));
        when(internalAccess.displayNames(any())).thenReturn(new java.util.HashMap<>());
        when(prilogService.zaVlasnike(VrstaVlasnika.OBJECTION, List.of(id))).thenReturn(java.util.Map.of(id, List.of(
                TestPrilog.vezan(21, VrstaVlasnika.OBJECTION, id, "foto.png", "NIAS:99999999990"),
                TestPrilog.vezan(22, VrstaVlasnika.OBJECTION, id, "dopis.pdf", "NIAS:99999999990"))));

        List<SuspensionObjectionDto> dtos = service.list(RN);

        assertThat(dtos).singleElement().satisfies(d -> {
            assertThat(d.prilozi()).extracting(PrilogDto::naziv).containsExactly("foto.png", "dopis.pdf");
            assertThat(d.prilozi().get(0).href()).isEqualTo("/api/rn/" + RN + "/attachments/21");
            assertThat(d.brojeviNaIstojAdresi()).as("RB nije povučen").isEmpty();
        });
        verify(reissueGuard, never()).brojeviNaIstojAdresi(any());
    }

    /** T12: uz povučen RB stavke nose važeće brojeve istog iznajmljivača na istoj adresi (upozorenje). */
    @Test
    void list_onWithdrawnRn_carriesNumbersAtSameAddress() {
        SuspensionObjectionRepository.ObjectionSummary row = mock(SuspensionObjectionRepository.ObjectionSummary.class);
        when(row.getObjectionId()).thenReturn(objection.getObjectionId());
        when(row.getRn()).thenReturn(RN);
        when(rn.getStatus()).thenReturn(RnStatus.WITHDRAWN);
        when(rnRepository.findById(RN)).thenReturn(Optional.of(rn));
        when(repository.findSummariesByRn(RN)).thenReturn(List.of(row));
        when(internalAccess.displayNames(any())).thenReturn(new java.util.HashMap<>());
        when(prilogService.zaVlasnike(any(), any())).thenReturn(java.util.Map.of());
        when(reissueGuard.brojeviNaIstojAdresi(RN)).thenReturn(List.of("HR120001000000000999"));

        assertThat(service.list(RN)).singleElement()
                .satisfies(d -> assertThat(d.brojeviNaIstojAdresi()).containsExactly("HR120001000000000999"));
    }

    // ---- T16: urudžbiranje podneska u županijskom postupku

    private static final RucniAkt PRIJEDLOG = new RucniAkt("UP/I-334-06/26-01/7", "2181-26-1",
            "Splitsko-dalmatinska županija", null, null, 17);
    private static final RucniAkt URUDZBA = new RucniAkt("UP/I-334-06/26-01/7", "2181-26-2",
            "Splitsko-dalmatinska županija", null, null, 17);
    private static final UUID SUBMISSION_ID = UUID.randomUUID();

    /** Prigovor na akt županijskog službenika (ručna KLASA i URBROJ na zapisu prijelaza). */
    private SuspensionObjectionEntity countyObjection(RnStatus rnStatus) {
        UUID logId = UUID.randomUUID();
        SuspensionObjectionEntity o = SuspensionObjectionEntity.createForOib("99999999990", RN, rnStatus, logId,
                "NIAS:99999999990", "prigovor.pdf", "application/pdf", "%PDF".getBytes(StandardCharsets.US_ASCII));
        when(repository.findByObjectionIdAndRn(o.getObjectionId(), RN)).thenReturn(Optional.of(o));
        when(logRepository.findRucniAkt(logId)).thenReturn(Optional.of(PRIJEDLOG));
        when(rn.getStatus()).thenReturn(rnStatus);
        when(rn.getSubmissionId()).thenReturn(SUBMISSION_ID);
        SuspensionObjectionRepository.ObjectionSummary row = summary(o.getObjectionId(), logId);
        when(repository.findSummariesByRn(RN)).thenReturn(List.of(row));
        RegistrationNumberLogEntity log = mock(RegistrationNumberLogEntity.class);
        when(log.getLogId()).thenReturn(logId);
        when(log.rucniAkt()).thenReturn(Optional.of(PRIJEDLOG));
        when(logRepository.findAllById(java.util.Set.of(logId))).thenReturn(List.of(log));
        return o;
    }

    private void registered(UUID objectionId) {
        when(pismenoRepository.existsByRnAndActRef(RN, "PRIGOVOR:" + objectionId)).thenReturn(true);
        when(pismenoRepository.findFirstByRnAndActRefAndUrBrojIsNotNull(RN, "PRIGOVOR:" + objectionId))
                .thenReturn(Optional.of(EgopPismenoEntity.rucniPodnesak(SUBMISSION_ID, RN, objectionId,
                        StrDocumentType.OCITOVANJE_SUSPENZIJE.vrstaPismenaNaziv(), URUDZBA.klasa(), URUDZBA.urBroj())));
    }

    /** Županijski postupak: ulazno pismeno s upisanim brojevima, vrsta po statusu pri predaji, bez eGOP-a. */
    @Test
    void register_county_savesManualIncomingPismeno_andAudits() {
        SuspensionObjectionEntity o = countyObjection(RnStatus.SUSPENSION_PROPOSED);
        List<EgopPismenoEntity> spremljeni = new java.util.ArrayList<>();
        when(pismenoRepository.save(any(EgopPismenoEntity.class))).thenAnswer(inv -> {
            spremljeni.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(pismenoRepository.findUrudzbiraniPodnesci(RN)).thenAnswer(inv -> List.copyOf(spremljeni));

        SuspensionObjectionDto dto = service.register(RN, o.getObjectionId(), ACTOR, URUDZBA);

        assertThat(dto.objectionId()).isEqualTo(o.getObjectionId());
        assertThat(dto.zupanijskiPostupak()).isTrue();
        assertThat(dto.urudzbiranje()).isEqualTo(new SuspensionObjectionDto.Urudzbiranje("UP/I-334-06/26-01/7",
                "2181-26-2", java.time.LocalDate.now(com.str.backend.common.Zagreb.ZONE)));
        assertThat(spremljeni).hasSize(1);
        EgopPismenoEntity p = spremljeni.get(0);
        assertThat(p.getSmjer()).isEqualTo(EgopPismenoEntity.Smjer.ULAZNO);
        assertThat(p.isRucno()).isTrue();
        assertThat(p.getStatus()).isEqualTo(EgopSyncStatus.SYNCED);
        assertThat(p.isDocumentAttached()).isTrue();
        assertThat(p.getPdfContent()).isNull();
        assertThat(p.getJop()).isNull();
        assertThat(p.getActRef()).isEqualTo("PRIGOVOR:" + o.getObjectionId());
        assertThat(p.objectionId()).contains(o.getObjectionId());
        assertThat(p.getVrstaPismenaNaziv()).isEqualTo(StrDocumentType.OCITOVANJE_SUSPENZIJE.vrstaPismenaNaziv());
        assertThat(p.getKlasa()).isEqualTo("UP/I-334-06/26-01/7");
        assertThat(p.getUrBroj()).isEqualTo("2181-26-2");
        assertThat(p.getSubmissionId()).isEqualTo(SUBMISSION_ID);
        assertThat(p.getRn()).isEqualTo(RN);
        assertThat(o.getStatus()).as("urudžbiranje nije odluka").isEqualTo(SuspensionObjectionStatus.SUBMITTED);
        verify(rnRepository).findForUpdate(RN);
        verify(auditService).record(ACTOR, "OBJECTION_REGISTER", "SUSPENSION_OBJECTION",
                o.getObjectionId().toString(), "KLASA: UP/I-334-06/26-01/7, URBROJ: 2181-26-2, pismeno: " + p.getId());
        verifyNoInteractions(rnService);
    }

    /** Prigovor na povučen broj daje „Prigovor na povlačenje…": vrsta je po statusu broja pri predaji. */
    @Test
    void register_county_kindFollowsRnStatusAtSubmission() {
        SuspensionObjectionEntity o = countyObjection(RnStatus.WITHDRAWN);
        when(pismenoRepository.save(any(EgopPismenoEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.register(RN, o.getObjectionId(), ACTOR, URUDZBA);

        ArgumentCaptor<EgopPismenoEntity> saved = ArgumentCaptor.forClass(EgopPismenoEntity.class);
        verify(pismenoRepository).save(saved.capture());
        assertThat(saved.getValue().getVrstaPismenaNaziv())
                .isEqualTo(StrDocumentType.PRIGOVOR_POVLACENJA.vrstaPismenaNaziv());
    }

    /** Postupak MINTS-a: podnesak urudžbira eGOP, ručno urudžbiranje je 409. */
    @Test
    void register_mintsProcedure_is409() {
        assertThatThrownBy(() -> service.register(RN, objection.getObjectionId(), ACTOR, URUDZBA))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("OBJECTION_NOT_COUNTY_PROCEDURE"));
        verify(pismenoRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void register_twice_is409() {
        SuspensionObjectionEntity o = countyObjection(RnStatus.SUSPENDED);
        registered(o.getObjectionId());

        assertThatThrownBy(() -> service.register(RN, o.getObjectionId(), ACTOR, URUDZBA))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("OBJECTION_ALREADY_REGISTERED"));
        verify(pismenoRepository, never()).save(any());
    }

    /** Odlučen prigovor (uvažen ili neuvažen) se ne urudžbira: 409 bez koda, kao ponovna odluka. */
    @Test
    void register_decidedObjection_is409() {
        SuspensionObjectionEntity uvazen = countyObjection(RnStatus.SUSPENDED);
        uvazen.accept(ACTOR, null);
        SuspensionObjectionEntity neuvazen = countyObjection(RnStatus.SUSPENDED);
        neuvazen.reject(ACTOR, null);

        assertThatThrownBy(() -> service.register(RN, uvazen.getObjectionId(), ACTOR, URUDZBA))
                .isInstanceOf(IllegalStatusTransitionException.class);
        assertThatThrownBy(() -> service.register(RN, neuvazen.getObjectionId(), ACTOR, URUDZBA))
                .isInstanceOf(IllegalStatusTransitionException.class);
        verify(pismenoRepository, never()).save(any());
    }

    /**
     * Zatvoren prigovor (RB je otišao dalje drugim putem) se urudžbira — svako ulazno pismeno mora biti
     * urudžbirano — i bez provjere statusa RB-a; vrsta je i dalje po statusu pri predaji.
     */
    @Test
    void register_closedObjection_isRegistered_withoutRnStatusCheck() {
        SuspensionObjectionEntity o = countyObjection(RnStatus.SUSPENSION_PROPOSED);
        o.close("SYSTEM");
        when(rn.getStatus()).thenReturn(RnStatus.ACTIVE);
        when(pismenoRepository.save(any(EgopPismenoEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.register(RN, o.getObjectionId(), ACTOR, URUDZBA);

        ArgumentCaptor<EgopPismenoEntity> saved = ArgumentCaptor.forClass(EgopPismenoEntity.class);
        verify(pismenoRepository).save(saved.capture());
        assertThat(saved.getValue().getVrstaPismenaNaziv())
                .isEqualTo(StrDocumentType.OCITOVANJE_SUSPENZIJE.vrstaPismenaNaziv());
        assertThat(o.getStatus()).isEqualTo(SuspensionObjectionStatus.CLOSED);
    }

    /** Već postoji zapis pismena za taj prigovor, i bez URBROJ-a: 409 umjesto pada na unique ključu. */
    @Test
    void register_existingRowWithoutUrBroj_is409() {
        SuspensionObjectionEntity o = countyObjection(RnStatus.SUSPENDED);
        when(pismenoRepository.existsByRnAndActRef(RN, "PRIGOVOR:" + o.getObjectionId())).thenReturn(true);
        when(pismenoRepository.findFirstByRnAndActRefAndUrBrojIsNotNull(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(RN, o.getObjectionId(), ACTOR, URUDZBA))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("OBJECTION_ALREADY_REGISTERED"));
        verify(pismenoRepository, never()).save(any());
    }

    /**
     * Županijski postupak vodi samo županijski službenik: uvažavanje i neuvažavanje drugoga je 403, i to prije
     * provjere urudžbe — RB se ne mijenja, prigovor ostaje neobrađen.
     */
    @Test
    void acceptAndReject_countyProcedure_byNonCountyOfficial_is403() {
        SuspensionObjectionEntity o = countyObjection(RnStatus.SUSPENSION_PROPOSED);

        assertThatThrownBy(() -> service.accept(RN, o.getObjectionId(), ACTOR, null, null))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        e -> assertThat(e.getCode()).isEqualTo("OBJECTION_NOT_COUNTY_OFFICIAL"));
        assertThatThrownBy(() -> service.reject(RN, o.getObjectionId(), ACTOR, null, false))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        e -> assertThat(e.getCode()).isEqualTo("OBJECTION_NOT_COUNTY_OFFICIAL"));
        assertThat(o.getStatus()).isEqualTo(SuspensionObjectionStatus.SUBMITTED);
        verifyNoInteractions(rnService, auditService);
    }

    /** I nakon urudžbe: službenik koji nije županijski ne odlučuje u županijskom postupku. */
    @Test
    void reject_countyProcedure_registered_byNonCountyOfficial_is403() {
        SuspensionObjectionEntity o = countyObjection(RnStatus.SUSPENSION_PROPOSED);
        registered(o.getObjectionId());

        assertThatThrownBy(() -> service.reject(RN, o.getObjectionId(), ACTOR, null, false))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(rnService);
    }

    /** Županijski službenik u postupku MINTS-a: odlučuje odmah, bez urudžbe (T16 ručni akt ostaje). */
    @Test
    void reject_mintsProcedure_byCountyOfficial_unchanged() {
        when(rn.getStatus()).thenReturn(RnStatus.SUSPENDED);

        service.reject(RN, objection.getObjectionId(), ACTOR, null, true);

        assertThat(objection.getStatus()).isEqualTo(SuspensionObjectionStatus.REJECTED);
    }

    @Test
    void register_rnLeftObjectionStatuses_is409() {
        SuspensionObjectionEntity o = countyObjection(RnStatus.SUSPENDED);
        when(rn.getStatus()).thenReturn(RnStatus.ACTIVE);

        assertThatThrownBy(() -> service.register(RN, o.getObjectionId(), ACTOR, URUDZBA))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("OBJECTION_RN_STATUS"));
        verify(pismenoRepository, never()).save(any());
    }

    /** Županijski postupak: bez urudžbiranja nema odluke, ni uvažavanja ni neuvažavanja. */
    @Test
    void acceptAndReject_countyWithoutRegistration_is409() {
        SuspensionObjectionEntity o = countyObjection(RnStatus.SUSPENSION_PROPOSED);

        assertThatThrownBy(() -> service.accept(RN, o.getObjectionId(), ACTOR, null, URUDZBA))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("OBJECTION_NOT_REGISTERED"));
        assertThatThrownBy(() -> service.reject(RN, o.getObjectionId(), ACTOR, null, true))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("OBJECTION_NOT_REGISTERED"));
        assertThat(o.getStatus()).isEqualTo(SuspensionObjectionStatus.SUBMITTED);
        verifyNoInteractions(rnService, auditService);
    }

    @Test
    void accept_countyAfterRegistration_runsTransitionWithManualAct() {
        SuspensionObjectionEntity o = countyObjection(RnStatus.SUSPENSION_PROPOSED);
        registered(o.getObjectionId());
        RucniAkt obustava = new RucniAkt("UP/I-334-06/26-01/7", "2181-26-3", "Splitsko-dalmatinska županija",
                null, null, 17);

        service.accept(RN, o.getObjectionId(), ACTOR, null, obustava);

        assertThat(o.getStatus()).isEqualTo(SuspensionObjectionStatus.ACCEPTED);
        verify(rnService).revokeProposal(RN, ACTOR, obustava);
    }

    /** Postupak MINTS-a (prijelaz bez ručnog akta): odlučuje se odmah, bez urudžbiranja. */
    @Test
    void accept_mintsWithoutRegistration_unchanged() {
        UUID logId = UUID.randomUUID();
        SuspensionObjectionEntity o = SuspensionObjectionEntity.createForOib("99999999990", RN,
                RnStatus.SUSPENDED, logId, "NIAS:99999999990", "prigovor.pdf", "application/pdf",
                "%PDF".getBytes(StandardCharsets.US_ASCII));
        when(repository.findByObjectionIdAndRn(o.getObjectionId(), RN)).thenReturn(Optional.of(o));
        when(logRepository.findRucniAkt(logId)).thenReturn(Optional.empty());
        when(rn.getStatus()).thenReturn(RnStatus.SUSPENDED);

        service.accept(RN, o.getObjectionId(), ACTOR, null, null);

        assertThat(o.getStatus()).isEqualTo(SuspensionObjectionStatus.ACCEPTED);
        verify(rnService).reactivate(RN, ACTOR, null);
        verify(pismenoRepository, never()).findFirstByRnAndActRefAndUrBrojIsNotNull(any(), any());
    }

    /**
     * Popis: {@code zupanijskiPostupak} samo u županijskom postupku (jedan upit za zapise prijelaza), a
     * {@code urudzbiranje} za svaki urudžbiran podnesak: ručni sa svojom KLASOM, eGOP-ov (MINTS) s KLASOM
     * predmeta akata sa submissiona (jedan upit za podneske RB-a).
     */
    @Test
    void list_fillsRegistrationFields() {
        UUID zupanijaLog = UUID.randomUUID();
        UUID mintsLog = UUID.randomUUID();
        UUID zupanijaId = UUID.randomUUID();
        UUID zupanijaNeurudzbiranId = UUID.randomUUID();
        UUID mintsId = UUID.randomUUID();
        SuspensionObjectionRepository.ObjectionSummary zupanija = summary(zupanijaId, zupanijaLog);
        SuspensionObjectionRepository.ObjectionSummary zupanijaNeurudzbiran = summary(zupanijaNeurudzbiranId, zupanijaLog);
        SuspensionObjectionRepository.ObjectionSummary mints = summary(mintsId, mintsLog);
        when(rnRepository.findById(RN)).thenReturn(Optional.of(rn));
        when(rn.getSubmissionId()).thenReturn(SUBMISSION_ID);
        when(repository.findSummariesByRn(RN)).thenReturn(List.of(zupanija, zupanijaNeurudzbiran, mints));
        when(internalAccess.displayNames(any())).thenReturn(new java.util.HashMap<>());
        RegistrationNumberLogEntity rucniLog = mock(RegistrationNumberLogEntity.class);
        when(rucniLog.getLogId()).thenReturn(zupanijaLog);
        when(rucniLog.rucniAkt()).thenReturn(Optional.of(PRIJEDLOG));
        RegistrationNumberLogEntity egopLog = mock(RegistrationNumberLogEntity.class);
        when(egopLog.getLogId()).thenReturn(mintsLog);
        when(egopLog.rucniAkt()).thenReturn(Optional.empty());
        when(logRepository.findAllById(java.util.Set.of(zupanijaLog, mintsLog))).thenReturn(List.of(rucniLog, egopLog));
        EgopPismenoEntity rucni = EgopPismenoEntity.rucniPodnesak(SUBMISSION_ID, RN, zupanijaId,
                StrDocumentType.PRIGOVOR_SUSPENZIJE.vrstaPismenaNaziv(), URUDZBA.klasa(), URUDZBA.urBroj());
        EgopPismenoEntity egop = EgopPismenoEntity.forPodnesak(SUBMISSION_ID, RN, mintsId,
                StrDocumentType.PRIGOVOR_SUSPENZIJE.vrstaPismenaNaziv());
        egop.applyPismeno(1009, "529-06/26-4", "108", false);
        when(pismenoRepository.findUrudzbiraniPodnesci(RN)).thenReturn(List.of(rucni, egop));
        SubmissionEntity submission = mock(SubmissionEntity.class);
        when(submission.getEgopAktiKlasa()).thenReturn("334-06/26-11/3");
        when(submissionRepository.findById(SUBMISSION_ID)).thenReturn(Optional.of(submission));

        List<SuspensionObjectionDto> dtos = service.list(RN);

        java.time.LocalDate danas = java.time.LocalDate.now(com.str.backend.common.Zagreb.ZONE);
        assertThat(dtos.get(0).zupanijskiPostupak()).isTrue();
        assertThat(dtos.get(0).urudzbiranje())
                .isEqualTo(new SuspensionObjectionDto.Urudzbiranje("UP/I-334-06/26-01/7", "2181-26-2", danas));
        assertThat(dtos.get(1).zupanijskiPostupak()).isTrue();
        assertThat(dtos.get(1).urudzbiranje()).isNull();
        assertThat(dtos.get(2).zupanijskiPostupak()).isFalse();
        // Zastavica ne ovisi o statusu prigovora: odlučen i zatvoren prigovor je i dalje županijski postupak.
        when(zupanija.getStatus()).thenReturn(SuspensionObjectionStatus.ACCEPTED);
        when(zupanijaNeurudzbiran.getStatus()).thenReturn(SuspensionObjectionStatus.CLOSED);
        assertThat(service.list(RN)).extracting(SuspensionObjectionDto::zupanijskiPostupak)
                .containsExactly(true, true, false);
        assertThat(dtos.get(2).urudzbiranje())
                .isEqualTo(new SuspensionObjectionDto.Urudzbiranje("334-06/26-11/3", "529-06/26-4", danas));
        verify(logRepository, never()).findById(any());
    }

    private static SuspensionObjectionRepository.ObjectionSummary summary(UUID id, UUID rnLogId) {
        SuspensionObjectionRepository.ObjectionSummary row = mock(SuspensionObjectionRepository.ObjectionSummary.class);
        when(row.getObjectionId()).thenReturn(id);
        when(row.getRn()).thenReturn(RN);
        when(row.getRnLogId()).thenReturn(rnLogId);
        return row;
    }

    @Test
    void list_unknownRn_is404() {
        when(rnRepository.findById(RN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.list(RN)).isInstanceOf(ResourceNotFoundException.class);
    }
}
