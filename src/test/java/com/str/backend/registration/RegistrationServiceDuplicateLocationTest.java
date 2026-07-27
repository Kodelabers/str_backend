package com.str.backend.registration;

import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.address.CountyEntity;
import com.str.backend.address.CountyRepository;
import com.str.backend.address.MunicipalityRepository;
import com.str.backend.address.SettlementRepository;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.exception.DuplicateLocationException;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.registration.dto.RegistrationRequest;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RnService;
import com.str.backend.str.StrLessorLookupService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegistrationServiceDuplicateLocationTest {

    private static final String OIB = "12312312316";
    private static final String HOUSE_NUMBER_CODE = "KC-001";
    private static final String EXISTING_RN = "HR120001000000000001";
    private static final String COUNTY = "Splitsko-dalmatinska županija";
    private static final String CITY = "Split";
    private static final String STREET = "Marulićeva";
    private static final String STREET_NUMBER = "5";

    private LessorRepository lessorRepository;
    private AccommodationRepository accommodationRepository;
    private SubmissionRepository submissionRepository;
    private ParallelValidationOrchestrator orchestrator;
    private RnService rnService;
    private RnRepository rnRepository;
    private StrLessorLookupService strLessorLookupService;
    private CountyRepository countyRepository;
    private MunicipalityRepository municipalityRepository;
    private SettlementRepository settlementRepository;
    private ApplicationEventPublisher eventPublisher;

    private RegistrationService service;

    @BeforeEach
    void setUp() {
        lessorRepository = mock(LessorRepository.class);
        accommodationRepository = mock(AccommodationRepository.class);
        submissionRepository = mock(SubmissionRepository.class);
        orchestrator = mock(ParallelValidationOrchestrator.class);
        rnService = mock(RnService.class);
        rnRepository = mock(RnRepository.class);
        strLessorLookupService = mock(StrLessorLookupService.class);
        countyRepository = mock(CountyRepository.class);
        municipalityRepository = mock(MunicipalityRepository.class);
        settlementRepository = mock(SettlementRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        service = new RegistrationService(
                lessorRepository, accommodationRepository, submissionRepository,
                orchestrator, rnService, rnRepository, strLessorLookupService,
                countyRepository, municipalityRepository, settlementRepository,
                eventPublisher);

        CountyEntity county = buildCountyEntity(7L, "Splitsko-dalmatinska županija");
        when(countyRepository.findById(7L)).thenReturn(Optional.of(county));

        LessorEntity lessor = LessorEntity.create("PERO", "PERIĆ",
                "Ilica", "1", "Zagreb", "Grad Zagreb", "pero.peric@example.hr");
        lessor.setLessorOib(OIB);
        when(strLessorLookupService.resolveLessor(anyString())).thenReturn(lessor);

        when(orchestrator.execute(any(ValidationContext.class)))
                .thenReturn(PipelineResult.passed());
    }

    @Test
    void throws_duplicate_location_when_existing_rn_present_and_no_confirmation() {
        when(rnRepository.findActiveOrSuspendedRnByAddressAndOib(
                eq(COUNTY), eq(CITY), eq(STREET), eq(STREET_NUMBER), eq(OIB)))
                .thenReturn(List.of(EXISTING_RN));

        assertThatThrownBy(() -> service.generateRegistrationNumber(buildRequest(null)))
                .isInstanceOf(DuplicateLocationException.class)
                .extracting("existingRegistrationNumber").isEqualTo(EXISTING_RN);

        verify(orchestrator, never()).execute(any(ValidationContext.class));
        verify(rnService, never()).issue(any(UUID.class), any(UUID.class));
    }

    @Test
    void proceeds_when_existing_rn_present_but_confirmation_provided() {
        when(rnRepository.findActiveOrSuspendedRnByAddressAndOib(
                eq(COUNTY), eq(CITY), eq(STREET), eq(STREET_NUMBER), eq(OIB)))
                .thenReturn(List.of(EXISTING_RN));

        RnEntity issued = mock(RnEntity.class);
        when(issued.getRn()).thenReturn("HR120001000000000999");
        when(rnService.issue(any(UUID.class), any(UUID.class))).thenReturn(issued);

        var resp = service.generateRegistrationNumber(buildRequest(Boolean.TRUE));

        assertThat(resp.registrationNumber()).isEqualTo("HR120001000000000999");
        verify(orchestrator).execute(any(ValidationContext.class));
    }

    @Test
    void proceeds_when_no_existing_rn_at_location() {
        when(rnRepository.findActiveOrSuspendedRnByAddressAndOib(
                eq(COUNTY), eq(CITY), eq(STREET), eq(STREET_NUMBER), eq(OIB)))
                .thenReturn(List.of());

        RnEntity issued = mock(RnEntity.class);
        when(issued.getRn()).thenReturn("HR120001000000000777");
        when(rnService.issue(any(UUID.class), any(UUID.class))).thenReturn(issued);

        var resp = service.generateRegistrationNumber(buildRequest(null));

        assertThat(resp.registrationNumber()).isEqualTo("HR120001000000000777");
    }

    @Test
    void check_fires_even_when_house_number_code_blank_address_alone_is_enough() {
        var requestWithBlankKc = new RegistrationRequest(
                OIB, "AP1", "1",
                7L, "Split", "Meje",
                "Marulićeva", "5", "  ", "21000",
                4, 6,
                OfferType.PRIMARY_RESIDENCE, Offering.WHOLE,
                false, null, false, true,
                null, null, null, null, null, null, null);
        when(rnRepository.findActiveOrSuspendedRnByAddressAndOib(
                eq(COUNTY), eq(CITY), eq(STREET), eq(STREET_NUMBER), eq(OIB)))
                .thenReturn(List.of(EXISTING_RN));

        assertThatThrownBy(() -> service.generateRegistrationNumber(requestWithBlankKc))
                .isInstanceOf(DuplicateLocationException.class)
                .extracting("existingRegistrationNumber").isEqualTo(EXISTING_RN);
    }

    private RegistrationRequest buildRequest(Boolean confirm) {
        return new RegistrationRequest(
                OIB, "AP1", "1",
                7L, "Split", "Meje",
                "Marulićeva", "5", HOUSE_NUMBER_CODE, "21000",
                4, 6,
                OfferType.PRIMARY_RESIDENCE, Offering.WHOLE,
                false, null, false, true,
                null, null, null, null, null, confirm, null);
    }

    private CountyEntity buildCountyEntity(Long id, String name) {
        try {
            var ctor = CountyEntity.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            CountyEntity c = ctor.newInstance();
            setField(c, "id", id);
            setField(c, "name", name);
            setField(c, "zuRb", id.intValue());
            return c;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
