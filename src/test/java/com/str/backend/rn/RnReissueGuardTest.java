package com.str.backend.rn;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.domain.RnStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T12: je li za objekt povučenog broja izdan drugi važeći broj — po eTurizam objektu, po lancu
 * ponovnih zahtjeva i po adresi (provjera duplikata lokacije bez OIB-a).
 */
class RnReissueGuardTest {

    private static final String RN = "HR120001000000000001";

    private RnRepository rnRepository;
    private AccommodationRepository accommodationRepository;
    private RnReissueGuard guard;
    private RnEntity withdrawn;
    private AccommodationEntity accommodation;

    @BeforeEach
    void setUp() {
        rnRepository = mock(RnRepository.class);
        accommodationRepository = mock(AccommodationRepository.class);
        guard = new RnReissueGuard(rnRepository);

        accommodation = AccommodationEntity.create(UUID.randomUUID(), "Splitsko-dalmatinska županija", "Split",
                "Riva", "8", 2, 4, OfferType.PRIMARY_RESIDENCE, Offering.WHOLE, false, false, true);
        withdrawn = RnEntity.issue(RN, UUID.randomUUID(), accommodation.getAccommodationId(), LocalDate.of(2026, 1, 1));
        withdrawn.applyStatus(RnStatus.WITHDRAWN);
        when(rnRepository.findById(RN)).thenReturn(Optional.of(withdrawn));
        when(accommodationRepository.findById(accommodation.getAccommodationId())).thenReturn(Optional.of(accommodation));
        when(rnRepository.findFacilityIdByRn(RN)).thenReturn(Optional.empty());
        when(rnRepository.findReissuesOf(anyString())).thenReturn(List.of());
        when(rnRepository.findStandingRnsOfSameLessorAtSameAddress(anyString())).thenReturn(List.of());
    }

    @Test
    void noOtherNumber_isFalse() {
        assertThat(guard.noviBrojIzdan(RN)).isFalse();
        assertThat(guard.noviBrojIzdan("HR999999999999999999")).as("nepoznat broj").isFalse();
    }

    /** Isti eTurizam objekt ima drugi nekonačan broj; vlastiti broj se ne broji. */
    @Test
    void sameFacility_otherStandingNumber_isTrue() {
        when(rnRepository.findFacilityIdByRn(RN)).thenReturn(Optional.of(" 42 "));
        when(rnRepository.findRnsByFacilityIds(List.of("42"))).thenReturn(List.of(row(RN)));
        assertThat(guard.noviBrojIzdan(RN)).isFalse();

        when(rnRepository.findRnsByFacilityIds(List.of("42"))).thenReturn(List.of(row(RN), row("HR120001000000000009")));
        assertThat(guard.noviBrojIzdan(RN)).isTrue();
    }

    /** Lanac ponovnih zahtjeva: važeći broj i dva koraka dalje, preko opozvane karike. */
    @Test
    void reissueChain_standingNumberFurtherDown_isTrue() {
        RnEntity revoked = rn("HR120001000000000002");
        revoked.applyStatus(RnStatus.REVOKED);
        RnEntity active = rn("HR120001000000000003");
        when(rnRepository.findReissuesOf(RN)).thenReturn(List.of(revoked));
        when(rnRepository.findReissuesOf(revoked.getRn())).thenReturn(List.of(active));

        assertThat(guard.noviBrojIzdan(RN)).isTrue();
    }

    /**
     * Ista adresa istog iznajmljivača samo upozorava (T12): ne blokira prigovor, nego se službeniku navodi u
     * popisu prigovora (upit sam isključuje vlastiti broj i druge vlasnike, v.
     * {@code SuspensionObjectionIntegrationTest#sameAddressSameLessor_onlyWarnsInObjectionList}).
     */
    @Test
    void sameAddressSameLessor_onlyWarns_doesNotBlock() {
        when(rnRepository.findStandingRnsOfSameLessorAtSameAddress(RN)).thenReturn(List.of("HR120001000000000007"));

        assertThat(guard.noviBrojIzdan(RN)).isFalse();
        assertThat(guard.brojeviNaIstojAdresi(RN)).containsExactly("HR120001000000000007");
    }

    private static RnEntity rn(String rn) {
        return RnEntity.issue(rn, UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 4, 1));
    }

    private static RnRepository.FacilityRnRow row(String rn) {
        return new RnRepository.FacilityRnRow() {
            @Override public String getFacilityId() { return "42"; }
            @Override public String getRn() { return rn; }
        };
    }
}
