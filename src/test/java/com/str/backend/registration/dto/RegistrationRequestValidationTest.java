package com.str.backend.registration.dto;

import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Naziv, adresa i kapacitet obavezni su samo za novi objekt. Postojeći eTurizam objekt
 * ({@code facilityId}) šalje samo ono što eTurizam zna, pa zahtjev mora proći i bez toga.
 */
class RegistrationRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void newFacility_complete_isValid() {
        assertThat(violations(request(null, "Villa Ana", 1L, "10", "Ilica", "1", 4))).isEmpty();
    }

    @Test
    void newFacility_withoutStreet_isRejected() {
        assertThat(violations(request(null, "Villa Ana", 1L, "10", null, "1", 4)))
                .containsExactly("newFacilityComplete");
    }

    @Test
    void newFacility_withoutMaxBeds_isRejected() {
        assertThat(violations(request(null, "Villa Ana", 1L, "10", "Ilica", "1", null)))
                .containsExactly("newFacilityComplete");
    }

    @Test
    void existingFacility_withoutAnyFacilityData_isValid() {
        assertThat(violations(request("153049", null, null, null, null, null, null))).isEmpty();
    }

    /** Ono što stigne i dalje mora biti ispravno — izostavljanje ne gasi ostale provjere. */
    @Test
    void existingFacility_invalidMaxBeds_isRejected() {
        assertThat(violations(request("153049", null, null, null, null, null, 0)))
                .containsExactly("maxBeds");
    }

    /** Upisana adresa podnositelja provjerava se kao dio zahtjeva ({@code @Valid}). */
    @Test
    void podnositelj_invalidPostalCode_isRejected() {
        RegistrationRequest base = request("153049", null, null, null, null, null, null);
        RegistrationRequest req = new RegistrationRequest(base.oib(), null, null, null, null, null, null, null,
                null, null, null, base.offerType(), base.offering(), base.building(), base.floor(),
                base.apartments(), base.legalized(), null, null, null, null, null, null, base.facilityId(),
                base.kontaktEmail(), base.kontaktMobitel(), null, null, null,
                new PodnositeljUnos("Ilica", "1", "100", "Zagreb", null, 1L, null));

        assertThat(violations(req)).containsExactly("podnositelj.postanskiBroj");
    }

    private static Set<String> violations(RegistrationRequest req) {
        return validator.validate(req).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    private static RegistrationRequest request(String facilityId, String name, Long countyId, String cityId,
                                               String street, String streetNumber, Integer maxBeds) {
        return new RegistrationRequest(
                "12312312316", name, null, countyId, cityId, null, street, streetNumber,
                null, null, maxBeds, OfferType.PRIMARY_RESIDENCE, Offering.PART, false, "1",
                false, false, null, null, null, null, null, null, facilityId,
                "pero@example.com", "0911234567", null, null, null);
    }
}
