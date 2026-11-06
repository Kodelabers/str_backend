package com.str.backend.str.verifikacija;

import com.str.backend.str.verifikacija.EturizamVerifikacijaService.Ishod;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Serija retry joba: infrastrukturni neuspjeh je prekida, neuspjeh jednog retka zbog podataka ne. */
class EturizamVerifikacijaRetryJobTest {

    private final VerifikacijaOutbox outbox = mock(VerifikacijaOutbox.class);
    private final EturizamVerifikacijaService service = mock(EturizamVerifikacijaService.class);
    private final EturizamVerifikacijaRetryJob job = new EturizamVerifikacijaRetryJob(outbox, service);

    @Test
    void infrastructureFailure_stopsBatch() {
        when(outbox.due()).thenReturn(List.of(zapis("HR1"), zapis("HR2"), zapis("HR3")));
        when(service.obradi(any())).thenReturn(Ishod.NEUSPJEH_INFRASTRUKTURA);

        job.retryDue();

        verify(service, times(1)).obradi(any());
    }

    @Test
    void dataFailure_continuesWithNextRows() {
        when(outbox.due()).thenReturn(List.of(zapis("HR1"), zapis("HR2"), zapis("HR3")));
        when(service.obradi(any())).thenReturn(Ishod.NEUSPJEH, Ishod.GOTOVO, Ishod.GOTOVO);

        job.retryDue();

        verify(service, times(3)).obradi(any());
    }

    private static VerifikacijaOutboxEntity zapis(String rn) {
        Instant now = Instant.now();
        return VerifikacijaOutboxEntity.pending(rn, UUID.randomUUID(), "1", 1L, null, null, now, now);
    }
}
