package com.str.backend.kp;

import com.str.backend.kp.client.KpAktivnost;
import com.str.backend.kp.client.KpClient;
import com.str.backend.kp.client.KpTransportException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KpStatusJobTest {

    private static final KpPrimatelj PS = new KpPrimatelj(KpPrimatelj.Tip.PS, "98765432106", "001");

    private KpOtpremaRepository repository;
    private KpOtpremaStore store;
    private KpClient client;
    private KpStatusJob job;

    @BeforeEach
    void setUp() {
        repository = mock(KpOtpremaRepository.class);
        store = mock(KpOtpremaStore.class);
        client = mock(KpClient.class);
        job = new KpStatusJob(repository, store, client, Duration.ofDays(30), 50);
    }

    @Test
    void refresh_recordsActivityAndReceipt() {
        KpOtpremaEntity otprema = sent();
        byte[] potvrda = "%PDF-potvrda".getBytes();
        KpAktivnost aktivnost = new KpAktivnost(List.of(), "", 2, Instant.now(), Instant.now(), null, potvrda);
        when(client.aktivnost(PS, otprema.getIdPoruke().toString())).thenReturn(aktivnost);
        Instant now = Instant.now();

        assertThat(job.refresh(otprema.getId(), now)).isTrue();

        verify(store).applyAktivnost(otprema.getId(), aktivnost, now);
    }

    /** Poruka još nije vidljiva ili nije PSPU — vrijeme provjere se pomiče, status se ne dira. */
    @Test
    void refresh_rejected_onlyMarksChecked() {
        KpOtpremaEntity otprema = sent();
        when(client.aktivnost(any(), any())).thenReturn(
                new KpAktivnost(List.of("C003"), "C003 nije pronađena", null, null, null, null, null));
        Instant now = Instant.now();

        assertThat(job.refresh(otprema.getId(), now)).isFalse();

        verify(store).markChecked(otprema.getId(), now);
        verify(store, never()).applyAktivnost(any(), any(), any());
    }

    @Test
    void refresh_transportError_isNotFatal() {
        KpOtpremaEntity otprema = sent();
        when(client.aktivnost(any(), any())).thenThrow(new KpTransportException("timeout"));

        assertThat(job.refresh(otprema.getId(), Instant.now())).isFalse();
    }

    @Test
    void refreshStatuses_asksOnlyForNonFinalLegalEffectMessages() {
        when(repository.findStatusCandidates(eq(KpOtpremaEntity.Status.SENT), eq(List.of(2, 3)), any(), any()))
                .thenReturn(List.of());

        job.refreshStatuses();

        verify(client, never()).aktivnost(any(), any());
    }

    private KpOtpremaEntity sent() {
        KpOtpremaEntity otprema = KpOtpremaEntity.pending(UUID.randomUUID(), PS, Instant.now());
        otprema.markSent(Instant.now(), 9101, true, null);
        when(store.find(otprema.getId())).thenReturn(Optional.of(otprema));
        return otprema;
    }
}
