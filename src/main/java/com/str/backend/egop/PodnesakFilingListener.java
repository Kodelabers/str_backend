package com.str.backend.egop;

import com.str.backend.document.StrDocumentType;
import com.str.backend.objection.SuspensionObjectionSubmittedEvent;
import com.str.backend.rn.RnLifecycleLookup;
import com.str.backend.rn.RucniAkt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;
import java.util.UUID;

/**
 * Očitovanje i prigovor iznajmljivača ({@code SuspensionObjectionService#submit}) postaju <b>ulazno pismeno</b>
 * u eGOP predmetu postupka (bug s testiranja MINTS-a, studeni 2026.). Vrsta ovisi o statusu broja pri predaji
 * ({@link StrDocumentType#podnesakNaAkt}).
 *
 * <p>Kao i {@link RnLifecycleFilingListener}: nakon commita predaje pismeno se zapiše u {@code egop_pismeno}
 * ({@code act_ref} {@code PRIGOVOR:<objection_id>}, jer na isti prijelaz može biti više podnesaka), pa se
 * odmah pokuša poslati. Pad slanja ne gubi ništa — pismeno je zapisano i {@link EgopRetryJob} ga pokupi. Sam
 * dokument se ne kopira: glavni dokument je datoteka s prigovora, a ostale datoteke stranke idu kao prilozi
 * ({@link EgopAktDispatcher}, {@link EgopFilingService#filePodnesak}).
 *
 * <p><b>Postupak koji vodi županijski službenik</b> (T16, ručni akt bez eGOP-a): podnesak ostaje samo u STR-u.
 * Prepoznaje se po zapisu prijelaza na koji podnesak odgovara ({@code rn_log_id}): ručna KLASA i URBROJ na
 * njemu ({@link RnLifecycleLookup#findRucniAkt}). Taj zapis nosi i istek roka nakon ručnog prijedloga, pa
 * pravilo vrijedi i za prigovor na suspenziju ili povlačenje u županijskom postupku — neovisno o tome u
 * kojoj je organizaciji službenik danas.
 *
 * <p>Uz ugašen eGOP ({@code EGOP_ENABLED=false}) ide isti tok kroz mock klijent, kao i ostala pismena.
 *
 * <p><b>Namjerno bez {@code @Transactional}</b>, iz istog razloga kao {@link RnLifecycleFilingListener}.
 * Iznimka ne smije izaći: u {@code AFTER_COMMIT} fazi bi se vratila pozivatelju i iznajmljivač bi dobio
 * grešku za podnesak koji je zaprimljen.
 */
@Component
public class PodnesakFilingListener {

    private static final Logger log = LoggerFactory.getLogger(PodnesakFilingListener.class);

    private final RnLifecycleLookup lookup;
    private final EgopFilingStore store;
    private final EgopAktDispatcher dispatcher;
    private final EgopAktiBezSifre bezSifre;

    public PodnesakFilingListener(RnLifecycleLookup lookup, EgopFilingStore store, EgopAktDispatcher dispatcher,
                                  EgopAktiBezSifre bezSifre) {
        this.lookup = lookup;
        this.store = store;
        this.dispatcher = dispatcher;
        this.bezSifre = bezSifre;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmitted(SuspensionObjectionSubmittedEvent event) {
        try {
            zapisi(event).ifPresent(p -> posalji(event, p));
        } catch (RuntimeException e) {
            log.error("egop_podnesak_not_recorded objection={} rn={} rn_status={}: {} — podnesak je zaprimljen u STR-u,"
                            + " ali nije zapisan za eGOP; urudžbirati ručno",
                    event.objectionId(), event.rn(), event.rnStatus(), e.getMessage(), e);
        }
    }

    private Optional<Zapisano> zapisi(SuspensionObjectionSubmittedEvent event) {
        Optional<StrDocumentType> vrsta = StrDocumentType.podnesakNaAkt(event.rnStatus());
        if (vrsta.isEmpty()) {
            // SuspensionObjectionService podnesak izvan ova četiri statusa odbija; ovo je samo brana.
            log.error("egop_podnesak_skipped reason=rn_status objection={} rn={} rn_status={} — nema vrste pismena",
                    event.objectionId(), event.rn(), event.rnStatus());
            return Optional.empty();
        }
        if (event.rnLogId() == null) {
            // Bez zapisa prijelaza ne zna se ni tko vodi postupak ni iza kojeg akta podnesak ide.
            log.error("egop_podnesak_skipped reason=missing_rn_log objection={} rn={} vrsta='{}' — nema zapisa"
                            + " prijelaza u {}; podnesak NIJE urudžbiran ni u eGOP-u ni u STR-u (ne zna se tko vodi"
                            + " postupak) — mora se urudžbirati ručno u eGOP-u",
                    event.objectionId(), event.rn(), vrsta.get().vrstaPismenaNaziv(), event.rnStatus());
            return Optional.empty();
        }
        Optional<RucniAkt> rucni = lookup.findRucniAkt(event.rnLogId());
        if (rucni.isPresent()) {
            log.info("egop_podnesak_skipped reason=zupanijski_postupak objection={} rn={} vrsta='{}' rn_log={}"
                            + " klasa={} — postupak vodi županija bez eGOP-a, podnesak ostaje u STR-u",
                    event.objectionId(), event.rn(), vrsta.get().vrstaPismenaNaziv(), event.rnLogId(),
                    rucni.get().klasa());
            return Optional.empty();
        }
        UUID submissionId = lookup.findSubmissionId(event.rn()).orElse(null);
        if (submissionId == null) {
            log.error("egop_podnesak_skipped reason=missing_submission objection={} rn={} vrsta='{}'",
                    event.objectionId(), event.rn(), vrsta.get().vrstaPismenaNaziv());
            return Optional.empty();
        }
        EgopPismenoEntity pismeno = store.saveAkt(EgopPismenoEntity.forPodnesak(submissionId, event.rn(),
                event.objectionId(), vrsta.get().vrstaPismenaNaziv()));
        log.info("egop_podnesak_recorded pismeno={} objection={} rn={} vrsta='{}' rn_log={}", pismeno.getId(),
                event.objectionId(), event.rn(), vrsta.get().vrstaPismenaNaziv(), event.rnLogId());
        return Optional.of(new Zapisano(vrsta.get(), pismeno));
    }

    private record Zapisano(StrDocumentType vrsta, EgopPismenoEntity pismeno) {}

    private void posalji(SuspensionObjectionSubmittedEvent event, Zapisano z) {
        if (!bezSifre.urudzbiv(z.vrsta())) {
            // Kao kod akata: zapisano, ali se ne šalje dok je slug na popisu; EgopRetryJob ga tada izuzima.
            log.info("egop_akt_not_filed reason=vrsta_bez_sifre akt={} rn={} vrsta='{}' — maknuti '{}' iz"
                            + " str.egop.akti-bez-sifre kad MINT unese vrstu", z.pismeno().getId(), event.rn(),
                    z.vrsta().vrstaPismenaNaziv(), z.vrsta().slug());
            return;
        }
        dispatcher.dispatch(z.pismeno().getId());
    }
}
