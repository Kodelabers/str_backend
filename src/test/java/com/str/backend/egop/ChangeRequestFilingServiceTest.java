package com.str.backend.egop;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.document.StrDocumentService;
import com.str.backend.document.StrDocumentType;
import com.str.backend.domain.RnStatus;
import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChangeRequestFilingServiceTest {

    private static final String RN = "HR180000123456789001";
    private static final String OIB = "12345678903";

    private final UUID submissionId = UUID.randomUUID();
    private final UUID accommodationId = UUID.randomUUID();

    private RnRepository rnRepository;
    private StrDocumentService documentService;
    private EgopFilingStore store;
    private ChangeRequestFilingService service;
    private RnEntity rn;
    private AccommodationEntity accommodation;

    @BeforeEach
    void setUp() {
        rnRepository = mock(RnRepository.class);
        AccommodationRepository accommodationRepository = mock(AccommodationRepository.class);
        documentService = mock(StrDocumentService.class);
        store = mock(EgopFilingStore.class);
        service = new ChangeRequestFilingService(rnRepository, accommodationRepository, documentService, store);

        rn = mock(RnEntity.class);
        when(rn.getSubmissionId()).thenReturn(submissionId);
        when(rn.getAccommodationId()).thenReturn(accommodationId);
        when(rn.getStatus()).thenReturn(RnStatus.ACTIVE);
        when(rnRepository.findById(RN)).thenReturn(Optional.of(rn));
        when(rnRepository.isOwnedByOib(RN, OIB)).thenReturn(true);

        accommodation = mock(AccommodationEntity.class);
        when(accommodation.getFacilityId()).thenReturn("1448000");
        when(accommodationRepository.findById(accommodationId)).thenReturn(Optional.of(accommodation));

        when(documentService.render(any(), any(), any())).thenReturn("pdf".getBytes());
        when(store.saveAkt(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /** Ulazno pismeno uz submission RB-a, sa stalnim act_ref — ponovljeni poziv ne daje drugi redak. */
    @Test
    void recordsIncomingActOnRnSubmission() {
        service.file(RN, OIB);

        ArgumentCaptor<EgopPismenoEntity> captor = ArgumentCaptor.forClass(EgopPismenoEntity.class);
        verify(store).saveAkt(captor.capture());
        EgopPismenoEntity akt = captor.getValue();
        assertThat(akt.getSubmissionId()).isEqualTo(submissionId);
        assertThat(akt.getRn()).isEqualTo(RN);
        assertThat(akt.getVrstaPismenaNaziv()).isEqualTo("Zahtjev za promjenu podataka");
        assertThat(akt.getSmjer()).isEqualTo(EgopPismenoEntity.Smjer.ULAZNO);
        assertThat(akt.getActRef()).isEqualTo(ChangeRequestFilingService.ACT_REF);
        assertThat(akt.getPdfContent()).isEqualTo("pdf".getBytes());
        verify(documentService).render(StrDocumentType.ZAHTJEV_PROMJENE_PODATAKA, RN, "");
    }

    @Test
    void unknownRn_isNotFound() {
        when(rnRepository.findById(RN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.file(RN, OIB)).isInstanceOf(ResourceNotFoundException.class);
        verify(store, never()).saveAkt(any());
    }

    /** Tuđi RB daje isti 404 kao nepostojeći — ne otkriva se da postoji. */
    @Test
    void foreignRn_isNotFound() {
        when(rnRepository.isOwnedByOib(RN, OIB)).thenReturn(false);

        assertThatThrownBy(() -> service.file(RN, OIB)).isInstanceOf(ResourceNotFoundException.class);
        verify(documentService, never()).render(any(), any(), any());
        verify(store, never()).saveAkt(any());
    }

    @Test
    void inactiveRn_isRejected() {
        when(rn.getStatus()).thenReturn(RnStatus.SUSPENDED);

        assertThatThrownBy(() -> service.file(RN, OIB))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.changeRequest.rnNotActive");
        verify(store, never()).saveAkt(any());
    }

    /** Novi objekt nema podataka iz registra — pismeno o njihovoj promjeni nema smisla. */
    @Test
    void rnForNewObject_isRejected() {
        when(accommodation.getFacilityId()).thenReturn(null);

        assertThatThrownBy(() -> service.file(RN, OIB))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.changeRequest.notEturizamFacility");
        verify(store, never()).saveAkt(any());
    }

    /** Vrsta nema eGOP šifru, pa ne smije u eGOP ni kad okolina isprazni blokadnu listu. */
    @Test
    void neverFiledToEgop_evenWithEmptyConfiguredList() {
        EgopAktiBezSifre bezSifre = new EgopAktiBezSifre(Set.of());

        assertThat(bezSifre.urudzbiv(StrDocumentType.ZAHTJEV_PROMJENE_PODATAKA)).isFalse();
        assertThat(bezSifre.vrstePismena()).contains("Zahtjev za promjenu podataka");
    }
}
