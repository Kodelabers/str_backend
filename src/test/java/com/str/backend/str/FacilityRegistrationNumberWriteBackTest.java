package com.str.backend.str;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FacilityRegistrationNumberWriteBackTest {

    @Mock private AccommodationRepository accommodationRepository;
    @Mock private StrFacilityRepository facilityRepository;

    private FacilityRegistrationNumberWriteBack writeBack;

    private static final UUID SUBMISSION = UUID.randomUUID();
    private static final String RN = "HR030002901133851391";

    @BeforeEach
    void setUp() {
        writeBack = new FacilityRegistrationNumberWriteBack(accommodationRepository, facilityRepository);
    }

    @Test
    void writes_rn_to_facility_when_handoff_id_present() {
        when(accommodationRepository.findBySubmissionId(SUBMISSION))
                .thenReturn(List.of(accommodation("1448035")));
        when(facilityRepository.writeBackRegistrationNumber(1448035L, RN)).thenReturn(1);

        writeBack.writeBack(SUBMISSION, RN);

        verify(facilityRepository).writeBackRegistrationNumber(1448035L, RN);
    }

    /** Registracija koja nije došla iz tuStarta nema facilityId — ne diramo tuđi registar. */
    @Test
    void skips_when_no_facility_id() {
        when(accommodationRepository.findBySubmissionId(SUBMISSION))
                .thenReturn(List.of(accommodation(null)));

        writeBack.writeBack(SUBMISSION, RN);

        verifyNoInteractions(facilityRepository);
    }

    @Test
    void skips_when_facility_id_is_not_numeric() {
        when(accommodationRepository.findBySubmissionId(SUBMISSION))
                .thenReturn(List.of(accommodation("FU-42")));

        writeBack.writeBack(SUBMISSION, RN);

        verify(facilityRepository, never()).writeBackRegistrationNumber(anyLong(), anyString());
    }

    /**
     * Nula ažuriranih redaka znači da objekt ne postoji ili već ima RB. To se logira,
     * ali ne smije srušiti tok — RB je u tom trenutku već izdan i commitan.
     */
    @Test
    void does_not_throw_when_no_row_matched() {
        when(accommodationRepository.findBySubmissionId(SUBMISSION))
                .thenReturn(List.of(accommodation("1448035")));
        when(facilityRepository.writeBackRegistrationNumber(1448035L, RN)).thenReturn(0);

        assertThatCode(() -> writeBack.writeBack(SUBMISSION, RN)).doesNotThrowAnyException();
    }

    /** Pad upisa u str.facility (npr. nema UPDATE prava) ne smije poništiti izdani RB. */
    @Test
    void swallows_database_failure() {
        when(accommodationRepository.findBySubmissionId(SUBMISSION))
                .thenReturn(List.of(accommodation("1448035")));
        when(facilityRepository.writeBackRegistrationNumber(1448035L, RN))
                .thenThrow(new IllegalStateException("permission denied for table facility"));

        assertThatCode(() -> writeBack.writeBack(SUBMISSION, RN)).doesNotThrowAnyException();
    }

    // --- fixtures ---

    private static AccommodationEntity accommodation(String facilityId) {
        AccommodationEntity entity = AccommodationEntity.create(
                null, "Krapinsko-zagorska županija", "Budinščina", "Gotalovec", "1",
                2, 2, OfferType.OTHER, Offering.WHOLE, false, false, true);
        entity.setFacilityId(facilityId);
        return entity;
    }
}
