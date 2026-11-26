package com.str.backend.admin;

import com.str.backend.objection.ObjectionResolution;
import com.str.backend.objection.SuspensionObjectionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Vremenske granice upita su uvijek zadane. Null {@code Instant} Hibernate Postgresu šalje bez tipa i upit puca
 * ({@code could not determine data type of parameter}); H2 u integracijskom testu to propušta, pa se pravilo
 * čuva ovdje.
 */
class AdminObjectionServiceTest {

    private final SuspensionObjectionRepository repository = mock(SuspensionObjectionRepository.class);
    private final AdminObjectionService service = new AdminObjectionService(repository,
            Clock.fixed(Instant.parse("2026-11-24T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void stats_neverPassesNullBounds() {
        service.stats(null);

        verify(repository, times(3)).countUnresolvedForAdmin(isNull(), anyCollection(), notNull(), notNull());
    }

    @Test
    void search_withoutOlderThan_passesUpperBoundInsteadOfNull() {
        when(repository.searchForAdmin(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(Page.empty());

        service.search(new AdminObjectionService.Filter(null, null, null, null, null, null, ObjectionResolution.ALL,
                        null),
                List.of("Grad Zagreb"), PageRequest.of(0, 20));

        verify(repository).searchForAdmin(anyCollection(), eq("ON"), eq(List.of("Grad Zagreb")), isNull(),
                eq(AdminObjectionService.NO_UPPER_BOUND), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void stats_countyScope_usesScopeMode() {
        service.stats(List.of("Grad Zagreb"));

        verify(repository, times(3)).countUnresolvedForAdmin(anyString(), eq(List.of("Grad Zagreb")), notNull(),
                notNull());
    }
}
