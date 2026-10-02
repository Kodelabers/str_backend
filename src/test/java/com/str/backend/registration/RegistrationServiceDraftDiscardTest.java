package com.str.backend.registration;

import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.address.CadastreResolver;
import com.str.backend.address.CountyEntity;
import com.str.backend.address.CountyRepository;
import com.str.backend.address.HouseNumberRepository;
import com.str.backend.address.MunicipalityRepository;
import com.str.backend.address.SettlementRepository;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.draft.SubmissionDraftService;
import com.str.backend.exception.ValidationRejectedException;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.lessor.SubjectProfileService;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.registration.dto.RegistrationRequest;
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
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Izdavanje RB-a za postojeći eTurizam objekt briše sve nacrte tog objekta; zahtjev za novi
 * objekt i odbijen zahtjev nacrte ne diraju.
 */
class RegistrationServiceDraftDiscardTest {

    private static final String OIB = "12312312316";
    private static final String FACILITY_ID = "4711";

    private ParallelValidationOrchestrator orchestrator;
    private SubmissionDraftService draftService;
    private RegistrationService service;

    @BeforeEach
    void setUp() {
        orchestrator = mock(ParallelValidationOrchestrator.class);
        RnService rnService = mock(RnService.class);
        RnRepository rnRepository = mock(RnRepository.class);
        SubjectProfileService subjectProfileService = mock(SubjectProfileService.class);
        CountyRepository countyRepository = mock(CountyRepository.class);
        AccommodationTypeRepository accommodationTypeRepository = mock(AccommodationTypeRepository.class);
        draftService = mock(SubmissionDraftService.class);

        lenient().when(accommodationTypeRepository.existsById(anyLong())).thenReturn(true);

        service = new RegistrationService(
                mock(LessorRepository.class), mock(AccommodationRepository.class), mock(SubmissionRepository.class),
                orchestrator, rnService, rnRepository, subjectProfileService,
                countyRepository, mock(MunicipalityRepository.class), mock(SettlementRepository.class),
                accommodationTypeRepository, mock(FacilityClaimVerifier.class),
                new CadastreResolver(mock(HouseNumberRepository.class)), mock(ApplicationEventPublisher.class),
                draftService);

        when(countyRepository.findById(7L)).thenReturn(Optional.of(buildCounty(7L, "Splitsko-dalmatinska županija")));
        lenient().when(rnRepository.findActiveOrSuspendedRnByAddressAndOib(
                anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of());
        LessorEntity lessor = LessorEntity.create("ANA", "ANIĆ", "Marulićeva", "5",
                "Split", "Splitsko-dalmatinska županija", null);
        lessor.setLessorOib(OIB);
        when(subjectProfileService.resolveLessor(any(), any(), any())).thenReturn(lessor);

        RnEntity issued = mock(RnEntity.class);
        lenient().when(issued.getRn()).thenReturn("HR120001000000000123");
        lenient().when(rnService.issue(any(UUID.class), any(UUID.class))).thenReturn(issued);
    }

    @Test
    void issuedRn_forExistingFacility_discardsItsDrafts() {
        when(orchestrator.execute(any(ValidationContext.class))).thenReturn(PipelineResult.passed());

        service.generateRegistrationNumber(request(FACILITY_ID));

        verify(draftService).discardForFacility(FACILITY_ID);
    }

    /** Novi objekt nema objekt u nacrtu — njegov nacrt briše FE po {@code draftId}. */
    @Test
    void issuedRn_forNewObject_touchesNoDrafts() {
        when(orchestrator.execute(any(ValidationContext.class))).thenReturn(PipelineResult.passed());

        service.generateRegistrationNumber(request(null));

        verify(draftService, never()).discardForFacility(any());
    }

    /** Odbijen zahtjev: korisnik nastavlja iz nacrta, pa nacrt mora ostati. */
    @Test
    void rejectedRequest_keepsDrafts() {
        when(orchestrator.execute(any(ValidationContext.class)))
                .thenReturn(PipelineResult.rejected("GO-1", "odbijeno"));

        assertThatThrownBy(() -> service.generateRegistrationNumber(request(FACILITY_ID)))
                .isInstanceOf(ValidationRejectedException.class);

        verify(draftService, never()).discardForFacility(any());
    }

    private RegistrationRequest request(String facilityId) {
        return new RegistrationRequest(
                OIB, "AP1", null,
                7L, "Split", "Meje",
                "Marulićeva", "5", null, "21000",
                4,
                OfferType.PRIMARY_RESIDENCE, Offering.WHOLE,
                false, "2", false, true,
                null, null, null, null, null, null, facilityId,
                "ana@example.com", "0991234567", null, null, null);
    }

    private CountyEntity buildCounty(Long id, String name) {
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
