package com.str.backend.email;

import com.str.backend.egop.EgopRegistrationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailRetryJobTest {

    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");

    private MailRetryStore store;
    private EgopRegistrationDispatcher dispatcher;
    private RnLifecycleEmailListener lifecycleListener;
    private MailRetryJob job;

    @BeforeEach
    void setUp() {
        store = mock(MailRetryStore.class);
        dispatcher = mock(EgopRegistrationDispatcher.class);
        lifecycleListener = mock(RnLifecycleEmailListener.class);
        job = new MailRetryJob(store, dispatcher, lifecycleListener,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(3), 50);
    }

    @Test
    void routesEachKindToItsSendingPath() {
        UUID submission = UUID.randomUUID();
        UUID logId = UUID.randomUUID();
        when(store.due(anyInt())).thenReturn(List.of(
                zapis(MailRetryEntity.Kind.RN_ISSUED, submission, NOW.minus(Duration.ofHours(1))),
                zapis(MailRetryEntity.Kind.RN_LIFECYCLE, logId, NOW.minus(Duration.ofHours(1)))));

        job.retryDue();

        verify(dispatcher).retryEmail(submission);
        verify(lifecycleListener).retry(logId);
    }

    /** Obavijest stara više dana se ne šalje — stanje je iznajmljivaču odavno vidljivo drugdje. */
    @Test
    void expiredRecord_isAbandoned_notSent() {
        UUID logId = UUID.randomUUID();
        when(store.due(anyInt())).thenReturn(List.of(
                zapis(MailRetryEntity.Kind.RN_LIFECYCLE, logId, NOW.minus(Duration.ofDays(4)))));

        job.retryDue();

        verify(store).abandon(MailRetryEntity.Kind.RN_LIFECYCLE, logId, "expired");
        verify(lifecycleListener, never()).retry(any());
    }

    /**
     * Pad prije nego što put slanja zabilježi ishod mora se računati kao pokušaj — inače bi zapis
     * ostao dospio i job bi ga vrtio svakih 5 minuta zauvijek. Ostali zapisi se i dalje obrađuju.
     */
    @Test
    void unexpectedError_countsAsFailedAttempt_andContinues() {
        UUID broken = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        when(store.due(anyInt())).thenReturn(List.of(
                zapis(MailRetryEntity.Kind.RN_ISSUED, broken, NOW.minus(Duration.ofHours(1))),
                zapis(MailRetryEntity.Kind.RN_ISSUED, next, NOW.minus(Duration.ofHours(1)))));
        doThrow(new IllegalStateException("predložak")).when(dispatcher).retryEmail(broken);

        job.retryDue();

        verify(store).recordFailure(eq(MailRetryEntity.Kind.RN_ISSUED), eq(broken), startsWith("error:"));
        verify(dispatcher).retryEmail(next);
    }

    private static MailRetryEntity zapis(MailRetryEntity.Kind kind, UUID ref, Instant createdAt) {
        return MailRetryEntity.firstFailure(kind, ref, createdAt, createdAt.plus(Duration.ofMinutes(5)), "x");
    }
}
