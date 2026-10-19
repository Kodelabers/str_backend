package com.str.backend.egop;

import com.str.backend.domain.EgopSyncStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JPQL odabira kandidata za otpremu u KP i za provjeru statusa, protiv prave baze (H2). Jobovi
 * u unit testovima mockaju repozitorij, pa bi kriv uvjet ovdje prošao nezapažen.
 */
@SpringBootTest
@ActiveProfiles("test")
class EgopKpCandidateQueryTest {

    private static final List<EgopKpOtpremaEntity.Status> NEPOSLANI =
            List.of(EgopKpOtpremaEntity.Status.PENDING, EgopKpOtpremaEntity.Status.FAILED);
    private static final List<Integer> KONACNI =
            List.of(EgopKpOtpremaEntity.KP_POTVRDEN_PRIMITAK, EgopKpOtpremaEntity.KP_ODBIJENO);

    @Autowired
    private EgopPismenoRepository pismenoRepository;

    @Autowired
    private EgopKpOtpremaRepository repository;

    @Test
    void retryCandidates_onlyUnsentWithFiledPismeno() {
        UUID pending = otprema(true, o -> { });
        UUID waitingForFiling = otprema(false, o -> { });
        UUID failedDue = otprema(true, o -> o.markFailed("boom", Instant.now().minus(1, ChronoUnit.MINUTES)));
        UUID failedInBackoff = otprema(true, o -> o.markFailed("boom", Instant.now().plus(1, ChronoUnit.HOURS)));
        UUID sent = otprema(true, o -> o.markSent(Instant.now()));
        UUID skipped = otprema(true, o -> o.markSkipped("zastario"));
        UUID exhausted = otprema(true, o -> {
            for (int i = 0; i < 10; i++) {
                o.markFailed("boom", Instant.now().minus(1, ChronoUnit.MINUTES));
            }
        });

        List<UUID> candidates = retryCandidates(Instant.now().plus(1, ChronoUnit.HOURS));

        assertThat(candidates).contains(pending, failedDue);
        assertThat(candidates).doesNotContain(waitingForFiling, failedInBackoff, sent, skipped, exhausted);
    }

    /** Neprobana otprema čeka grace od urudžbe, da cron ne preotme inline slanje. */
    @Test
    void retryCandidates_untriedOtpremaWaitsForGrace() {
        UUID fresh = otprema(true, o -> { });

        assertThat(retryCandidates(Instant.now().minus(5, ChronoUnit.MINUTES))).doesNotContain(fresh);
    }

    @Test
    void statusCandidates_sentWithNonFinalStatus_neverCheckedFirst() {
        UUID checked = otprema(true, o -> {
            o.markSent(Instant.now());
            o.applyKpStatus(new KpStatusPoruke(1, null, null, null, null, null, null, null), Instant.now());
        });
        UUID neverChecked = otprema(true, o -> o.markSent(Instant.now()));
        UUID confirmed = otprema(true, o -> {
            o.markSent(Instant.now());
            o.applyKpStatus(new KpStatusPoruke(2, null, null, null, null, null, null, null), Instant.now());
        });
        UUID rejected = otprema(true, o -> {
            o.markSent(Instant.now());
            o.applyKpStatus(new KpStatusPoruke(3, null, null, null, null, null, null, null), Instant.now());
        });
        UUID notSent = otprema(true, o -> { });

        List<UUID> candidates = repository.findStatusCandidates(EgopKpOtpremaEntity.Status.SENT, KONACNI,
                Instant.now().minus(1, ChronoUnit.HOURS), PageRequest.of(0, 1000));

        assertThat(candidates).contains(checked, neverChecked);
        assertThat(candidates).doesNotContain(confirmed, rejected, notSent);
        assertThat(candidates.indexOf(neverChecked)).isLessThan(candidates.indexOf(checked));
    }

    private List<UUID> retryCandidates(Instant notAfter) {
        Instant now = Instant.now();
        return repository.findRetryCandidates(EgopSyncStatus.SYNCED, NEPOSLANI, 10, notAfter,
                now.minus(1, ChronoUnit.HOURS), now);
    }

    private UUID otprema(boolean pismenoSynced, java.util.function.Consumer<EgopKpOtpremaEntity> setup) {
        EgopPismenoEntity pismeno = EgopPismenoEntity.create(UUID.randomUUID(),
                "Obavijest o dodjeli registracijskog broja", EgopPismenoEntity.Smjer.IZLAZNO,
                1002, "529-06/26-2", "102", false);
        if (pismenoSynced) {
            pismeno.markDocumentAttached();
        }
        pismenoRepository.saveAndFlush(pismeno);
        EgopKpOtpremaEntity otprema = EgopKpOtpremaEntity.pending(pismeno.getId(), "12345678903", Instant.now());
        setup.accept(otprema);
        return repository.saveAndFlush(otprema).getId();
    }
}
