package com.str.backend.registration;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.address.CountyRepository;
import com.str.backend.address.MunicipalityRepository;
import com.str.backend.address.SettlementRepository;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.exception.BusinessException;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.lookup.AccommodationTypeEntity;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.registration.dto.RegistrationRequest;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RnService;
import com.str.backend.str.StrLessorLookupService;
import com.str.backend.validation.ParallelValidationOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegistrationServiceBuildAccommodationTest {

    private final AccommodationTypeRepository accommodationTypeRepository =
            mock(AccommodationTypeRepository.class);

    @BeforeEach
    void setUp() {
        lenient().when(accommodationTypeRepository.existsById(1L)).thenReturn(true);
    }

    private RegistrationService newService() {
        return new RegistrationService(
                mock(LessorRepository.class),
                mock(AccommodationRepository.class),
                mock(SubmissionRepository.class),
                mock(ParallelValidationOrchestrator.class),
                mock(RnService.class),
                mock(RnRepository.class),
                mock(StrLessorLookupService.class),
                mock(CountyRepository.class),
                mock(MunicipalityRepository.class),
                mock(SettlementRepository.class),
                accommodationTypeRepository,
                mock(ApplicationEventPublisher.class));
    }

    private RegistrationRequest fullRequest(Boolean host) {
        return requestWithType("1", host);
    }

    private RegistrationRequest requestWithType(String typeId, Boolean host) {
        return new RegistrationRequest(
                "12312312316", "AP1", typeId,
                7L, "Split", "Meje",
                "Marulićeva", "5", null, "21000",
                4, 6,
                OfferType.PRIMARY_RESIDENCE, Offering.PART,
                true, "3", false, true,
                Boolean.TRUE, Boolean.TRUE,
                LocalDate.of(2026, 1, 15), LocalDate.of(2027, 1, 15),
                host, null, "1448035");
    }

    @Test
    void all_form_fields_propagate_to_entity() {
        AccommodationEntity e = newService()
                .buildAccommodation(fullRequest(Boolean.TRUE), "Splitsko-dalmatinska");

        assertThat(e.getName()).isEqualTo("AP1");
        assertThat(e.getCounty()).isEqualTo("Splitsko-dalmatinska");
        assertThat(e.getCity()).isEqualTo("Split");
        assertThat(e.getSettlement()).isEqualTo("Meje");
        assertThat(e.getStreet()).isEqualTo("Marulićeva");
        assertThat(e.getStreetNumber()).isEqualTo("5");
        assertThat(e.getMaxBeds()).isEqualTo(4);
        assertThat(e.getMaxGuests()).isEqualTo(6);
        assertThat(e.getOfferType()).isEqualTo(OfferType.PRIMARY_RESIDENCE);
        assertThat(e.getOffering()).isEqualTo(Offering.PART);
        assertThat(e.isBuilding()).isTrue();
        assertThat(e.getFloor()).isEqualTo("3");
        assertThat(e.isApartments()).isFalse();
        assertThat(e.isLegalized()).isTrue();
        assertThat(e.getLessorResidence()).isTrue();
        assertThat(e.getCoOwnerConsent()).isTrue();
        assertThat(e.getConsentDate()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(e.getConsentWithdrawalDate()).isEqualTo(LocalDate.of(2027, 1, 15));
        assertThat(e.getHost()).isTrue();
        assertThat(e.getAccommodationTypeId()).isEqualTo(1L);
        assertThat(e.getFacilityId()).isEqualTo("1448035");
    }

    @Test
    void host_false_is_recorded_explicitly() {
        AccommodationEntity e = newService()
                .buildAccommodation(fullRequest(Boolean.FALSE), "Splitsko-dalmatinska");
        assertThat(e.getHost()).isFalse();
    }

    @Test
    void host_null_leaves_entity_host_null() {
        AccommodationEntity e = newService()
                .buildAccommodation(fullRequest(null), "Splitsko-dalmatinska");
        assertThat(e.getHost()).isNull();
    }

    /**
     * tuStart u handoff URL-u šalje šifru vrste (FS_*), a ne type_id — ID se razlikuje
     * među okolinama. Backend je mora razriješiti na interni ID.
     */
    @Test
    void resolves_type_by_stable_code() {
        when(accommodationTypeRepository.findByCodeIgnoreCase("FS_KUCA_ZA_ODMOR"))
                .thenReturn(Optional.of(type(4L)));

        AccommodationEntity e = newService()
                .buildAccommodation(requestWithType("FS_KUCA_ZA_ODMOR", Boolean.TRUE), "Splitsko-dalmatinska");

        assertThat(e.getAccommodationTypeId()).isEqualTo(4L);
    }

    @Test
    void resolves_code_case_insensitively() {
        when(accommodationTypeRepository.findByCodeIgnoreCase("fs_soba"))
                .thenReturn(Optional.of(type(1L)));

        AccommodationEntity e = newService()
                .buildAccommodation(requestWithType(" fs_soba ", Boolean.TRUE), "Splitsko-dalmatinska");

        assertThat(e.getAccommodationTypeId()).isEqualTo(1L);
    }

    /**
     * Nepoznata vrsta ne smije proći tiho: bez type_id-a otpada provjera iz RnService.issue()
     * koja hotelu/kampu brani dodjelu RB-a.
     */
    @Test
    void rejects_unknown_code() {
        when(accommodationTypeRepository.findByCodeIgnoreCase("FS_NEPOSTOJECI"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> newService()
                .buildAccommodation(requestWithType("FS_NEPOSTOJECI", Boolean.TRUE), "Splitsko-dalmatinska"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.accommodation.type.unknown");
    }

    @Test
    void rejects_numeric_id_that_does_not_exist() {
        when(accommodationTypeRepository.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> newService()
                .buildAccommodation(requestWithType("999", Boolean.TRUE), "Splitsko-dalmatinska"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.accommodation.type.unknown");
    }

    /** Vrsta je opcionalna — prazan tip ostavlja polje null, bez pogađanja registra. */
    @Test
    void leaves_type_null_when_not_supplied() {
        AccommodationEntity e = newService()
                .buildAccommodation(requestWithType(null, Boolean.TRUE), "Splitsko-dalmatinska");

        assertThat(e.getAccommodationTypeId()).isNull();
    }

    // --- fixtures ---

    /** typeId puni Liquibase/JPA, pa ga u testu postavljamo refleksijom. */
    private static AccommodationTypeEntity type(long id) {
        AccommodationTypeEntity entity = new AccommodationTypeEntity("Vrsta", true, "domacinstvo");
        try {
            Field field = AccommodationTypeEntity.class.getDeclaredField("typeId");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return entity;
    }
}
