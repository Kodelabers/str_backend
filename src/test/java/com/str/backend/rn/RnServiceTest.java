package com.str.backend.rn;
import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lookup.AccommodationTypeEntity;
import com.str.backend.lookup.AccommodationTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RnServiceTest {

    private RnRepository repository;
    private RnStatusTransitionService transitionService;
    private AccommodationRepository accommodationRepository;
    private AccommodationTypeRepository accommodationTypeRepository;
    private final Clock clock = Clock.fixed(Instant.parse("2026-04-30T10:00:00Z"), ZoneId.of("UTC"));

    private RnService service;

    @BeforeEach
    void setUp() {
        repository = mock(RnRepository.class);
        transitionService = mock(RnStatusTransitionService.class);
        accommodationRepository = mock(AccommodationRepository.class);
        accommodationTypeRepository = mock(AccommodationTypeRepository.class);
        service = new RnService(repository, transitionService, accommodationRepository,
                accommodationTypeRepository, clock);
    }

    @Test
    void issue_returnsRnEntity_whenAccommodationHasNoType() {
        UUID submissionId = UUID.randomUUID();
        UUID accommodationId = UUID.randomUUID();
        when(accommodationRepository.findById(accommodationId))
                .thenReturn(Optional.of(accommodation(accommodationId, null)));
        when(repository.existsByRn(anyString())).thenReturn(false);

        RnEntity result = service.issue(submissionId, accommodationId);

        assertThat(result.getRn()).matches("HR\\d{18}");
        assertThat(result.getStatus()).isEqualTo(RnStatus.ACTIVE);
        assertThat(result.getIssueDate()).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(result.getSubmissionId()).isEqualTo(submissionId);
        assertThat(result.getAccommodationId()).isEqualTo(accommodationId);
        verify(repository).save(result);
    }

    @Test
    void issue_returnsRnEntity_whenTypeAllowsRn() {
        UUID accommodationId = UUID.randomUUID();
        Long typeId = 1L;
        when(accommodationRepository.findById(accommodationId))
                .thenReturn(Optional.of(accommodation(accommodationId, typeId)));
        when(accommodationTypeRepository.findById(typeId))
                .thenReturn(Optional.of(new AccommodationTypeEntity("Apartman", true, "domacinstvo")));
        when(repository.existsByRn(anyString())).thenReturn(false);

        RnEntity result = service.issue(UUID.randomUUID(), accommodationId);

        assertThat(result.getRn()).matches("HR\\d{18}");
        verify(repository).save(result);
    }

    @Test
    void issue_throwsBusinessException_whenTypeDoesNotAllowRn() {
        UUID accommodationId = UUID.randomUUID();
        Long typeId = 2L;
        when(accommodationRepository.findById(accommodationId))
                .thenReturn(Optional.of(accommodation(accommodationId, typeId)));
        when(accommodationTypeRepository.findById(typeId))
                .thenReturn(Optional.of(new AccommodationTypeEntity("Hotel", false, "ostalo")));

        assertThatThrownBy(() -> service.issue(UUID.randomUUID(), accommodationId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.rn.type.not.allowed");
        verify(repository, never()).save(any());
    }

    @Test
    void issue_retriesUntilUniqueRn_thenSaves() {
        UUID accommodationId = UUID.randomUUID();
        when(accommodationRepository.findById(accommodationId))
                .thenReturn(Optional.of(accommodation(accommodationId, null)));
        // first 4 candidates collide, 5th succeeds
        when(repository.existsByRn(anyString()))
                .thenReturn(true, true, true, true, false);

        RnEntity result = service.issue(UUID.randomUUID(), accommodationId);

        assertThat(result.getRn()).matches("HR\\d{18}");
        verify(repository, times(5)).existsByRn(anyString());
        verify(repository).save(result);
    }

    @Test
    void issue_throwsBusinessException_whenAllAttemptsCollide() {
        UUID accommodationId = UUID.randomUUID();
        when(accommodationRepository.findById(accommodationId))
                .thenReturn(Optional.of(accommodation(accommodationId, null)));
        when(repository.existsByRn(anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.issue(UUID.randomUUID(), accommodationId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.rn.generation.failed");
        verify(repository, never()).save(any());
    }

    @Test
    void issue_throwsResourceNotFoundException_whenAccommodationMissing() {
        UUID accommodationId = UUID.randomUUID();
        when(accommodationRepository.findById(accommodationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issue(UUID.randomUUID(), accommodationId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void suspend_acceptsIncompleteDocumentation() {
        RnEntity rn = activeRn();
        when(repository.findById(rn.getRn())).thenReturn(Optional.of(rn));

        service.suspend(rn.getRn(), RnTrigger.INCOMPLETE_DOCUMENTATION, null, null);

        verify(transitionService).transition(rn, RnStatus.SUSPENSION_PROPOSED, RnTrigger.INCOMPLETE_DOCUMENTATION);
    }

    @Test
    void suspend_rejectsNonSuspendTrigger() {
        assertThatThrownBy(() -> service.suspend("HR120001000000000001", RnTrigger.WITHDRAWAL, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.rn.suspend.trigger.invalid");
        verify(transitionService, never())
                .transition(any(), any(), any(), anyString(), anyString());
    }

    @Test
    void withdraw_passesReasonToTransition() {
        RnEntity rn = activeRn();
        when(repository.findById(rn.getRn())).thenReturn(Optional.of(rn));

        service.withdraw(rn.getRn(), "  nedostaje suglasnost  ");

        verify(transitionService).transition(rn, RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL,
                null, "nedostaje suglasnost");
    }

    @Test
    void withdraw_nullReasonStaysNull() {
        RnEntity rn = activeRn();
        when(repository.findById(rn.getRn())).thenReturn(Optional.of(rn));

        service.withdraw(rn.getRn(), null);

        verify(transitionService).transition(rn, RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL, null, null);
    }

    private RnEntity activeRn() {
        return RnEntity.issue("HR120001000000000001", UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.of(2026, 4, 30));
    }

    private AccommodationEntity accommodation(UUID id, Long typeId) {
        AccommodationEntity e = AccommodationEntity.create(
                UUID.randomUUID(), "Grad Zagreb", "Zagreb", "Ilica", "1",
                2, 4, OfferType.PRIMARY_RESIDENCE, Offering.WHOLE, false, false, true);
        e.setLocationDetails(null, null, null, null, null, null, typeId, null);
        // Override the generated accommodationId via reflection — entity uses UUID.randomUUID() internally
        try {
            var field = AccommodationEntity.class.getDeclaredField("accommodationId");
            field.setAccessible(true);
            field.set(e, id);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return e;
    }
}
