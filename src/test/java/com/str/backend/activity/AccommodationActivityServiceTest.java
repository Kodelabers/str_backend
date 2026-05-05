package com.str.backend.activity;

import com.str.backend.activity.dto.SdepIngestRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccommodationActivityServiceTest {

    private AccommodationActivityRepository repository;
    private AccommodationActivityService service;

    @BeforeEach
    void setUp() {
        repository = mock(AccommodationActivityRepository.class);
        service = new AccommodationActivityService(repository);
    }

    @Test
    void ingest_savesOneEntityPerEntry() {
        SdepIngestRequest req = request("HR120001000000000011", "HR120001000000000022");

        int count = service.ingest(req);

        assertThat(count).isEqualTo(2);
        verify(repository, times(2)).save(any(AccommodationActivityEntity.class));
    }

    @Test
    void ingest_mapsRnAndAccommodationId() {
        UUID accommodationId = UUID.randomUUID();
        SdepIngestRequest req = new SdepIngestRequest();
        req.setPlatformId(42L);
        SdepIngestRequest.Entry entry = new SdepIngestRequest.Entry();
        entry.setRn("HR120001000000000001");
        entry.setAccommodationId(accommodationId);
        entry.setPeriodFrom(LocalDate.of(2026, 3, 1));
        entry.setPeriodTo(LocalDate.of(2026, 3, 31));
        entry.setNumberOfNights(10);
        entry.setNumberOfGuests(2);
        req.setEntries(List.of(entry));

        service.ingest(req);

        ArgumentCaptor<AccommodationActivityEntity> captor =
                ArgumentCaptor.forClass(AccommodationActivityEntity.class);
        verify(repository).save(captor.capture());
        AccommodationActivityEntity saved = captor.getValue();
        assertThat(saved.getRn()).isEqualTo("HR120001000000000001");
        assertThat(saved.getAccommodationId()).isEqualTo(accommodationId);
        assertThat(saved.getPlatformId()).isEqualTo(42L);
        assertThat(saved.getNumberOfNights()).isEqualTo(10);
        assertThat(saved.getPurgeAfter()).isNotNull();
    }

    @Test
    void search_delegatesToRepository() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 3, 31);
        when(repository.search(42L, "HR120001000000000001", from, to)).thenReturn(List.of());

        List<AccommodationActivityEntity> result = service.search(42L, "HR120001000000000001", from, to);

        assertThat(result).isEmpty();
        verify(repository).search(42L, "HR120001000000000001", from, to);
    }

    @Test
    void search_withNullParams_delegatesToRepository() {
        when(repository.search(null, null, null, null)).thenReturn(List.of());

        service.search(null, null, null, null);

        verify(repository).search(null, null, null, null);
    }

    @Test
    void purgeExpired_returnsDeletedCount() {
        when(repository.purgeExpired(any())).thenReturn(7);

        int deleted = service.purgeExpired();

        assertThat(deleted).isEqualTo(7);
    }

    @Test
    void purgeExpired_returnsZero_whenNothingToDelete() {
        when(repository.purgeExpired(any())).thenReturn(0);

        assertThat(service.purgeExpired()).isZero();
    }

    private SdepIngestRequest request(String... rns) {
        SdepIngestRequest req = new SdepIngestRequest();
        req.setPlatformId(1L);
        req.setEntries(java.util.Arrays.stream(rns).map(rn -> {
            SdepIngestRequest.Entry e = new SdepIngestRequest.Entry();
            e.setRn(rn);
            e.setPeriodFrom(LocalDate.of(2026, 1, 1));
            e.setPeriodTo(LocalDate.of(2026, 1, 31));
            return e;
        }).toList());
        return req;
    }
}
