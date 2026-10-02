package com.str.backend.registration;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.address.CadastreResolver;
import com.str.backend.address.CountyEntity;
import com.str.backend.address.CountyRepository;
import com.str.backend.address.HouseNumberRepository;
import com.str.backend.address.MunicipalityRepository;
import com.str.backend.address.SettlementRepository;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.lessor.SubjectProfileService;
import com.str.backend.lookup.AccommodationTypeEntity;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.registration.dto.RegistrationRequest;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RnService;
import com.str.backend.str.FacilityClaimVerifier;
import com.str.backend.str.StrFacilityRepository.FacilityOwnershipRow;
import com.str.backend.validation.ParallelValidationOrchestrator;
import com.str.backend.validation.PipelineResult;
import com.str.backend.validation.ValidationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Postojeći eTurizam objekt: obrazac šalje {@code null} za sve što ne uspije razriješiti, a RB se
 * mora moći izdati i tada — s pravim kodom županije i vrste. Backend zato dopunjava iz eTurizma
 * ono što zahtjev nije donio, a ono što je donio ne dira.
 */
class RegistrationServiceFacilityCompletionTest {

    private static final String OIB = "12312312316";
    private static final String FACILITY_ID = "153049";

    private final AccommodationRepository accommodationRepository = mock(AccommodationRepository.class);
    private final CountyRepository countyRepository = mock(CountyRepository.class);
    private final AccommodationTypeRepository typeRepository = mock(AccommodationTypeRepository.class);
    private final FacilityClaimVerifier verifier = mock(FacilityClaimVerifier.class);
    private final FacilityOwnershipRow facility = mock(FacilityOwnershipRow.class);
    private RegistrationService service;

    @BeforeEach
    void setUp() {
        ParallelValidationOrchestrator orchestrator = mock(ParallelValidationOrchestrator.class);
        RnService rnService = mock(RnService.class);
        SubjectProfileService subjectProfileService = mock(SubjectProfileService.class);
        service = new RegistrationService(
                mock(LessorRepository.class), accommodationRepository, mock(SubmissionRepository.class),
                orchestrator, rnService, mock(RnRepository.class), subjectProfileService,
                countyRepository, mock(MunicipalityRepository.class), mock(SettlementRepository.class),
                typeRepository, verifier,
                new CadastreResolver(mock(HouseNumberRepository.class)), mock(ApplicationEventPublisher.class));

        when(orchestrator.execute(any(ValidationContext.class))).thenReturn(PipelineResult.passed());
        RnEntity issued = mock(RnEntity.class);
        when(issued.getRn()).thenReturn("HR180001000000000123");
        when(rnService.issue(any(UUID.class), any(UUID.class))).thenReturn(issued);
        LessorEntity lessor = LessorEntity.create("ANA", "ANIĆ", "Marulićeva", "5",
                "Split", "Splitsko-dalmatinska županija", null);
        lessor.setLessorOib(OIB);
        when(subjectProfileService.resolveLessor(any(), any(), any())).thenReturn(lessor);

        when(countyRepository.findAll()).thenReturn(List.of(
                county(18L, "Splitsko-dalmatinska županija"), county(22L, "Grad Zagreb")));
        lenient().when(countyRepository.findById(18L))
                .thenReturn(Optional.of(county(18L, "Splitsko-dalmatinska županija")));
        lenient().when(typeRepository.existsById(anyLong())).thenReturn(true);
        lenient().when(typeRepository.findByCodeIgnoreCase("FS_APARTMAN")).thenReturn(Optional.of(type(3L)));

        when(facility.getSubtypeCode()).thenReturn("FS_APARTMAN");
        when(facility.getBeds()).thenReturn(4);
        when(facility.getAuxiliaryBeds()).thenReturn(2);
        when(facility.getName()).thenReturn("Apartman Marija");
        when(facility.getCountyName()).thenReturn("SPLITSKO-DALMATINSKA");
        when(facility.getMunicipalityName()).thenReturn("MAKARSKA");
        when(facility.getSettlementName()).thenReturn("Makarska");
        when(facility.getStreetName()).thenReturn("Kalalarga");
        when(facility.getHouseNumber()).thenReturn("12");
        when(facility.getPostalCode()).thenReturn("21300");
        when(verifier.verify(eq(OIB), eq(FACILITY_ID), any())).thenReturn(Optional.of(facility));
    }

