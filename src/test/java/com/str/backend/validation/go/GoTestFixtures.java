package com.str.backend.validation.go;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.core.CoreObjektEntity;
import com.str.backend.domain.OfferType;
import com.str.backend.lessor.LessorEntity;

import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

final class GoTestFixtures {

    private GoTestFixtures() {}

    static LessorEntity lessor(String county, String place) {
        return LessorEntity.create(
                "Marko", "Maric", "Ilica", "1", place, county, "marko@example.com");
    }

    static AccommodationEntity accommodation(String county, String city, int maxBeds, int maxGuests,
                                             boolean building, boolean apartments, boolean legalized) {
        return AccommodationEntity.create(
                UUID.randomUUID(), county, city, "Ulica", "1",
                maxBeds, maxGuests, OfferType.FULL,
                building, apartments, legalized);
    }

    static AccommodationEntity accommodationWithConsent(Boolean consent, LocalDate consentDate,
                                                        LocalDate withdrawalDate) {
        AccommodationEntity acc = accommodation("Zagreb", "Zagreb", 2, 4, true, true, true);
        acc.setConsent(consent, consentDate, withdrawalDate);
        return acc;
    }

    static CoreObjektEntity coreObject(int maxBeds, int maxGuests, boolean legalan) {
        CoreObjektEntity core = mock(CoreObjektEntity.class);
        lenient().when(core.getUuid()).thenReturn(UUID.randomUUID());
        lenient().when(core.getMaxKreveta()).thenReturn(maxBeds);
        lenient().when(core.getMaxGostiju()).thenReturn(maxGuests);
        lenient().when(core.isLegalan()).thenReturn(legalan);
        return core;
    }

    // --- Legacy aliases used by existing tests (kept for backward compatibility) ---

    static LessorEntity iznajmljivac(String county, String place) {
        return lessor(county, place);
    }

    static AccommodationEntity sso(String county, String city, int maxBeds, int maxGuests,
                                   boolean building, boolean apartments, boolean legalized) {
        return accommodation(county, city, maxBeds, maxGuests, building, apartments, legalized);
    }

    static AccommodationEntity ssoWithSuglasnost(Boolean consent, LocalDate consentDate,
                                                  LocalDate withdrawalDate) {
        return accommodationWithConsent(consent, consentDate, withdrawalDate);
    }

    static CoreObjektEntity coreObjekt(int maxBeds, int maxGuests, boolean legalan) {
        return coreObject(maxBeds, maxGuests, legalan);
    }
}
