package com.str.backend.egop;

import com.str.backend.egop.exception.EgopException;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Otprema izlaznih pismena u korisnički pretinac (KP) eGrađana preko eGOP-a.
 *
 * <p>Dva koraka, po pravilu „kreiramo lokalno pa šaljemo":
 * <ol>
 *   <li>{@link #register} — kad izlazno pismeno <b>nastane</b>, zapiše se otprema ({@code PENDING})
 *       s primateljem razriješenim u tom trenutku ({@link KpPrimatelj}). Akt nastao dok je KP bio
 *       ugašen nema zapisa, pa se nikad ne šalje;</li>
 *   <li>{@link #otpremi} / {@link #retry} — kad je pismeno urudžbirano i ima dokument, ide SOAP
 *       poziv. Akt urudžbiran kasnije od {@code str.egop.kp.max-akt-age} od nastanka se ne šalje
 *       nego ide u {@code SKIPPED}: zastarjeli akt (npr. prijedlog suspenzije u već obustavljenom
 *       postupku) ne smije stranci otvoriti rok.</li>
 * </ol>
 * Pad otpreme bilježi se samo na otpremi; status urudžbe se ne dira, a ponovni pokušaj vodi
 * {@link EgopRetryJob}.
 *
 * <p>Radi samo uz uključen eGOP <b>i</b> KP. S mockom bi otpreme dobile status {@code SENT} iako
 * ništa nije poslano, pa ih kasnije paljenje eGOP-a ne bi nikad poslalo.
 */
@Service
public class EgopKpOtpremaService {

    private static final Logger log = LoggerFactory.getLogger(EgopKpOtpremaService.class);

    private final EgopClient egopClient;
    private final EgopKpOtpremaStore store;
    private final EgopFilingStore filingStore;
    private final LessorRepository lessorRepository;
    private final RnRepository rnRepository;
    private final EgopRetryPolicy retryPolicy;
    private final EgopKpExecutor executor;
    private final EgopAktKorisnik korisnik;
    private final Duration maxAktAge;
    private final boolean enabled;

    public EgopKpOtpremaService(EgopClient egopClient,
                                EgopKpOtpremaStore store,
                                EgopFilingStore filingStore,
                                LessorRepository lessorRepository,
                                RnRepository rnRepository,
                                EgopRetryPolicy retryPolicy,
                                EgopKpExecutor executor,
                                EgopAktKorisnik korisnik,
                                @Value("${str.egop.kp.max-akt-age:P7D}") Duration maxAktAge,
                                @Value("${str.egop.kp.enabled:false}") boolean kpEnabled,
                                @Value("${hr.infodom.str.integration.egop.enabled:false}") boolean egopEnabled) {
        this.egopClient = egopClient;
        this.store = store;
        this.filingStore = filingStore;
        this.lessorRepository = lessorRepository;
        this.rnRepository = rnRepository;
        this.retryPolicy = retryPolicy;
        this.executor = executor;
        this.korisnik = korisnik;
        this.maxAktAge = maxAktAge;
        this.enabled = kpEnabled && egopEnabled;
        if (kpEnabled && !egopEnabled) {
            log.warn("egop_kp_disabled reason=egop_disabled — str.egop.kp.enabled=true, ali eGOP je ugašen;"
                    + " otprema u korisnički pretinac ne radi");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Zapisuje otpremu za izlazno pismeno. Ulazna pismena i iznajmljivači bez pretinca (non-EU)
     * se preskaču — njima ostaje dostava e-poštom. Idempotentno.
     *
     * <p>Ne baca iznimke: zove se iz toka koji stvara akt, i pad zapisa otpreme ne smije oboriti
     * ni urudžbu ni promjenu statusa RB-a.
     *
     * @param aktNastaoAt kad je akt nastao — od toga se mjeri {@code max-akt-age}
     */
    public Optional<EgopKpOtpremaEntity> register(EgopPismenoEntity pismeno, LessorEntity lessor,
                                                  Instant aktNastaoAt) {
        if (!enabled || pismeno.getSmjer() != EgopPismenoEntity.Smjer.IZLAZNO) {
            return Optional.empty();
        }
        Optional<String> primatelj = KpPrimatelj.resolve(lessor);
        if (primatelj.isEmpty()) {
            if (KpPrimatelj.isAnomaly(lessor)) {
                log.error("egop_kp_skipped reason=legal_entity_without_representative pismeno={} lessor={}"
                        + " — pravna osoba bez OIB-a zastupnika, akt neće otići u KP", pismeno.getId(),
                        lessor.getLessorId());
            }
            return Optional.empty();
        }
        try {
            return Optional.of(store.register(pismeno.getId(), primatelj.get(), aktNastaoAt));
        } catch (RuntimeException e) {
            log.error("egop_kp_not_registered pismeno={} rn={} vrsta='{}': {} — akt neće otići u KP",
                    pismeno.getId(), pismeno.getRn(), pismeno.getVrstaPismenaNaziv(), e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * {@link #register} za akt životnog ciklusa, iz {@code RnLifecycleFilingListener}-a, u trenutku
     * nastanka akta. Iznajmljivača čita sam, jer ga listener nema. Ne baca iznimke.
     */
    public void registerForAkt(EgopPismenoEntity akt) {
        if (!enabled || akt.getSmjer() != EgopPismenoEntity.Smjer.IZLAZNO) {
            return;
        }
        try {
            Optional<LessorEntity> lessor = filingStore.findSubmission(akt.getSubmissionId())
                    .flatMap(s -> lessorRepository.findById(s.getLessorId()));
            if (lessor.isEmpty()) {
                log.error("egop_kp_not_registered reason=missing_lessor akt={} rn={}", akt.getId(), akt.getRn());
                return;
            }
            register(akt, lessor.get(), akt.getCreatedAt());
        } catch (RuntimeException e) {
            log.error("egop_kp_not_registered akt={} rn={}: {} — akt neće otići u KP",
                    akt.getId(), akt.getRn(), e.getMessage(), e);
        }
    }

    /**
     * Otprema pismena ako je urudžbirano, a otprema zapisana i još nije dovršena. Idempotentno.
     *
     * <p><b>Asinkrono</b> ({@link EgopKpExecutor}): zove se iz toka urudžbe, koji ide na niti
     * zahtjeva (izdavanje RB-a, opoziv, povlačenje), a dostava u KP ne treba ništa što korisnik
     * čeka u odgovoru. Sve što zadatak čita već je upisano u vlastitim transakcijama, pa ne ovisi o
     * transakciji pozivatelja. Ako se zadatak izgubi (restart, pun red), otpremu pokupi
     * {@link EgopRetryJob}.
     *
     * <p>Ne baca iznimke — u asinkronoj niti ih ionako nitko ne bi primio.
     */
    public void otpremi(UUID pismenoId) {
        if (!enabled) {
            return;
        }
        executor.submit(() -> otpremiNow(pismenoId));
    }

    private void otpremiNow(UUID pismenoId) {
        try {
            store.findByPismeno(pismenoId).filter(o -> !o.isDone())
                    .ifPresent(o -> send(o, o.getAttempts() > 0));
        } catch (RuntimeException e) {
            log.error("egop_kp_error pismeno={}: {} — otpremu pokupi retry job", pismenoId, e.getMessage(), e);
        }
    }

    /**
     * Ulaz za {@link EgopRetryJob}, po id-u otpreme. Uvijek prvo pita eGOP je li poruka već u
     * pretincu: kandidat za retry može biti i otprema čiji je prethodni poziv prošao, a odgovor
     * ili upis uspjeha se izgubio.
     */
    public void retry(UUID otpremaId) {
        if (!enabled) {
            return;
        }
        store.find(otpremaId).filter(o -> !o.isDone()).ifPresent(o -> send(o, true));
    }

    private void send(EgopKpOtpremaEntity otprema, boolean provjeriPretinac) {
        EgopPismenoEntity pismeno = filingStore.findAkt(otprema.getPismenoId()).orElse(null);
        if (pismeno == null) {
            log.error("egop_kp_skipped reason=missing_pismeno otprema={} pismeno={}",
                    otprema.getId(), otprema.getPismenoId());
            return;
        }
        if (!pismeno.isSynced() || !pismeno.isFiled()) {
            // Urudžba još nije dovršena — otpremu pokrene njezin uspjeh ili retry job.
            log.debug("egop_kp_waiting reason=pismeno_not_synced otprema={} pismeno={}",
                    otprema.getId(), pismeno.getId());
            return;
        }
        Instant now = Instant.now();
        if (otprema.getAktNastaoAt().plus(maxAktAge).isBefore(now)) {
            store.markSkipped(otprema.getId(), "zastario: akt nastao " + otprema.getAktNastaoAt()
                    + ", urudžbiran nakon više od " + maxAktAge);
            log.warn("egop_kp_skipped reason=stale otprema={} pismeno={} rn={} vrsta='{}' akt_nastao_at={}"
                            + " — akt se ne dostavlja u KP",
                    otprema.getId(), pismeno.getId(), pismeno.getRn(), pismeno.getVrstaPismenaNaziv(),
                    otprema.getAktNastaoAt());
            return;
        }
        // Pod istim korisnikom pod kojim je pismeno urudžbirano (odluka 21.10.2026.): akt
        // službenika je u predmetu za koji je nadležan službenik, ne aplikacijski korisnik.
        String korisnikPismena = korisnik.za(pismeno);
        if (provjeriPretinac) {
            Optional<Instant> poslano = vecUPretincu(otprema, pismeno, korisnikPismena);
            if (poslano.isPresent()) {
                recordSent(otprema, pismeno, poslano.get(), "already_in_pretinac");
                return;
            }
        }

        try {
            egopClient.otpremiUKorisnickiPretinac(korisnikPismena, pismeno.getJop(), otprema.getPrimateljOib(),
                    napomena(pismeno));
        } catch (EgopException | RuntimeException e) {
            handleFailure(otprema, pismeno, e);
            return;
        }
        // Izvan try bloka: poruka je otišla, pa pad upisa ne smije postati neuspjeh slanja — retry
        // bi je poslao drugi put. Neupisana otprema ostaje kandidat, a retry je prvo provjeri u KP-u.
        recordSent(otprema, pismeno, now, "sent");
    }

    /**
     * Je li poruka za ovo pismeno i ovog primatelja već otpremljena. Greška ili nepoznata poruka
     * znače „nije" — tada se šalje.
     *
     * <p>Dovoljno je da otpremnica postoji ({@code DatumVrijemeKreiranja}): eGOP je zaprimio naš
     * raniji poziv, a slanje prema KP-u radi sam i može kasniti. Čekati {@code DatumVrijemeSlanja}
     * značilo bi u tom razmaku poslati drugu otpremnicu iste poruke.
     */
    private Optional<Instant> vecUPretincu(EgopKpOtpremaEntity otprema, EgopPismenoEntity pismeno,
                                           String korisnikPismena) {
        KpStatusPoruke kp;
        try {
            kp = egopClient.dohvatiKpStatusPoruke(korisnikPismena, pismeno.getJop(), otprema.getPrimateljOib());
        } catch (EgopException | RuntimeException e) {
            log.debug("egop_kp_status_unknown otprema={} pismeno={}: {} — šalje se",
                    otprema.getId(), pismeno.getId(), e.getMessage());
            return Optional.empty();
        }
        Instant otpremljeno = kp.poslano() != null ? kp.poslano() : kp.kreirano();
        if (otpremljeno == null) {
            return Optional.empty();
        }
        // Zapis statusa je usputan; njegov pad ne mijenja činjenicu da je poruka u pretincu.
        try {
            store.applyKpStatus(otprema.getId(), kp, Instant.now());
        } catch (RuntimeException e) {
            log.warn("egop_kp_status_not_recorded otprema={}: {}", otprema.getId(), e.getMessage());
        }
        return Optional.of(otpremljeno);
    }

    private void recordSent(EgopKpOtpremaEntity otprema, EgopPismenoEntity pismeno, Instant sentAt,
                            String ishod) {
        try {
            store.markSent(otprema.getId(), sentAt);
            log.info("egop_kp_sent ishod={} otprema={} pismeno={} rn={} vrsta='{}' urbroj={}", ishod,
                    otprema.getId(), pismeno.getId(), pismeno.getRn(), pismeno.getVrstaPismenaNaziv(),
                    pismeno.getUrBroj());
        } catch (RuntimeException e) {
            log.error("egop_kp_sent_not_recorded otprema={} pismeno={} rn={}: {} — poruka JE u pretincu;"
                            + " retry će to utvrditi preko DohvatiKPStatusPoruke i ne šalje ponovo",
                    otprema.getId(), pismeno.getId(), pismeno.getRn(), e.getMessage(), e);
        }
    }

    private void handleFailure(EgopKpOtpremaEntity otprema, EgopPismenoEntity pismeno, Exception cause) {
        Instant nextAttemptAt = retryPolicy.nextAttemptAt(Instant.now(), otprema.getAttempts());
        int attempts = store.markFailed(otprema.getId(), cause.getMessage(), nextAttemptAt);
        if (retryPolicy.isExhausted(attempts)) {
            log.error("egop_kp_retry_exhausted otprema={} pismeno={} rn={} vrsta='{}' attempts={} last_error={}"
                            + " — akt se više neće automatski otpremati u KP",
                    otprema.getId(), pismeno.getId(), pismeno.getRn(), pismeno.getVrstaPismenaNaziv(),
                    attempts, cause.getMessage());
        } else {
            log.error("egop_kp_failed otprema={} pismeno={} rn={} vrsta='{}' attempt={} next_attempt_at={}: {}",
                    otprema.getId(), pismeno.getId(), pismeno.getRn(), pismeno.getVrstaPismenaNaziv(),
                    attempts, nextAttemptAt, cause.getMessage(), cause);
        }
    }

    /**
     * Zapisuje se na otpremnicu eGOP-a — čitljivo službeniku u pisarnici. Registracijska pismena
     * nemaju RB na sebi ({@code rn} je prazan), pa se čita s RB-a submissiona.
     */
    String napomena(EgopPismenoEntity pismeno) {
        String rn = pismeno.getRn() != null ? pismeno.getRn()
                : rnRepository.findBySubmissionId(pismeno.getSubmissionId()).stream()
                        .findFirst().map(RnEntity::getRn).orElse(null);
        return rn == null ? pismeno.getVrstaPismenaNaziv() : pismeno.getVrstaPismenaNaziv() + " — " + rn;
    }
}
