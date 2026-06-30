package com.str.backend.registration;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.address.CountyRepository;
import com.str.backend.address.MunicipalityRepository;
import com.str.backend.address.SettlementRepository;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.registration.dto.RegistrationRequest;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RnService;
import com.str.backend.str.StrLessorLookupService;
import com.str.backend.validation.ParallelValidationOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RegistrationServiceBuildAccommodationTest {

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
                mock(AccommodationTypeRepository.class),
                mock(ApplicationEventPublisher.class));
    }

    private RegistrationRequest fullRequest(Boolean host) {
        return new RegistrationRequest(
                "12312312316", "AP1", "1",
                7L, "Split", "Meje",
                "Marulićeva", "5", null, "21000",
                4, 6,
                OfferType.PRIMARY_RESIDENCE, Offering.PART,
                true, "3", false, true,
                Boolean.TRUE, Boolean.TRUE,
                LocalDate.of(2026, 1, 15), LocalDate.of(2027, 1, 15),
                host, null);
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
}
