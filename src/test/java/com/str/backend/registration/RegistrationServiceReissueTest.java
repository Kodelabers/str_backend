package com.str.backend.registration;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.address.AddressHierarchyValidator;
import com.str.backend.address.CadastreResolver;
import com.str.backend.auth.nias.ActingSubject;
import com.str.backend.auth.nias.NiasIdentity;
import com.str.backend.address.CountyEntity;
import com.str.backend.address.CountyRepository;
import com.str.backend.address.HouseNumberRepository;
import com.str.backend.address.MunicipalityRepository;
import com.str.backend.address.SettlementRepository;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.draft.SubmissionDraftService;
import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ConflictException;
import com.str.backend.exception.GoneException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.lessor.SubjectProfileService;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.registration.dto.RegistrationExternalRequest;
import com.str.backend.registration.dto.RegistrationRequest;
import com.str.backend.registration.dto.RegistrationResponse;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RnService;
import com.str.backend.str.FacilityClaimVerifier;
import com.str.backend.validation.ParallelValidationOrchestrator;
import com.str.backend.validation.PipelineResult;
import com.str.backend.validation.ValidationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Predaja ponovnog zahtjeva nakon povlačenja (T15): {@code prethodniRb} se provjerava prije GO
 * pipelinea i pohrane, upisuje u {@code accommodation.previous_rn}, a rješenje prelazi na novi RB.
 */
class RegistrationServiceReissueTest {

    private static final String OIB = "12312312316";
    private static final String PREVIOUS_RN = "HR120001000000000111";
    private static final String NEW_RN = "HR120001000000000222";
    private static final String COMPANY_OIB = "33333333360";

    private ParallelValidationOrchestrator orchestrator;
    private AccommodationRepository accommodationRepository;
    private LessorRepository lessorRepository;
    private ReissueService reissueService;
    private SubjectProfileService subjectProfileService;
    private RegistrationService service;

    @BeforeEach
    void setUp() {
        orchestrator = mock(ParallelValidationOrchestrator.class);
        accommodationRepository = mock(AccommodationRepository.class);
        lessorRepository = mock(LessorRepository.class);
        reissueService = mock(ReissueService.class);
        RnService rnService = mock(RnService.class);
        RnRepository rnRepository = mock(RnRepository.class);
        subjectProfileService = mock(SubjectProfileService.class);
        CountyRepository countyRepository = mock(CountyRepository.class);

        service = new RegistrationService(
                lessorRepository, accommodationRepository, mock(SubmissionRepository.class),
                orchestrator, rnService, rnRepository, subjectProfileService,
                countyRepository, mock(MunicipalityRepository.class), mock(SettlementRepository.class),
                mock(AccommodationTypeRepository.class), mock(FacilityClaimVerifier.class),
                new CadastreResolver(mock(HouseNumberRepository.class)), mock(AddressHierarchyValidator.class),
                mock(ApplicationEventPublisher.class),
                mock(SubmissionDraftService.class), reissueService);

        lenient().when(countyRepository.findById(7L))
                .thenReturn(Optional.of(county(7L, "Splitsko-dalmatinska županija")));
        lenient().when(rnRepository.findActiveOrSuspendedRnByAddressAndOib(
                anyString(), anyString(), anyString(), anyString(), any())).thenReturn(List.of());
        LessorEntity lessor = LessorEntity.create("ANA", "ANIĆ", "Marulićeva", "5",
                "Split", "Splitsko-dalmatinska županija", null);
        lessor.setLessorOib(OIB);
        lenient().when(subjectProfileService.resolveLessor(any(), any(), any(), any())).thenReturn(lessor);
        lenient().when(orchestrator.execute(any(ValidationContext.class))).thenReturn(PipelineResult.passed());

        RnEntity issued = mock(RnEntity.class);
        lenient().when(issued.getRn()).thenReturn(NEW_RN);
        lenient().when(rnService.issue(any(UUID.class), any(UUID.class))).thenReturn(issued);
    }

    @Test
    void nias_withPreviousRn_checksOwnerByOib_storesPreviousRn_andCarriesDecision() {
        when(reissueService.carryOverDecision(eq(PREVIOUS_RN), eq(NEW_RN), any())).thenReturn(true);

        RegistrationResponse response = service.generateRegistrationNumber(request(PREVIOUS_RN, null));

        verify(reissueService).requireReusableForSubmission(PREVIOUS_RN, RnOwner.byOib(OIB));
        ArgumentCaptor<AccommodationEntity> saved = ArgumentCaptor.forClass(AccommodationEntity.class);
        verify(accommodationRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPreviousRn()).isEqualTo(PREVIOUS_RN);
        verify(reissueService).carryOverDecision(PREVIOUS_RN, NEW_RN, saved.getValue());
        assertThat(response.rjesenjePreneseno()).isTrue();
    }

