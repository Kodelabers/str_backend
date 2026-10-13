package com.str.backend.registration;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.address.AddressIdResolver;
import com.str.backend.address.AddressIdResolver.AddressIds;
import com.str.backend.categorization.CategorizationDecisionEntity;
import com.str.backend.categorization.CategorizationDecisionEntity.CategorizationDecisionMetadata;
import com.str.backend.categorization.CategorizationDecisionRepository;
import com.str.backend.categorization.CategorizationDecisionRepository.DecisionSummary;
import com.str.backend.categorization.CategorizationDecisionService;
import com.str.backend.categorization.CategorizationDecisionStatus;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.domain.RnStatus;
import com.str.backend.exception.ConflictException;
import com.str.backend.exception.GoneException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.lessor.LessorRnSummaryDto;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.registration.ReissueService.Outcome;
import com.str.backend.registration.dto.ReissuePrefillResponse;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RnRepository.ReissueCandidateRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pravila ponovnog zahtjeva nakon povlačenja (T15): vlasništvo, status, novi objekt, rok od
 * {@code app.rn.retention-months} mjeseci i jednokratnost — isto za predpopunu, predaju i popis —
 * te pravilo „isti objekt" za prijenos rješenja.
 */
class ReissueServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Zagreb");
    /** Danas za sve testove. */
    private static final LocalDate TODAY = LocalDate.of(2027, 6, 15);
    private static final Instant NOW = TODAY.atStartOfDay(ZONE).toInstant();
    private static final String OIB = "12312312316";
    private static final String RN = "HR120001000000000123";
    private static final String NEW_RN = "HR120001000000000999";
    private static final UUID SUBMISSION_ID = UUID.randomUUID();
    private static final UUID ACCOMMODATION_ID = UUID.randomUUID();
    private static final UUID LESSOR_ID = UUID.randomUUID();

    private RnRepository rnRepository;
    private AccommodationRepository accommodationRepository;
    private CategorizationDecisionRepository decisionRepository;
    private LessorRepository lessorRepository;
    private ReissueService service;
    private AccommodationEntity accommodation;

    @BeforeEach
    void setUp() {
        rnRepository = mock(RnRepository.class);
        accommodationRepository = mock(AccommodationRepository.class);
        decisionRepository = mock(CategorizationDecisionRepository.class);
        AccommodationTypeRepository typeRepository = mock(AccommodationTypeRepository.class);
        SubmissionRepository submissionRepository = mock(SubmissionRepository.class);
        lessorRepository = mock(LessorRepository.class);
        AddressIdResolver addressIdResolver = mock(AddressIdResolver.class);
        Clock clock = Clock.fixed(NOW, ZONE);

        service = new ReissueService(rnRepository, accommodationRepository, decisionRepository,
                typeRepository, submissionRepository, lessorRepository, addressIdResolver, clock, 18);

        accommodation = object(a -> { });
        accommodation.setConsent(true, LocalDate.of(2025, 3, 1), null);
        accommodation.markHost(true);
        lenient().when(accommodationRepository.findById(ACCOMMODATION_ID)).thenReturn(Optional.of(accommodation));
        lenient().when(rnRepository.isOwnedByOib(RN, OIB)).thenReturn(true);
        lenient().when(rnRepository.isOwnedByLessor(RN, LESSOR_ID)).thenReturn(true);
        lenient().when(addressIdResolver.resolve(any(), any(), any(), any(), any()))
                .thenReturn(new AddressIds(7L, 70L, 700L, 7000L, 70000L));
        lenient().when(decisionRepository.findSummariesByRnAndStatusIn(anyString(), any())).thenReturn(List.of());
        lenient().when(decisionRepository.findFirstByRnAndStatusInOrderByUploadedAtDesc(anyString(), any()))
                .thenReturn(Optional.empty());

        SubmissionEntity submission = SubmissionEntity.create(null, LESSOR_ID, null, Instant.now(), null, null);
        lenient().when(submissionRepository.findById(SUBMISSION_ID)).thenReturn(Optional.of(submission));
        LessorEntity lessor = LessorEntity.create("Ana", "Anić", "Marulićeva", "5", "Split",
                "Splitsko-dalmatinska županija", null);
        lessor.applyContact("ana@example.com", "Ana", "021123456", "0991234567");
        lenient().when(lessorRepository.findById(LESSOR_ID)).thenReturn(Optional.of(lessor));
    }

    // ── pravilo prihvatljivosti (čista funkcija) ────────────────────────────

    @Test
    void eligibility_allOutcomes() {
        LocalDate w = TODAY.minusMonths(2);
        assertThat(ReissueService.eligibility(RnStatus.WITHDRAWN, w, false, false, TODAY, 18))
                .isEqualTo(new ReissueService.Eligibility(Outcome.ALLOWED, w.plusMonths(18)));
        assertThat(outcome(RnStatus.ACTIVE, w, false, false)).isEqualTo(Outcome.NOT_WITHDRAWN);
        assertThat(outcome(RnStatus.SUSPENDED, w, false, false)).isEqualTo(Outcome.NOT_WITHDRAWN);
        assertThat(outcome(RnStatus.WITHDRAWN, w, true, false)).isEqualTo(Outcome.NOT_NEW_FACILITY);
        assertThat(outcome(RnStatus.WITHDRAWN, null, false, false)).isEqualTo(Outcome.UNKNOWN_WITHDRAWAL_DATE);
        assertThat(outcome(RnStatus.WITHDRAWN, TODAY.minusMonths(18), false, false)).isEqualTo(Outcome.ALLOWED);
        assertThat(outcome(RnStatus.WITHDRAWN, TODAY.minusMonths(18).minusDays(1), false, false))
                .isEqualTo(Outcome.EXPIRED);
        assertThat(outcome(RnStatus.WITHDRAWN, w, false, true)).isEqualTo(Outcome.ALREADY_USED);
    }

    private static Outcome outcome(RnStatus status, LocalDate validTo, boolean inETurizam, boolean used) {
        return ReissueService.eligibility(status, validTo, inETurizam, used, TODAY, 18).outcome();
    }

    // ── pravilo „isti objekt" (čista funkcija) ──────────────────────────────

    @Test
    void sameObject_identical_orOnlyCaseAndWhitespace() {
        assertThat(ReissueService.isSameObject(object(a -> { }), object(a -> { }))).isTrue();
        AccommodationEntity spaced = AccommodationEntity.create(SUBMISSION_ID, "SPLITSKO-DALMATINSKA",
                " split ", "  riva ", "1 ", 4, 4, OfferType.OTHER, Offering.PART, false, false, true);
        spaced.setSettlement("SPLIT");
        spaced.setAccommodationTypeId(3L);
        assertThat(ReissueService.isSameObject(object(a -> { }), spaced)).isTrue();
    }

    @Test
    void sameObject_eachAddressChange_breaksIt() {
        AccommodationEntity previous = object(a -> { });
        assertThat(ReissueService.isSameObject(previous, withAddress("Istarska županija", "Split", "Split", "Riva", "1")))
                .as("županija").isFalse();
        assertThat(ReissueService.isSameObject(previous, withAddress("Splitsko-dalmatinska županija", "Solin", "Split", "Riva", "1")))
                .as("općina").isFalse();
        assertThat(ReissueService.isSameObject(previous, withAddress("Splitsko-dalmatinska županija", "Split", "Stobreč", "Riva", "1")))
                .as("naselje").isFalse();
        assertThat(ReissueService.isSameObject(previous, withAddress("Splitsko-dalmatinska županija", "Split", "Split", "Obala", "1")))
                .as("ulica").isFalse();
        assertThat(ReissueService.isSameObject(previous, withAddress("Splitsko-dalmatinska županija", "Split", "Split", "Riva", "2")))
                .as("kućni broj").isFalse();
    }

    @Test
    void sameObject_typeChange_breaksIt() {
        assertThat(ReissueService.isSameObject(object(a -> { }), object(a -> a.setAccommodationTypeId(4L)))).isFalse();
        assertThat(ReissueService.isSameObject(object(a -> a.setAccommodationTypeId(null)),
                object(a -> a.setAccommodationTypeId(null)))).isFalse();
    }

    @Test
    void sameObject_beds_fewerOrEqualKeep_moreBreak() {
        AccommodationEntity previous = object(a -> { });
        assertThat(ReissueService.isSameObject(previous, withBeds(3))).as("manje").isTrue();
        assertThat(ReissueService.isSameObject(previous, withBeds(4))).as("jednako").isTrue();
        assertThat(ReissueService.isSameObject(previous, withBeds(5))).as("više").isFalse();
        assertThat(ReissueService.isSameObject(previous, withBeds(null))).as("nepoznato").isFalse();
    }

    // ── prefill: rok ────────────────────────────────────────────────────────

    @Test
    void prefill_withinRetention_returnsDataGoFieldsAndDeadline() {
        withdrawn(TODAY.minusMonths(6));

        ReissuePrefillResponse r = service.prefill(RN, RnOwner.byOib(OIB));

        assertThat(r.povucenRb()).isEqualTo(RN);
        assertThat(r.povucenDana()).isEqualTo(TODAY.minusMonths(6));
        assertThat(r.ponovniZahtjevDo()).isEqualTo(TODAY.minusMonths(6).plusMonths(18));
        ReissuePrefillResponse.Objekt o = r.objekt();
        assertThat(o.naziv()).isEqualTo("Apartman Riva");
        assertThat(o.vrstaId()).isEqualTo(3L);
        assertThat(o.brKreveta()).isEqualTo(4);
        assertThat(o.kat()).isEqualTo(2);
        assertThat(o.zupanijaId()).isEqualTo(7L);
        assertThat(o.zupanijaNaziv()).isEqualTo("Splitsko-dalmatinska županija");
        assertThat(o.opcinaNaziv()).isEqualTo("Split");
        assertThat(o.ulicaNaziv()).isEqualTo("Riva");
        assertThat(o.kucniBrojId()).isEqualTo(70000L);
        assertThat(o.kucniBrojNaziv()).isEqualTo("1");
        assertThat(o.postanskiBroj()).isEqualTo("21000");
        assertThat(o.kcBroj()).isEqualTo("1234/5");
        assertThat(o.offerType()).isEqualTo(OfferType.SECONDARY_RESIDENCE);
        assertThat(o.ponuda()).isEqualTo(Offering.WHOLE);
        // GO izjave, imena kao u tijelu zahtjeva.
        assertThat(o.building()).isTrue();
        assertThat(o.apartments()).isFalse();
        assertThat(o.legalized()).isTrue();
        assertThat(o.lessorResidence()).isTrue();
        assertThat(o.coOwnerConsent()).isTrue();
        assertThat(o.consentDate()).isEqualTo(LocalDate.of(2025, 3, 1));
        assertThat(o.consentWithdrawalDate()).isNull();
        assertThat(o.host()).isTrue();
        assertThat(o.kontaktEmail()).isEqualTo("ana@example.com");
        assertThat(o.kontaktMobitel()).isEqualTo("0991234567");
        assertThat(o.kontaktTelefon()).isEqualTo("021123456");
        assertThat(o.kontaktOsoba()).isEqualTo("Ana");
        assertThat(r.rjesenje()).isNull();
    }

    /** e-Zastupanja: kontakt iz snapshota tvrtke je kontakt tadašnjeg zastupnika — ne vraća se. */
    @Test
    void prefill_legalEntityOwner_omitsSnapshotContact() {
        withdrawn(TODAY.minusMonths(6));
        LessorEntity company = mock(LessorEntity.class);
        when(company.isLegalEntityOwner()).thenReturn(true);
        lenient().when(company.getEmail()).thenReturn("zastupnik@tvrtka.hr");
        when(lessorRepository.findById(LESSOR_ID)).thenReturn(Optional.of(company));

        ReissuePrefillResponse.Objekt o = service.prefill(RN, RnOwner.byOib(OIB)).objekt();

        assertThat(o.kontaktEmail()).isNull();
        assertThat(o.kontaktMobitel()).isNull();
        assertThat(o.kontaktTelefon()).isNull();
        assertThat(o.kontaktOsoba()).isNull();
        assertThat(o.naziv()).isEqualTo("Apartman Riva");
    }

    @Test
    void prefill_exactlyOnBoundary_isAllowed() {
        withdrawn(TODAY.minusMonths(18));

        assertThat(service.prefill(RN, RnOwner.byOib(OIB)).ponovniZahtjevDo()).isEqualTo(TODAY);
    }

    @Test
    void prefill_dayAfterBoundary_isExpired410() {
        withdrawn(TODAY.minusMonths(18).minusDays(1));

        assertThatThrownBy(() -> service.prefill(RN, RnOwner.byOib(OIB)))
                .isInstanceOfSatisfying(GoneException.class,
                        e -> assertThat(e.getCode()).isEqualTo("REISSUE_EXPIRED"));
    }

    /** Legacy povlačenje bez datuma: zaseban kod, poruka ne tvrdi da je rok istekao. */
    @Test
    void prefill_withoutValidTo_isUnknownWithdrawalDate409() {
        withdrawn(null);

        assertThatThrownBy(() -> service.prefill(RN, RnOwner.byOib(OIB)))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("REISSUE_UNKNOWN_WITHDRAWAL_DATE");
                    assertThat(e.getMessage()).isEqualTo("error.reissue.unknownWithdrawalDate");
                });
    }

    // ── prefill: ostali uvjeti ──────────────────────────────────────────────

    @Test
    void prefill_foreignRn_is404WithCode_andRevealsNothing() {
        withdrawn(TODAY.minusMonths(1));
        when(rnRepository.isOwnedByOib(RN, "99999999990")).thenReturn(false);

        assertThatThrownBy(() -> service.prefill(RN, RnOwner.byOib("99999999990")))
                .isInstanceOfSatisfying(ResourceNotFoundException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("error.rn.notFound");
                    assertThat(e.getCode()).isEqualTo("REISSUE_NOT_FOUND");
                });
        verify(rnRepository, never()).findById(any());
    }

    @Test
    void prefill_unknownRn_is404WithSameCode() {
        when(rnRepository.isOwnedByOib("HR000", OIB)).thenReturn(false);

        assertThatThrownBy(() -> service.prefill("HR000", RnOwner.byOib(OIB)))
                .isInstanceOfSatisfying(ResourceNotFoundException.class,
                        e -> assertThat(e.getCode()).isEqualTo("REISSUE_NOT_FOUND"));
    }

    @Test
    void prefill_nonEuOwner_checksByLessorId() {
        withdrawn(TODAY.minusMonths(1));

        assertThat(service.prefill(RN, RnOwner.byLessor(LESSOR_ID)).povucenRb()).isEqualTo(RN);
        verify(rnRepository).isOwnedByLessor(RN, LESSOR_ID);
        verify(rnRepository, never()).isOwnedByOib(any(), any());
    }

    @Test
    void prefill_nonEuForeignRn_is404() {
        withdrawn(TODAY.minusMonths(1));

        assertThatThrownBy(() -> service.prefill(RN, RnOwner.byLessor(UUID.randomUUID())))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void prefill_notWithdrawn_is409() {
        rn(RnStatus.SUSPENDED, null);

        assertThatThrownBy(() -> service.prefill(RN, RnOwner.byOib(OIB)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("REISSUE_NOT_WITHDRAWN"));
    }

    /** eTurizam objekt ima svoj put (popis objekata, PR #120). */
    @Test
    void prefill_eturizamFacility_is409() {
        withdrawn(TODAY.minusMonths(1));
        accommodation.setFacilityId("153049");

        assertThatThrownBy(() -> service.prefill(RN, RnOwner.byOib(OIB)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("REISSUE_NOT_NEW_FACILITY"));
    }

    /** Objekt je u međuvremenu upisan u eTurizam preko rješenja — više nije „novi". */
    @Test
    void prefill_decisionWithFacilityId_is409() {
        withdrawn(TODAY.minusMonths(1));
        when(decisionRepository.findSummariesByRnAndStatusIn(RN, CategorizationDecisionService.ACTIVE_DECISION_STATUSES))
                .thenReturn(List.of(summary(CategorizationDecisionStatus.VERIFIED, "777")));

        assertThatThrownBy(() -> service.prefill(RN, RnOwner.byOib(OIB)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("REISSUE_NOT_NEW_FACILITY"));
    }

    @Test
    void prefill_blankFacilityId_countsAsNewObject() {
        withdrawn(TODAY.minusMonths(1));
        accommodation.setFacilityId("  ");

        assertThat(service.prefill(RN, RnOwner.byOib(OIB)).povucenRb()).isEqualTo(RN);
    }

    @Test
    void prefill_alreadyUsed_is409() {
        withdrawn(TODAY.minusMonths(1));
        when(accommodationRepository.existsByPreviousRn(RN)).thenReturn(true);

        assertThatThrownBy(() -> service.prefill(RN, RnOwner.byOib(OIB)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("REISSUE_ALREADY_USED"));
    }

    // ── rješenje ────────────────────────────────────────────────────────────

    /** Predpopuna čita sažetak bez BLOB-a, samo za SUBMITTED/VERIFIED. */
    @Test
    void prefill_returnsActiveDecisionSummary_withoutLoadingFile() {
        withdrawn(TODAY.minusMonths(1));
        DecisionSummary summary = summary(CategorizationDecisionStatus.VERIFIED, null);
        when(decisionRepository.findSummariesByRnAndStatusIn(RN, CategorizationDecisionService.ACTIVE_DECISION_STATUSES))
                .thenReturn(List.of(summary));

        ReissuePrefillResponse.Rjesenje r = service.prefill(RN, RnOwner.byOib(OIB)).rjesenje();

        assertThat(r.decisionId()).isEqualTo(summary.getDecisionId());
        assertThat(r.nazivDatoteke()).isEqualTo("rjesenje.pdf");
        assertThat(r.status()).isEqualTo(CategorizationDecisionStatus.VERIFIED);
        verify(decisionRepository, never()).findFirstByRnAndStatusInOrderByUploadedAtDesc(any(), any());
        assertThat(CategorizationDecisionService.ACTIVE_DECISION_STATUSES)
                .containsExactlyInAnyOrder(CategorizationDecisionStatus.SUBMITTED, CategorizationDecisionStatus.VERIFIED);
    }

    @Test
    void carryOverDecision_sameObject_copiesFileAndStatus_withSourceIdAndNewUploadTime() {
        withdrawn(TODAY.minusMonths(1));
        CategorizationDecisionEntity source = decision(RN);
        source.verify("sluzbenik");
        when(decisionRepository.findFirstByRnAndStatusInOrderByUploadedAtDesc(
                RN, CategorizationDecisionService.ACTIVE_DECISION_STATUSES)).thenReturn(Optional.of(source));
        when(decisionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.carryOverDecision(RN, NEW_RN, object(a -> { }))).isTrue();

        ArgumentCaptor<CategorizationDecisionEntity> saved = ArgumentCaptor.forClass(CategorizationDecisionEntity.class);
        verify(decisionRepository).save(saved.capture());
        CategorizationDecisionEntity copy = saved.getValue();
        assertThat(copy.getDecisionId()).isNotEqualTo(source.getDecisionId());
        assertThat(copy.getRn()).isEqualTo(NEW_RN);
        assertThat(copy.getSourceDecisionId()).isEqualTo(source.getDecisionId());
        assertThat(copy.getStatus()).isEqualTo(CategorizationDecisionStatus.VERIFIED);
        assertThat(copy.getVerifiedBy()).isEqualTo("sluzbenik");
        assertThat(copy.getVerifiedAt()).isEqualTo(source.getVerifiedAt());
        assertThat(copy.getUploadedAt()).isEqualTo(NOW);
        assertThat(copy.getFileContent()).containsExactly(source.getFileContent());
        assertThat(copy.getFileName()).isEqualTo(source.getFileName());
        assertThat(copy.getContentType()).isEqualTo(source.getContentType());
        assertThat(copy.getFileSize()).isEqualTo(source.getFileSize());
        assertThat(copy.getLessorOib()).isEqualTo(OIB);
        assertThat(copy.getObjectName()).isEqualTo("Apartman Riva");
    }

    @Test
    void carryOverDecision_changedObject_copiesNothing() {
        withdrawn(TODAY.minusMonths(1));
        lenient().when(decisionRepository.findFirstByRnAndStatusInOrderByUploadedAtDesc(any(), any()))
                .thenReturn(Optional.of(decision(RN)));

        assertThat(service.carryOverDecision(RN, NEW_RN, withBeds(6))).isFalse();

        verify(decisionRepository, never()).save(any());
    }

    @Test
    void carryOverDecision_withoutActiveDecision_savesNothing() {
        withdrawn(TODAY.minusMonths(1));

        assertThat(service.carryOverDecision(RN, NEW_RN, object(a -> { }))).isFalse();

        verify(decisionRepository, never()).save(any());
    }

    @Test
    void copyForReissue_refusesRejectedDecision() {
        CategorizationDecisionEntity rejected = decision(RN);
        rejected.reject("sluzbenik");

        assertThatThrownBy(() -> CategorizationDecisionEntity.copyForReissue(rejected, "HR1", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── predaja ─────────────────────────────────────────────────────────────

    @Test
    void requireReusableForSubmission_appliesSameRules() {
        withdrawn(TODAY.minusMonths(3));

        service.requireReusableForSubmission(RN, RnOwner.byOib(OIB));

        verify(accommodationRepository).existsByPreviousRn(RN);
    }

    @Test
    void requireReusableForSubmission_foreign_is404WithCode() {
        when(rnRepository.isOwnedByOib(RN, "99999999990")).thenReturn(false);

        assertThatThrownBy(() -> service.requireReusableForSubmission(RN, RnOwner.byOib("99999999990")))
                .isInstanceOfSatisfying(ResourceNotFoundException.class,
                        e -> assertThat(e.getCode()).isEqualTo("REISSUE_NOT_FOUND"));
        verify(rnRepository, never()).findById(any());
    }

    @Test
    void requireReusableForSubmission_alreadyUsed_is409() {
        withdrawn(TODAY.minusMonths(3));
        when(accommodationRepository.existsByPreviousRn(RN)).thenReturn(true);

        assertThatThrownBy(() -> service.requireReusableForSubmission(RN, RnOwner.byOib(OIB)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("REISSUE_ALREADY_USED"));
    }

    @Test
    void isPreviousRnConflict_onlyForThatIndex() {
        assertThat(ReissueService.isPreviousRnConflict(duplicate("uq_accommodation_previous_rn"))).isTrue();
        assertThat(ReissueService.isPreviousRnConflict(duplicate("STR_RN.UQ_ACCOMMODATION_PREVIOUS_RN"))).isTrue();
        assertThat(ReissueService.isPreviousRnConflict(duplicate("accommodation_pkey"))).isFalse();
        assertThat(ReissueService.isPreviousRnConflict(
                new DataIntegrityViolationException("x", new IllegalStateException()))).isFalse();
    }

    // ── popis ───────────────────────────────────────────────────────────────

    @Test
    void withReissueDeadlines_setsDeadlineOnlyWhereAllowed() {
        List<String> rns = List.of("HR2", "HR3", "HR4", "HR5", "HR6", "HR7", "HR8");
        when(rnRepository.findReissueCandidates(rns)).thenReturn(List.of(
                candidate("HR2", TODAY.minusMonths(2), null, false, false),
                candidate("HR3", TODAY.minusMonths(18), "", false, false),
                candidate("HR4", TODAY.minusMonths(18).minusDays(1), null, false, false),
                candidate("HR5", TODAY.minusMonths(2), "153049", false, false),
                candidate("HR6", TODAY.minusMonths(2), null, false, true),
                candidate("HR7", null, null, false, false),
                candidate("HR8", TODAY.minusMonths(2), null, true, false)));

        List<LessorRnSummaryDto> rows = new ArrayList<>(List.of(summaryRow("HR1", RnStatus.ACTIVE)));
        rns.forEach(rn -> rows.add(summaryRow(rn, RnStatus.WITHDRAWN)));
        List<LessorRnSummaryDto> result = service.withReissueDeadlines(rows);

        assertThat(result).extracting(LessorRnSummaryDto::ponovniZahtjevDo).containsExactly(
                null, TODAY.minusMonths(2).plusMonths(18), TODAY, null, null, null, null, null);
    }

    @Test
    void withReissueDeadlines_withoutWithdrawn_skipsQuery() {
        List<LessorRnSummaryDto> rows = List.of(summaryRow("HR1", RnStatus.ACTIVE));

        assertThat(service.withReissueDeadlines(rows)).isSameAs(rows);
        verify(rnRepository, never()).findReissueCandidates(any());
    }

    // ── pomoćno ─────────────────────────────────────────────────────────────

    private void withdrawn(LocalDate validTo) {
        rn(RnStatus.WITHDRAWN, validTo);
    }

    private void rn(RnStatus status, LocalDate validTo) {
        RnEntity rn = mock(RnEntity.class);
        lenient().when(rn.getRn()).thenReturn(RN);
        lenient().when(rn.getStatus()).thenReturn(status);
        lenient().when(rn.getValidTo()).thenReturn(validTo);
        lenient().when(rn.getAccommodationId()).thenReturn(ACCOMMODATION_ID);
        lenient().when(rn.getSubmissionId()).thenReturn(SUBMISSION_ID);
        when(rnRepository.findById(RN)).thenReturn(Optional.of(rn));
    }

    /** Smještaj povučenog RB-a: Riva 1, Split, vrsta 3, 4 kreveta. */
    private static AccommodationEntity object(Consumer<AccommodationEntity> change) {
        AccommodationEntity a = AccommodationEntity.create(SUBMISSION_ID, "Splitsko-dalmatinska županija", "Split",
                "Riva", "1", 4, 4, OfferType.SECONDARY_RESIDENCE, Offering.WHOLE, true, false, true);
        a.setName("Apartman Riva");
        a.setSettlement("Split");
        a.setFloor("2");
        a.setPostalCode("21000");
        a.setCadastralParcelNumber("1234/5");
        a.setAccommodationTypeId(3L);
        a.setLessorResidence(true);
        change.accept(a);
        return a;
    }

    private static AccommodationEntity withAddress(String county, String city, String settlement,
                                                   String street, String number) {
        AccommodationEntity a = AccommodationEntity.create(SUBMISSION_ID, county, city, street, number, 4, 4,
                OfferType.SECONDARY_RESIDENCE, Offering.WHOLE, true, false, true);
        a.setSettlement(settlement);
        a.setAccommodationTypeId(3L);
        return a;
    }

    private static AccommodationEntity withBeds(Integer beds) {
        AccommodationEntity a = AccommodationEntity.create(SUBMISSION_ID, "Splitsko-dalmatinska županija", "Split",
                "Riva", "1", beds, beds, OfferType.SECONDARY_RESIDENCE, Offering.WHOLE, true, false, true);
        a.setSettlement("Split");
        a.setAccommodationTypeId(3L);
        return a;
    }

    private static CategorizationDecisionEntity decision(String rn) {
        return CategorizationDecisionEntity.create(OIB, rn, "rjesenje.pdf", "application/pdf",
                new byte[]{0x25, 0x50, 0x44, 0x46},
                new CategorizationDecisionMetadata("Apartman Riva", "FS_APARTMAN", "Riva 1, 21000 Split",
                        null, null, 4, null));
    }

    private static DecisionSummary summary(CategorizationDecisionStatus status, String facilityId) {
        UUID id = UUID.randomUUID();
        return new DecisionSummary() {
            @Override public UUID getDecisionId() { return id; }
            @Override public String getFileName() { return "rjesenje.pdf"; }
            @Override public CategorizationDecisionStatus getStatus() { return status; }
            @Override public String getFacilityId() { return facilityId; }
        };
    }

    private static LessorRnSummaryDto summaryRow(String rn, RnStatus status) {
        return new LessorRnSummaryDto(rn, status, LocalDate.of(2026, 1, 1), "Objekt", "Riva", "1", "Split", "Apartman");
    }

    private static ReissueCandidateRow candidate(String rn, LocalDate validTo, String facilityId,
                                                 boolean decisionInETurizam, boolean used) {
        return new ReissueCandidateRow() {
            @Override public String getRn() { return rn; }
            @Override public LocalDate getValidTo() { return validTo; }
            @Override public String getFacilityId() { return facilityId; }
            @Override public Boolean getDecisionInETurizam() { return decisionInETurizam; }
            @Override public Boolean getUsed() { return used; }
        };
    }

    private static DataIntegrityViolationException duplicate(String constraintName) {
        return new DataIntegrityViolationException("wrapper",
                new org.hibernate.exception.ConstraintViolationException("could not execute statement",
                        new SQLException("duplicate key value violates unique constraint", "23505"), constraintName));
    }
}
