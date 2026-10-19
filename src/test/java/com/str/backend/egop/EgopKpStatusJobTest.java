package com.str.backend.egop;

import com.str.backend.egop.exception.EgopTransportException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EgopKpStatusJobTest {

    private static final String OIB = "12345678903";

    private final EgopKpOtpremaRepository repository = mock(EgopKpOtpremaRepository.class);
    private final EgopKpOtpremaStore store = mock(EgopKpOtpremaStore.class);
    private final EgopFilingStore filingStore = mock(EgopFilingStore.class);
    private final EgopClient egopClient = mock(EgopClient.class);

    private final EgopKpStatusJob job = new EgopKpStatusJob(repository, store, filingStore, egopClient,
            EgopKpOtpremaServiceTest.properties(), Duration.ofDays(30), 50);

    @Test
    void refresh_storesStatusAndTimes() throws Exception {
        EgopPismenoEntity pismeno = pismeno();
        EgopKpOtpremaEntity otprema = EgopKpOtpremaEntity.pending(pismeno.getId(), OIB, Instant.now());
        when(store.find(otprema.getId())).thenReturn(Optional.of(otprema));
        when(filingStore.findAkt(pismeno.getId())).thenReturn(Optional.of(pismeno));
        KpStatusPoruke kp = new KpStatusPoruke(2, null, null, null, null, Instant.now(), null, null);
        when(egopClient.dohvatiKpStatusPoruke("MINT\\str-svc", 1002, OIB)).thenReturn(kp);

        assertTrue(job.refresh(otprema.getId(), Instant.now()));

        verify(store).applyKpStatus(eq(otprema.getId()), eq(kp), any());
    }

    @Test
    void refresh_egopDown_reportsFailureWithoutStoring() throws Exception {
        EgopPismenoEntity pismeno = pismeno();
        EgopKpOtpremaEntity otprema = EgopKpOtpremaEntity.pending(pismeno.getId(), OIB, Instant.now());
        when(store.find(otprema.getId())).thenReturn(Optional.of(otprema));
        when(filingStore.findAkt(pismeno.getId())).thenReturn(Optional.of(pismeno));
        when(egopClient.dohvatiKpStatusPoruke("MINT\\str-svc", 1002, OIB))
                .thenThrow(new EgopTransportException("timeout", new RuntimeException()));

        assertFalse(job.refresh(otprema.getId(), Instant.now()));

        verify(store, never()).applyKpStatus(any(), any(), any());
    }

    /** Konačni statusi (potvrđen i odbijen primitak) ne smiju se tražiti ponovo. */
    @Test
    void refreshStatuses_excludesFinalStatuses() {
        when(repository.findStatusCandidates(any(), any(), any(), any())).thenReturn(List.of());

        job.refreshStatuses();

        verify(repository).findStatusCandidates(eq(EgopKpOtpremaEntity.Status.SENT),
                eq(List.of(EgopKpOtpremaEntity.KP_POTVRDEN_PRIMITAK, EgopKpOtpremaEntity.KP_ODBIJENO)),
                any(), any());
    }

    /** Odgovor bez nekog vremena ne briše ono što je ranije zapisano. */
    @Test
    void applyKpStatus_keepsEarlierTimesWhenAbsent() {
        EgopKpOtpremaEntity otprema = EgopKpOtpremaEntity.pending(UUID.randomUUID(), OIB, Instant.now());
        Instant poslano = Instant.parse("2026-10-19T08:00:00Z");
        otprema.applyKpStatus(new KpStatusPoruke(0, null, poslano, null, null, null, null, null), Instant.now());

        otprema.applyKpStatus(new KpStatusPoruke(1, null, null, null, null, null, null, null), Instant.now());

        assertEquals(1, otprema.getKpStatusPoruke());
        assertEquals(poslano, otprema.getKpPoslanoAt());
        assertNull(otprema.getKpZaprimljenoPrimateljAt());
    }

    private static EgopPismenoEntity pismeno() {
        EgopPismenoEntity pismeno = EgopPismenoEntity.create(UUID.randomUUID(),
                "Obavijest o dodjeli registracijskog broja", EgopPismenoEntity.Smjer.IZLAZNO,
                1002, "529-06/26-2", "102", false);
        pismeno.markDocumentAttached();
        return pismeno;
    }
}