    /** Objekt promijenjen: rješenje se ne prenosi, a odgovor to kaže (FE traži upload). */
    @Test
    void notCarried_isReportedInResponse() {
        when(reissueService.carryOverDecision(any(), any(), any())).thenReturn(false);

        assertThat(service.generateRegistrationNumber(request(PREVIOUS_RN, null)).rjesenjePreneseno()).isFalse();
    }

    /** Utrka dviju predaja: drugu zaustavlja UNIQUE indeks na previous_rn → isti 409 kao rana provjera. */
    @Test
    void uniqueIndexViolation_mapsToAlreadyUsed() {
        when(accommodationRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("wrapper",
                new org.hibernate.exception.ConstraintViolationException("could not execute statement",
                        new SQLException("duplicate key", "23505"), "uq_accommodation_previous_rn")));

        assertThatThrownBy(() -> service.generateRegistrationNumber(request(PREVIOUS_RN, null)))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("REISSUE_ALREADY_USED"));
        verify(reissueService, never()).carryOverDecision(any(), any(), any());
    }

    @Test
    void otherIntegrityViolation_isNotMasked() {
        DataIntegrityViolationException other = new DataIntegrityViolationException("x", new IllegalStateException());
        when(accommodationRepository.saveAndFlush(any())).thenThrow(other);

        assertThatThrownBy(() -> service.generateRegistrationNumber(request(PREVIOUS_RN, null))).isSameAs(other);
    }

    /** e-Zastupanja: vlasnik povučenog RB-a je tvrtka (OIB iz zahtjeva = OIB tvrtke), ne osoba. */
    @Test
    void legalEntity_withPreviousRn_checksOwnerByCompanyOib() {
        ActingSubject company = new ActingSubject(COMPANY_OIB, "Tvrtka d.o.o.", List.of("direktor"),
                OIB, "ANA", "ANIĆ", Instant.now());
        LessorEntity companyLessor = LessorEntity.create("ANA", "ANIĆ", "Riva", "2", "Split",
                "Splitsko-dalmatinska županija", null);
        companyLessor.setLessorOib(COMPANY_OIB);
        when(subjectProfileService.resolveLegalLessor(any(), any(), any(), any(), any(), any())).thenReturn(companyLessor);

        service.generateRegistrationNumber(RegistrationRequest.withOib(request(PREVIOUS_RN, null), COMPANY_OIB),
                new NiasIdentity(OIB, "ANA", "ANIĆ"), company);

        verify(reissueService).requireReusableForSubmission(PREVIOUS_RN, RnOwner.byOib(COMPANY_OIB));
        verify(reissueService, never()).requireReusableForSubmission(PREVIOUS_RN, RnOwner.byOib(OIB));
    }

    @Test
    void withoutPreviousRn_isOrdinaryRequest() {
        service.generateRegistrationNumber(request(null, null));

        verifyNoInteractions(reissueService);
        ArgumentCaptor<AccommodationEntity> saved = ArgumentCaptor.forClass(AccommodationEntity.class);
        verify(accommodationRepository).save(saved.capture());
        assertThat(saved.getValue().getPreviousRn()).isNull();
        verify(accommodationRepository, never()).saveAndFlush(any());
    }

    @Test
    void blankPreviousRn_isOrdinaryRequest() {
        service.generateRegistrationNumber(request("  ", null));

        verifyNoInteractions(reissueService);
    }

    /** Neispravan prethodni RB nikad ne prolazi tiho kao novi zahtjev — odbija se prije pipelinea. */
    @Test
    void invalidPreviousRn_isRejectedBeforePipeline() {
        doThrow(new ConflictException("error.reissue.alreadyUsed", "REISSUE_ALREADY_USED"))
                .when(reissueService).requireReusableForSubmission(any(), any());

        assertThatThrownBy(() -> service.generateRegistrationNumber(request(PREVIOUS_RN, null)))
                .isInstanceOf(ConflictException.class);

        verify(orchestrator, never()).execute(any());
        verify(accommodationRepository, never()).save(any());
        verify(accommodationRepository, never()).saveAndFlush(any());
        verify(reissueService, never()).carryOverDecision(any(), any(), any());
    }

    @Test
    void expiredPreviousRn_isRejectedBeforePipeline() {
        doThrow(new GoneException("error.reissue.expired", "REISSUE_EXPIRED"))
                .when(reissueService).requireReusableForSubmission(any(), any());

        assertThatThrownBy(() -> service.generateRegistrationNumber(request(PREVIOUS_RN, null)))
                .isInstanceOf(GoneException.class);
        verify(orchestrator, never()).execute(any());
    }

    @Test
    void foreignPreviousRn_is404BeforePipeline() {
        doThrow(new ResourceNotFoundException("error.rn.notFound"))
                .when(reissueService).requireReusableForSubmission(any(), any());

        assertThatThrownBy(() -> service.generateRegistrationNumber(request(PREVIOUS_RN, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(orchestrator, never()).execute(any());
    }

    /** eTurizam objekt nakon povlačenja ide s popisa objekata, ne kroz prethodniRb. */
    @Test
    void previousRnWithFacilityId_is400() {
        assertThatThrownBy(() -> service.generateRegistrationNumber(request(PREVIOUS_RN, "153049")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.reissue.withFacility");

        verifyNoInteractions(reissueService);
        verify(orchestrator, never()).execute(any());
    }

    /** Odbijen GO pipeline ne troši povučeni RB: smještaj se ne sprema, rješenje se ne prenosi. */
    @Test
    void rejectedPipeline_doesNotConsumePreviousRn() {
        when(orchestrator.execute(any(ValidationContext.class)))
                .thenReturn(PipelineResult.rejected("GO-1", "odbijeno"));

        assertThatThrownBy(() -> service.generateRegistrationNumber(request(PREVIOUS_RN, null)))
                .isInstanceOf(RuntimeException.class);

        verify(accommodationRepository, never()).save(any());
        verify(accommodationRepository, never()).saveAndFlush(any());
        verify(reissueService, never()).carryOverDecision(any(), any(), any());
    }

    @Test
    void nonEu_withPreviousRn_checksOwnerByLessorId() {
        UUID lessorId = UUID.randomUUID();
        LessorEntity lessor = LessorEntity.create("JOHN", "DOE", "Main", "1", "Graz", null, "john@example.com");
        when(lessorRepository.findById(lessorId)).thenReturn(Optional.of(lessor));

        service.generateRegistrationNumberExternal(externalRequest(PREVIOUS_RN, null), lessorId);

        verify(reissueService).requireReusableForSubmission(PREVIOUS_RN, RnOwner.byLessor(lessorId));
        ArgumentCaptor<AccommodationEntity> saved = ArgumentCaptor.forClass(AccommodationEntity.class);
        verify(accommodationRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPreviousRn()).isEqualTo(PREVIOUS_RN);
        verify(reissueService).carryOverDecision(PREVIOUS_RN, NEW_RN, saved.getValue());
    }

    @Test
    void nonEu_previousRnWithFacilityId_is400() {
        UUID lessorId = UUID.randomUUID();
        LessorEntity lessor = LessorEntity.create("JOHN", "DOE", "Main", "1", "Graz", null, "john@example.com");
        when(lessorRepository.findById(lessorId)).thenReturn(Optional.of(lessor));

        assertThatThrownBy(() -> service.generateRegistrationNumberExternal(
                externalRequest(PREVIOUS_RN, "153049"), lessorId))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.reissue.withFacility");
        verifyNoInteractions(reissueService);
    }

    private static RegistrationRequest request(String previousRn, String facilityId) {
        return new RegistrationRequest(
                OIB, "Apartman Riva", null,
                7L, "Split", "Split",
                "Riva", "1", null, "21000",
                4,
                OfferType.PRIMARY_RESIDENCE, Offering.WHOLE,
                false, "2", false, true,
                null, null, null, null, null, null, facilityId,
                "ana@example.com", "0991234567", null, null, null, null, previousRn);
    }

    private static RegistrationExternalRequest externalRequest(String previousRn, String facilityId) {
        return new RegistrationExternalRequest(
                "Apartman Riva", null, 7L, "Split", "Split", "Riva", "1", null, "21000", 4,
                OfferType.PRIMARY_RESIDENCE, Offering.WHOLE, false, "2", false, true,
                null, null, null, null, null, null, facilityId,
                null, "0991234567", null, null, null, previousRn);
    }

    private static CountyEntity county(Long id, String name) {
        try {
            var ctor = CountyEntity.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            CountyEntity c = ctor.newInstance();
            Field idField = CountyEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(c, id);
            Field nameField = CountyEntity.class.getDeclaredField("name");
            nameField.setAccessible(true);
            nameField.set(c, name);
            return c;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
