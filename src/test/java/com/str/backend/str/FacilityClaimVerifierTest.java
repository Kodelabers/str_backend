package com.str.backend.str;

import com.str.backend.exception.BusinessException;
import com.str.backend.lookup.AccommodationTypeEntity;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RnRepository.FacilityRnRow;
import com.str.backend.str.StrFacilityRepository.FacilityOwnershipRow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Brana za zahtjev koji se poziva na postojeći eTurizam objekt. Vlasništvo je tu najvažnije:
 * bez njega se tuđim {@code facilityId}-em RB write-backom upiše u tuđi zapis u eTurizmu.
 */
class FacilityClaimVerifierTest {

    private static final String OIB = "06756460531";

    private final StrFacilityRepository facilityRepository = mock(StrFacilityRepository.class);
    private final AccommodationTypeRepository typeRepository = mock(AccommodationTypeRepository.class);
    private final RnRepository rnRepository = mock(RnRepository.class);
    private final FacilityClaimVerifier verifier =
            new FacilityClaimVerifier(facilityRepository, typeRepository, rnRepository);

    @Test
    void skips_whenNoFacilityId() {
        assertThatCode(() -> verifier.verify(OIB, null, 1L, 2)).doesNotThrowAnyException();
        assertThatCode(() -> verifier.verify(OIB, "  ", 1L, 2)).doesNotThrowAnyException();
        verifyNoInteractions(facilityRepository);
    }

    @Test
    void rejects_whenFacilityIdNotNumeric() {
        assertThatThrownBy(() -> verifier.verify(OIB, "abc", 1L, 2))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.facility.unknown");
        verifyNoInteractions(facilityRepository);
    }

    @Test
    void rejects_whenFacilityMissing() {
        when(facilityRepository.findOwnership(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> verifier.verify(OIB, "153049", 1L, 2))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.facility.unknown");
    }

    @Test
    void rejects_whenFacilityBelongsToAnotherLessor() {
        stubFacility("12312312316", "FS_SOBA", 2, true);

        assertThatThrownBy(() -> verifier.verify(OIB, "153049", 1L, 2))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.facility.notOwned");
    }

    @Test
    void rejects_whenFacilityInactive() {
        stubFacility(OIB, "FS_SOBA", 2, false);

        assertThatThrownBy(() -> verifier.verify(OIB, "153049", 1L, 2))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.facility.inactive");
    }

    /**
     * Dva RB-a za isti eTurizam objekt: adresni {@code checkDuplicateLocation} to promaši jer su
     * ulica i kućni broj u eTurizmu najčešće prazni, a write-back drugog RB-a bi tiho pao na
     * {@code WHERE registration_number IS NULL}.
     */
    @Test
    void rejects_whenFacilityAlreadyHasStandingRn() {
        stubFacility(OIB, "FS_SOBA", 2, true);
        FacilityRnRow existing = mock(FacilityRnRow.class);
        when(rnRepository.findRnsByFacilityIds(List.of("153049"))).thenReturn(List.of(existing));

        assertThatThrownBy(() -> verifier.verify(OIB, "153049", 1L, 2))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.facility.alreadyRegistered");
    }

    @Test
    void rejects_whenTypeChanged() {
        stubFacility(OIB, "FS_SOBA", 2, true);
        stubSubmittedType(1L, "FS_APARTMAN");

        assertThatThrownBy(() -> verifier.verify(OIB, "153049", 1L, 2))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.facility.type.mismatch");
    }

    @Test
    void rejects_whenBedCountChanged() {
        stubFacility(OIB, "FS_SOBA", 2, true);
        stubSubmittedType(1L, "FS_SOBA");

        assertThatThrownBy(() -> verifier.verify(OIB, "153049", 1L, 5))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.facility.beds.mismatch");
    }

    @Test
    void passes_whenTypeAndBedsMatch() {
        stubFacility(OIB, "FS_SOBA", 2, true);
        stubSubmittedType(1L, "fs_soba"); // sifra se usporeduje neosjetljivo na velika/mala slova

        assertThatCode(() -> verifier.verify(OIB, " 153049 ", 1L, 2)).doesNotThrowAnyException();
    }

    /** eTurizam bez kategoriziranog kapaciteta ne smije obarati zahtjev. */
    @Test
    void passes_whenEturizamHasNoCapacity() {
        stubFacility(OIB, "FS_SOBA", null, true);
        stubSubmittedType(1L, "FS_SOBA");

        assertThatCode(() -> verifier.verify(OIB, "153049", 1L, 4)).doesNotThrowAnyException();
    }

    /** Vrsta bez FS_ šifre (npr. hotel) — usporedba se preskače, ne laže. */
    @Test
    void passes_whenSubmittedTypeHasNoCode() {
        stubFacility(OIB, "FS_SOBA", 2, true);
        AccommodationTypeEntity type = new AccommodationTypeEntity("Hotel", false, "Hoteli");
        assertThat(type.getCode()).isNull();
        when(typeRepository.findById(9L)).thenReturn(Optional.of(type));

        assertThatCode(() -> verifier.verify(OIB, "153049", 9L, 2)).doesNotThrowAnyException();
    }

    private void stubFacility(String oib, String subtypeCode, Integer beds, boolean active) {
        FacilityOwnershipRow row = mock(FacilityOwnershipRow.class);
        when(row.getOib()).thenReturn(oib);
        when(row.getSubtypeCode()).thenReturn(subtypeCode);
        when(row.getBeds()).thenReturn(beds);
        when(row.getActive()).thenReturn(active);
        when(facilityRepository.findOwnership(153049L)).thenReturn(Optional.of(row));
    }

    private void stubSubmittedType(long typeId, String code) {
        AccommodationTypeEntity type = mock(AccommodationTypeEntity.class);
        when(type.getCode()).thenReturn(code);
        when(typeRepository.findById(typeId)).thenReturn(Optional.of(type));
    }
}
