package com.str.backend.email;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MailRetryStore} protiv prave baze (H2) — mapiranje enuma i {@code Instant}-a te izvedeni
 * upit dospjelih zapisa ne bi se vidjeli s mockanim repozitorijem.
 *
 * <p>Store se sastavlja ručno: testni profil ima mail ugašen, a store tada namjerno ništa ne
 * bilježi.
 */
@SpringBootTest
@ActiveProfiles("test")
class MailRetryStoreTest {

    private static final Instant T0 = Instant.parse("2026-10-02T10:00:00Z");

    @Autowired
    private MailRetryRepository repository;

    private UUID ref;

    @BeforeEach
    void setUp() {
        ref = UUID.randomUUID();
    }

    @Test
    void firstFailure_schedulesRetryAfterBackoff() {
        store(T0, true).recordFailure(MailRetryEntity.Kind.RN_ISSUED, ref, "smtp_failed");

        MailRetryEntity zapis = zapis();
        assertThat(zapis.getStatus()).isEqualTo(MailRetryEntity.Status.PENDING);
        assertThat(zapis.getAttempts()).isEqualTo(1);
        assertThat(zapis.getNextAttemptAt()).isEqualTo(T0.plus(Duration.ofMinutes(5)));
        assertThat(zapis.getLastError()).isEqualTo("smtp_failed");
    }

    /** Ugašen mail nije neuspjeh — uključivanjem se inače odjednom pošalje sve nakupljeno. */
    @Test
    void mailDisabled_recordsNothing() {
        store(T0, false).recordFailure(MailRetryEntity.Kind.RN_ISSUED, ref, "smtp_failed");

        assertThat(repository.findByKindAndRefId(MailRetryEntity.Kind.RN_ISSUED, ref)).isEmpty();
    }

    @Test
    void repeatedFailure_backsOffExponentially_thenAbandons() {
        store(T0, true).recordFailure(MailRetryEntity.Kind.RN_LIFECYCLE, ref, "smtp_failed");
        store(T0, true).recordFailure(MailRetryEntity.Kind.RN_LIFECYCLE, ref, "smtp_failed");

        assertThat(zapis(MailRetryEntity.Kind.RN_LIFECYCLE).getAttempts()).isEqualTo(2);
        assertThat(zapis(MailRetryEntity.Kind.RN_LIFECYCLE).getNextAttemptAt())
                .isEqualTo(T0.plus(Duration.ofMinutes(10)));

        store(T0, true).recordFailure(MailRetryEntity.Kind.RN_LIFECYCLE, ref, "smtp_failed");

        MailRetryEntity zapis = zapis(MailRetryEntity.Kind.RN_LIFECYCLE);
        assertThat(zapis.getAttempts()).isEqualTo(3);
        assertThat(zapis.getStatus()).isEqualTo(MailRetryEntity.Status.ABANDONED);
        assertThat(zapis.getNextAttemptAt()).isNull();
    }

    @Test
    void markSent_closesPendingRecord_andIgnoresMissingOne() {
        store(T0, true).markSent(MailRetryEntity.Kind.RN_ISSUED, ref);
        assertThat(repository.findByKindAndRefId(MailRetryEntity.Kind.RN_ISSUED, ref)).isEmpty();

        store(T0, true).recordFailure(MailRetryEntity.Kind.RN_ISSUED, ref, "smtp_failed");
        store(T0, true).markSent(MailRetryEntity.Kind.RN_ISSUED, ref);

        assertThat(zapis().getStatus()).isEqualTo(MailRetryEntity.Status.SENT);
    }

    @Test
    void isQueued_onlyWhilePending() {
        assertThat(store(T0, true).isQueued(MailRetryEntity.Kind.RN_ISSUED, ref)).isFalse();

        store(T0, true).recordFailure(MailRetryEntity.Kind.RN_ISSUED, ref, "smtp_failed");
        assertThat(store(T0, true).isQueued(MailRetryEntity.Kind.RN_ISSUED, ref)).isTrue();

        store(T0, true).markSent(MailRetryEntity.Kind.RN_ISSUED, ref);
        assertThat(store(T0, true).isQueued(MailRetryEntity.Kind.RN_ISSUED, ref)).isFalse();
    }

    /** Poslan zapis više ne prima neuspjehe — inače bi ga kasniji pad vratio u red. */
    @Test
    void failureAfterSent_isIgnored() {
        store(T0, true).recordFailure(MailRetryEntity.Kind.RN_ISSUED, ref, "smtp_failed");
        store(T0, true).markSent(MailRetryEntity.Kind.RN_ISSUED, ref);
        store(T0, true).recordFailure(MailRetryEntity.Kind.RN_ISSUED, ref, "smtp_failed");

        assertThat(zapis().getStatus()).isEqualTo(MailRetryEntity.Status.SENT);
        assertThat(zapis().getAttempts()).isEqualTo(1);
    }

    @Test
    void due_returnsOnlyPendingWhoseTimeHasCome() {
        UUID dospio = UUID.randomUUID();
        UUID ceka = UUID.randomUUID();
        UUID odbacen = UUID.randomUUID();
        store(T0, true).recordFailure(MailRetryEntity.Kind.RN_ISSUED, dospio, "x");
        store(T0.plus(Duration.ofHours(1)), true).recordFailure(MailRetryEntity.Kind.RN_ISSUED, ceka, "x");
        store(T0, true).recordFailure(MailRetryEntity.Kind.RN_ISSUED, odbacen, "x");
        store(T0, true).abandon(MailRetryEntity.Kind.RN_ISSUED, odbacen, "no_email");

        // T0 + 10 min: prvi je dospio (T0 + 5 min), drugi čeka do T0 + 65 min.
        assertThat(store(T0.plus(Duration.ofMinutes(10)), true).due(50))
                .extracting(MailRetryEntity::getRefId)
                .contains(dospio)
                .doesNotContain(ceka, odbacen);
    }

    private MailRetryStore store(Instant now, boolean mailEnabled) {
        return new MailRetryStore(repository,
                new MailProperties(mailEnabled, "str@example.com", "https://str.example.com/login", null),
                Clock.fixed(now, ZoneOffset.UTC), 3, Duration.ofMinutes(5), Duration.ofHours(2));
    }

    private MailRetryEntity zapis() {
        return zapis(MailRetryEntity.Kind.RN_ISSUED);
    }

    private MailRetryEntity zapis(MailRetryEntity.Kind kind) {
        return repository.findByKindAndRefId(kind, ref).orElseThrow();
    }
}
