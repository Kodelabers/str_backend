package com.str.backend.kp;

import com.str.backend.domain.EgopSyncStatus;
import com.str.backend.egop.EgopPismenoEntity;
import com.str.backend.egop.EgopPismenoRepository;
import com.str.backend.kp.client.KpAktivnost;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JPQL odabira kandidata za otpremu u KP i za provjeru aktivnosti, protiv prave baze (H2). Jobovi
 * u unit testovima mockaju repozitorij, pa bi kriv uvjet ovdje prošao nezapažen.
 */
@SpringBootTest
@ActiveProfiles("test")
class KpCandidateQueryTest {

    private static final List<KpOtpremaEntity.Status> NEPOSLANI =
            List.of(KpOtpremaEntity.Status.PENDING, KpOtpremaEntity.Status.FAILED);
    private static final List<Integer> KONACNI =
            List.of(KpOtpremaEntity.KP_POTVRDEN_PRIMITAK, KpOtpremaEntity.KP_ODBIJENO);
    private static final KpPrimatelj FO = new KpPrimatelj(KpPrimatelj.Tip.FO, "12345678903", null);

    @Autowired
    private EgopPismenoRepository pismenoRepository;

    @Autowired
    private KpOtpremaRepository repository;

    @Test
    void retryCandidates_onlyUnsentWithFiledPismeno() {
        UUID pending = otprema(true, o -> { });
        UUID waitingForFiling = otprema(false, o -> { });
        UUID failedDue = otprema(true, o -> o.markFailed("M008", "boom", Instant.now().minus(1, ChronoUnit.MINUTES), null));
        UUID failedInBackoff = otprema(true, o -> o.markFailed("M008", "boom", Instant.now().plus(1, ChronoUnit.HOURS), null));
        UUID sent = otprema(true, o -> o.markSent(Instant.now(), 101, true, null));
        UUID skipped = otprema(true, o -> o.markSkipped("zastario"));
        UUID nemaPretinca = otprema(true, o -> o.markNemaPretinca("K006", "nema KP", null));
        UUID exhausted = otprema(true, o -> o.markExhausted("K013", "nije registriran", 10, null));

        List<UUID> candidates = retryCandidates(Instant.now().plus(1, ChronoUnit.HOURS));

        assertThat(candidates).contains(pending, failedDue);
        assertThat(candidates).doesNotContain(waitingForFiling, failedInBackoff, sent, skipped, nemaPretinca, exhausted);
    }

    /** Neprobana otprema čeka grace od urudžbe, da cron ne preotme inline slanje. */
    @Test
    void retryCandidates_untriedOtpremaWaitsForGrace() {
        UUID fresh = otprema(true, o -> { });

        assertThat(retryCandidates(Instant.now().minus(5, ChronoUnit.MINUTES))).doesNotContain(fresh);
    }

    @Test
    void statusCandidates_sentWithLegalEffectAndNonFinalActivity_neverCheckedFirst() {
        UUID checked = otprema(true, o -> {
            o.markSent(Instant.now(), 101, true, null);
            o.applyAktivnost(aktivnost(1), Instant.now());
        });
        UUID neverChecked = otprema(true, o -> o.markSent(Instant.now(), 101, true, null));
        UUID confirmed = otprema(true, o -> {
            o.markSent(Instant.now(), 101, true, null);
            o.applyAktivnost(aktivnost(2), Instant.now());
        });
        UUID rejected = otprema(true, o -> {
            o.markSent(Instant.now(), 101, true, null);
            o.applyAktivnost(aktivnost(3), Instant.now());
        });
        UUID withoutLegalEffect = otprema(true, o -> o.markSent(Instant.now(), 101, false, null));
        UUID notSent = otprema(true, o -> { });

        List<UUID> candidates = repository.findStatusCandidates(KpOtpremaEntity.Status.SENT, KONACNI,
                Instant.now().minus(1, ChronoUnit.HOURS), PageRequest.of(0, 1000));

        assertThat(candidates).contains(checked, neverChecked);
        assertThat(candidates).doesNotContain(confirmed, rejected, withoutLegalEffect, notSent);
        assertThat(candidates.indexOf(neverChecked)).isLessThan(candidates.indexOf(checked));
    }

    private List<UUID> retryCandidates(Instant notAfter) {
        Instant now = Instant.now();
        return repository.findRetryCandidates(EgopSyncStatus.SYNCED, NEPOSLANI, 10, notAfter,
                now.minus(1, ChronoUnit.HOURS), now);
    }

    private static KpAktivnost aktivnost(int status) {
        return new KpAktivnost(List.of(), "", status, null, null, null, null);
    }

    private UUID otprema(boolean pismenoSynced, Consumer<KpOtpremaEntity> setup) {
        EgopPismenoEntity pismeno = EgopPismenoEntity.create(UUID.randomUUID(),
                "Obavijest o dodjeli registracijskog broja", EgopPismenoEntity.Smjer.IZLAZNO,
                1002, "529-06/26-2", "102", false);
        if (pismenoSynced) {
            pismeno.markDocumentAttached();
        }
        pismenoRepository.saveAndFlush(pismeno);
        KpOtpremaEntity otprema = KpOtpremaEntity.pending(pismeno.getId(), FO, Instant.now());
        setup.accept(otprema);
        return repository.saveAndFlush(otprema).getId();
    }
}