    @Test
    void fillsEverythingTheRequestDidNotBring() {
        service.generateRegistrationNumber(emptyFacilityRequest());

        AccommodationEntity saved = savedAccommodation();
        assertThat(saved.getName()).isEqualTo("Apartman Marija");
        assertThat(saved.getAccommodationTypeId()).isEqualTo(3L);
        assertThat(saved.getMaxBeds()).isEqualTo(6);
        assertThat(saved.getMaxGuests()).isEqualTo(6);
        // U obliku adresnog registra — po njemu RnService određuje kod županije u RB-u.
        assertThat(saved.getCounty()).isEqualTo("Splitsko-dalmatinska županija");
        assertThat(saved.getCity()).isEqualTo("MAKARSKA");
        assertThat(saved.getSettlement()).isEqualTo("Makarska");
        assertThat(saved.getStreet()).isEqualTo("Kalalarga");
        assertThat(saved.getStreetNumber()).isEqualTo("12");
        assertThat(saved.getPostalCode()).isEqualTo("21300");
    }

    /** Ono što je zahtjev donio verifier je već usporedio — dopuna to ne smije pregaziti. */
    @Test
    void keepsWhatTheRequestBrought() {
        RegistrationRequest req = request("Villa Ana", 18L, "Marulićeva", "5", 6);

        service.generateRegistrationNumber(req);

        AccommodationEntity saved = savedAccommodation();
        assertThat(saved.getName()).isEqualTo("Villa Ana");
        assertThat(saved.getCounty()).isEqualTo("Splitsko-dalmatinska županija");
        assertThat(saved.getStreet()).isEqualTo("Marulićeva");
        assertThat(saved.getStreetNumber()).isEqualTo("5");
    }

    /** Popunjivač iz eTurizma nije podatak: prazno ostaje prazno, RB se svejedno izdaje. */
    @Test
    void placeholdersStayEmpty() {
        when(facility.getStreetName()).thenReturn("-");
        when(facility.getHouseNumber()).thenReturn(null);
        when(facility.getBeds()).thenReturn(0);

        service.generateRegistrationNumber(emptyFacilityRequest());

        AccommodationEntity saved = savedAccommodation();
        assertThat(saved.getStreet()).isNull();
        assertThat(saved.getStreetNumber()).isNull();
        assertThat(saved.getMaxBeds()).isNull();
    }

    /** Županija koje nema u registru ostaje kakvu je eTurizam dao — bolje od praznog, ne blokira. */
    @Test
    void unknownCountyKeepsEturizamName() {
        when(facility.getCountyName()).thenReturn("Nepoznata županija");

        service.generateRegistrationNumber(emptyFacilityRequest());

        assertThat(savedAccommodation().getCounty()).isEqualTo("Nepoznata županija");
    }

    /** Prazan string sa zahtjeva je „nije upisano" — dopunjava se kao i null. */
    @Test
    void blankRequestValuesAreCompleted() {
        service.generateRegistrationNumber(request("   ", null, "  ", "", null));

        AccommodationEntity saved = savedAccommodation();
        assertThat(saved.getName()).isEqualTo("Apartman Marija");
        assertThat(saved.getStreet()).isEqualTo("Kalalarga");
        assertThat(saved.getStreetNumber()).isEqualTo("12");
    }

    private AccommodationEntity savedAccommodation() {
        ArgumentCaptor<AccommodationEntity> captor = ArgumentCaptor.forClass(AccommodationEntity.class);
        verify(accommodationRepository).save(captor.capture());
        return captor.getValue();
    }

    private static RegistrationRequest emptyFacilityRequest() {
        return request(null, null, null, null, null);
    }

    private static RegistrationRequest request(String name, Long countyId, String street,
                                               String streetNumber, Integer maxBeds) {
        return new RegistrationRequest(
                OIB, name, null, countyId, null, null, street, streetNumber, null, null, maxBeds,
                OfferType.PRIMARY_RESIDENCE, Offering.WHOLE, false, "1", false, false,
                null, null, null, null, null, null, FACILITY_ID,
                "ana@example.com", "0991234567", null, null, null);
    }

    private static AccommodationTypeEntity type(long id) {
        AccommodationTypeEntity entity = new AccommodationTypeEntity("Apartman", true, "domacinstvo");
        setField(AccommodationTypeEntity.class, entity, "typeId", id);
        return entity;
    }

    private static CountyEntity county(Long id, String name) {
        try {
            var ctor = CountyEntity.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            CountyEntity c = ctor.newInstance();
            setField(CountyEntity.class, c, "id", id);
            setField(CountyEntity.class, c, "name", name);
            return c;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> void setField(Class<T> type, T target, String field, Object value) {
        try {
            Field f = type.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
