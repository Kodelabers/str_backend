package com.str.backend.egop;

import com.str.backend.kp.KpOtpremaService;
import com.str.backend.kp.KpOtpremaRepository;
import com.str.backend.kp.KpOtpremaEntity;
import com.str.backend.domain.EgopSyncStatus;
import com.str.backend.request.SubmissionRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EgopRetryJobTest {

    private final SubmissionRepository submissionRepository = mock(SubmissionRepository.class);
    private final EgopRegistrationDispatcher dispatcher = mock(EgopRegistrationDispatcher.class);
    private final EgopPismenoRepository pismenoRepository = mock(EgopPismenoRepository.class);
    private final EgopAktDispatcher aktDispatcher = mock(EgopAktDispatcher.class);
    private final KpOtpremaRepository kpOtpremaRepository = mock(KpOtpremaRepository.class);
    private final KpOtpremaService kpOtprema = mock(KpOtpremaService.class);

    private final EgopRetryPolicy retryPolicy =
            new EgopRetryPolicy(10, Duration.ofMinutes(2), Duration.ofHours(2));

    private EgopRetryJob job(int batchSize) {
        return new EgopRetryJob(submissionRepository, pismenoRepository, dispatcher, aktDispatcher,
                retryPolicy, new EgopAktiBezSifre(java.util.Set.of()), kpOtpremaRepository, kpOtprema,
                Duration.ofMinutes(5), Duration.ofDays(7), batchSize);
    }

    @Test
    void retryPending_dispatchesEachCandidate() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(submissionRepository.findEgopRetryCandidates(eq(EgopSyncStatus.SYNCED), eq(10), any(), any(), any()))
                .thenReturn(List.of(a, b));

        job(50).retryPending();

        verify(dispatcher).dispatch(a);
        verify(dispatcher).dispatch(b);
    }

    @Test
    void retryPending_respectsBatchSize() {
        List<UUID> many = IntStream.range(0, 10).mapToObj(i -> UUID.randomUUID()).toList();
        when(submissionRepository.findEgopRetryCandidates(any(), any(Integer.class), any(), any(), any()))
                .thenReturn(many);

        job(3).retryPending();

        verify(dispatcher, times(3)).dispatch(any());
    }

    @Test
    void retryPending_noCandidates_doesNothing() {
        when(submissionRepository.findEgopRetryCandidates(any(), any(Integer.class), any(), any(), any()))
                .thenReturn(List.of());

        job(50).retryPending();

        verify(dispatcher, times(0)).dispatch(any());
    }

    /** Akti životnog ciklusa imaju vlastiti red kandidata i vlastiti dispatcher. */
    @Test
    void retryPending_dispatchesLifecycleActs() {
        UUID akt = UUID.randomUUID();
        when(pismenoRepository.findRetryCandidates(eq(EgopSyncStatus.SYNCED),
                eq(EgopPismenoEntity.ACT_REF_REGISTRACIJA), any(), eq(10), any(), any(), any()))
                .thenReturn(List.of(akt));

        job(50).retryPending();

        verify(aktDispatcher).dispatch(akt);
    }

    /** Pad jednog akta ne smije zaustaviti ostale u batchu. */
    @Test
    void retryPending_actFailure_continuesWithRest() {
        UUID prvi = UUID.randomUUID();
        UUID drugi = UUID.randomUUID();
        when(pismenoRepository.findRetryCandidates(any(), any(), any(), any(Integer.class), any(), any(), any()))
                .thenReturn(List.of(prvi, drugi));
        org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
                .when(aktDispatcher).dispatch(prvi);

        job(50).retryPending();

        verify(aktDispatcher).dispatch(drugi);
    }

    @Test
    void retryPending_kpEnabled_retriesKpOtpreme() {
        UUID otprema = UUID.randomUUID();
        when(kpOtprema.isEnabled()).thenReturn(true);
        when(kpOtpremaRepository.findRetryCandidates(eq(EgopSyncStatus.SYNCED),
                eq(List.of(KpOtpremaEntity.Status.PENDING, KpOtpremaEntity.Status.FAILED)),
                eq(10), any(), any(), any()))
                .thenReturn(List.of(otprema));

        job(50).retryPending();

        verify(kpOtprema).retry(otprema);
    }

    @Test
    void retryPending_kpDisabled_doesNotQueryKp() {
        when(kpOtprema.isEnabled()).thenReturn(false);

        job(50).retryPending();

        verify(kpOtpremaRepository, times(0)).findRetryCandidates(any(), any(), any(Integer.class), any(), any(), any());
        verify(kpOtprema, times(0)).retry(any());
    }

}
