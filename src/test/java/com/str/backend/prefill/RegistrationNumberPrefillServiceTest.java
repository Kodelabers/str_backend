package com.str.backend.prefill;

import com.str.backend.address.HouseNumberRepository;
import com.str.backend.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RegistrationNumberPrefillServiceTest {

    private RegistrationNumberPrefillRepository repository;
    private HouseNumberRepository houseNumberRepository;
    private RegistrationNumberPrefillService service;

    @BeforeEach
    void setUp() {
        repository = mock(RegistrationNumberPrefillRepository.class);
        houseNumberRepository = mock(HouseNumberRepository.class);
        service = new RegistrationNumberPrefillService(repository, houseNumberRepository);
    }

    @Test
    void store_persistsEntity_andReturnsGeneratedPrefillId() {
        when(repository.save(any(RegistrationNumberPrefillEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        UUID id = service.store("12345678901", "Ana", "Anić", "42", 3, 6);

        assertThat(id).isNotNull();
        verify(repository).save(any(RegistrationNumberPrefillEntity.class));
    }

    @Test
    void resolve_returnsResponseWithAddressHierarchy_whenHouseNumberPresent() {
        UUID prefillId = UUID.randomUUID();
        RegistrationNumberPrefillEntity entity = RegistrationNumberPrefillEntity.create(
                "12345678901", "Ana", "Anić", "42", 3, 6);
        when(repository.findById(prefillId)).thenReturn(Optional.of(entity));
        when(houseNumberRepository.resolveAddressHierarchy("42"))
                .thenReturn(Optional.of(addressProjection(
                        "Grad Zagreb", "Zagreb", "Zagreb", "Ilica", "1")));

        RegistrationNumberPrefillResponse response = service.resolve(prefillId);

        assertThat(response.oib()).isEqualTo("12345678901");
        assertThat(response.firstName()).isEqualTo("Ana");
        assertThat(response.lastName()).isEqualTo("Anić");
        assertThat(response.maxBedCount()).isEqualTo(3);
        assertThat(response.maxGuestCount()).isEqualTo(6);
        assertThat(response.countyName()).isEqualTo("Grad Zagreb");
        assertThat(response.municipalityName()).isEqualTo("Zagreb");
        assertThat(response.settlementName()).isEqualTo("Zagreb");
        assertThat(response.streetName()).isEqualTo("Ilica");
        assertThat(response.streetNumber()).isEqualTo("1");
    }

    @Test
    void resolve_returnsResponseWithoutAddress_whenAddressCodeIsNull() {
        UUID prefillId = UUID.randomUUID();
        RegistrationNumberPrefillEntity entity = RegistrationNumberPrefillEntity.create(
                "12345678901", "Ana", "Anić", null, null, null);
        when(repository.findById(prefillId)).thenReturn(Optional.of(entity));

        RegistrationNumberPrefillResponse response = service.resolve(prefillId);

        assertThat(response.oib()).isEqualTo("12345678901");
        assertThat(response.countyName()).isNull();
        assertThat(response.municipalityName()).isNull();
        assertThat(response.settlementName()).isNull();
        assertThat(response.streetName()).isNull();
        assertThat(response.streetNumber()).isNull();
        verifyNoInteractions(houseNumberRepository);
    }

    @Test
    void resolve_throwsResourceNotFound_whenPrefillMissing() {
        UUID prefillId = UUID.randomUUID();
        when(repository.findById(prefillId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(prefillId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Prefill payload not found");
    }

    @Test
    void resolve_throwsResourceNotFound_whenAddressHierarchyMissing() {
        UUID prefillId = UUID.randomUUID();
        RegistrationNumberPrefillEntity entity = RegistrationNumberPrefillEntity.create(
                "12345678901", "Ana", "Anić", "999", 2, 4);
        when(repository.findById(prefillId)).thenReturn(Optional.of(entity));
        when(houseNumberRepository.resolveAddressHierarchy("999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(prefillId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Adresa za zadanu šifru adrese");
    }

    private static HouseNumberRepository.FullAddressProjection addressProjection(
            String county, String municipality, String settlement, String street, String streetNumber) {
        return new HouseNumberRepository.FullAddressProjection() {
            @Override public String getCounty() { return county; }
            @Override public String getMunicipality() { return municipality; }
            @Override public String getSettlement() { return settlement; }
            @Override public String getStreet() { return street; }
            @Override public String getStreetNumber() { return streetNumber; }
        };
    }
}
