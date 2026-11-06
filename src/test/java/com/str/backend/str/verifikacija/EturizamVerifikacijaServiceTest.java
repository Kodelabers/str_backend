package com.str.backend.str.verifikacija;

import com.str.backend.rn.RnRepository;
import com.str.backend.str.RnFacilityVerification;
import com.str.backend.str.verifikacija.EturizamVerifikacijaWriter.Pokusaj;
import com.str.backend.str.verifikacija.VerifikacijaOutboxEntity.Status;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ponašanje oko pokušaja: utrka na UNIQUE, gutanje grešaka, transakcija. Sam upis pokriva integracijski test. */
class EturizamVerifikacijaServiceTest {

    private static final String RN = "HR180001000000000123";
    private static final Pokusaj POKUSAJ = new Pokusaj(RN, 2, false);

    private final EturizamVerifikacijaWriter writer = mock(EturizamVerifikacijaWriter.class);
    private final VerifikacijaOutbox outbox = mock(VerifikacijaOutbox.class);
    private final EturizamVerifikacijaService service = new EturizamVerifikacijaService(writer, outbox,
            new EturizamVerifikacijaProperties(true, null, null, null, null, null));

    /** eTurizam je upisao zahtjev za isti dokument istodobno: drugi pokušaj ga zatekne, bez neuspjeha. */
    @Test
    void duplicateKey_retriesOnceWithoutRecordingFailure() {
        when(writer.upisi(POKUSAJ))
                .thenThrow(duplicate("unique constraint \"uq_verification_request_source_document\""))
                .thenReturn(Optional.of(Status.ZAHTJEV_POSTOJAO));

        service.obradi(POKUSAJ);

        verify(writer, times(2)).upisi(POKUSAJ);
        verify(outbox, never()).recordFailure(anyString(), anyInt(), any());
    }

    /** Drugi sukob ključa (npr. PK iza sekvence) ponovni pokušaj ne rješava — odmah obični neuspjeh. */
    @Test
    void otherDuplicateKey_noRetry_recordedAsFailure() {
        DuplicateKeyException pk = duplicate("duplicate key value violates unique constraint \"pk_verification_request\"");
        when(writer.upisi(POKUSAJ)).thenThrow(pk);

        assertThat(service.obradi(POKUSAJ)).isEqualTo(EturizamVerifikacijaService.Ishod.NEUSPJEH);

        verify(writer, times(1)).upisi(POKUSAJ);
        verify(outbox).recordFailure(eq(RN), eq(2), same(pk));
    }

    @Test
    void permissionDenied_isInfrastructureOutcome() {
        when(writer.upisi(POKUSAJ)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("x",
                new SQLException("ERROR: permission denied for table verification_request", "42501")));

        assertThat(service.obradi(POKUSAJ)).isEqualTo(EturizamVerifikacijaService.Ishod.NEUSPJEH_INFRASTRUKTURA);
    }

    @Test
    void isDocumentConflict_onlyForDocumentUniqueConstraint() {
        assertThat(EturizamVerifikacijaService.isDocumentConflict(duplicate(
                "Unique index or primary key violation: \"PUBLIC.UQ_VERIFICATION_REQUEST_SOURCE_DOCUMENT\""))).isTrue();
        assertThat(EturizamVerifikacijaService.isDocumentConflict(duplicate(
                "duplicate key value violates unique constraint \"pk_verification_request\""))).isFalse();
    }

    /** Neuspjeh se bilježi s brojem pokušaja koji je pokušaj vidio — tako se ne broji dvaput. */
    @Test
    void failure_isSwallowedAndRecordedWithSeenAttempts() {
        IllegalStateException greska = new IllegalStateException("wrap", new RuntimeException("check violated"));
        when(writer.upisi(POKUSAJ)).thenThrow(greska);

        assertThat(service.obradi(POKUSAJ)).isEqualTo(EturizamVerifikacijaService.Ishod.NEUSPJEH);

        verify(outbox).recordFailure(eq(RN), eq(2), same(greska));
    }

    @Test
    void recordFailureFailing_isSwallowedToo() {
        when(writer.upisi(POKUSAJ)).thenThrow(new IllegalStateException("db down"));
        doThrow(new IllegalStateException("db still down")).when(outbox).recordFailure(anyString(), anyInt(), any());

        assertThatCode(() -> service.obradi(POKUSAJ)).doesNotThrowAnyException();
    }

    @Test
    void disabled_doesNothing() {
        EturizamVerifikacijaService ugasen = new EturizamVerifikacijaService(writer, outbox,
                new EturizamVerifikacijaProperties(false, null, null, null, null, null));

        ugasen.obradi(POKUSAJ);

        verify(writer, never()).upisi(any());
    }

    /** Pokušaj ide u novu transakciju s ograničenjem iz konfiguracije: job {@code timeout}, inline {@code inline-timeout}. */
    @Test
    void writer_runsAttemptInNamedRequiresNewTransactionWithTimeout() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        VerifikacijaOutboxRepository outboxRepository = mock(VerifikacijaOutboxRepository.class);
        when(outboxRepository.lockByRn(RN)).thenReturn(Optional.empty());
        EturizamVerifikacijaWriter pravi = new EturizamVerifikacijaWriter(outboxRepository,
                mock(EturizamVerifikacijaRepository.class), mock(RnRepository.class), mock(RnFacilityVerification.class),
                new EturizamVerifikacijaProperties(true, null, Duration.ofSeconds(7), Duration.ofSeconds(2), null, null),
                Clock.systemUTC(), txManager);

        assertThat(pravi.upisi(POKUSAJ)).isEmpty();
        assertThat(pravi.upisi(Pokusaj.inline(RN))).isEmpty();

        ArgumentCaptor<TransactionDefinition> definicija = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(txManager, times(2)).getTransaction(definicija.capture());
        assertThat(definicija.getAllValues()).extracting(TransactionDefinition::getTimeout).containsExactly(7, 2);
        assertThat(definicija.getAllValues()).allMatch(d ->
                d.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private static DuplicateKeyException duplicate(String sqlMessage) {
        return new DuplicateKeyException("insert", new SQLException(sqlMessage, "23505"));
    }
}
