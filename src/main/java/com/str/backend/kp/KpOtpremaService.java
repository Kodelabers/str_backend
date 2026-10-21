package com.str.backend.kp;

import com.str.backend.document.DocumentLabels;
import com.str.backend.document.StrDocumentType;
import com.str.backend.egop.EgopFilingStore;
import com.str.backend.egop.EgopPismenoEntity;
import com.str.backend.egop.EgopRetryPolicy;
import com.str.backend.kp.client.KpClient;
import com.str.backend.kp.client.KpOdgovor;
import com.str.backend.kp.client.KpPoruka;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Dostava izlaznih pismena u korisnički pretinac (KP) — izravno, preko Sustava za zaprimanje
 * poruka APIS IT-a. Vidi {@code docs/KP-INTEGRACIJA.md}.
 *
 * <p>Dva koraka, po pravilu „kreiramo lokalno pa šaljemo":
 * <ol>
 *   <li>{@link #register} — kad izlazno pismeno <b>nastane</b>, zapiše se otprema ({@code PENDING})
 *       s primateljem razriješenim u tom trenutku ({@link KpPrimatelj}) i vlastitim
 *       {@code IdPoruke}. Akt nastao dok je KP bio ugašen nema zapisa, pa se nikad ne šalje;</li>
 *   <li>{@link #otpremi} / {@link #retry} — kad je pismeno urudžbirano i ima dokument, ide poruka s
 *       aktom u privitku. Akt urudžbiran kasnije od {@code str.kp.max-akt-age} od nastanka ide u
 *       {@code SKIPPED}: zastarjeli akt ne smije stranci otvoriti rok.</li>
 * </ol>
 * Ishod slanja određuje šifra odbijanja ({@link KpGreske}): primatelj bez aktivnog pretinca dobiva
 * akt e-poštom ({@link KpZamjenskaDostava}), trajna greška se odmah iscrpljuje, a prolazna ide
 * u backoff. Status urudžbe se ne dira.
 *
 * <p>Radi samo uz uključen KP <b>i</b> eGOP: bez urudžbe akt nema klasu ni urudžbeni broj.
 */
@Service
public class KpOtpremaService {

    private static final Logger log = LoggerFactory.getLogger(KpOtpremaService.class);

    /**
     * Cijeli zahtjev, uključujući omotnicu, poslovnu poruku i privitke, najviše 3 MB (spec. 4.1,
     * 5.1; K001/Z006). Uzima se strože čitanje, 3 000 000 bajtova.
     */
    static final long MAX_PORUKA_BYTES = 3_000_000;
    /** Omotnica, zaglavlje i tekst poruke uz privitak — s velikom rezervom. */
    static final long OMOTNICA_REZERVA_BYTES = 20_000;
    /** PS shema: {@code SadrzajPrivitka} najviše 1 500 000 znakova Base64. */
    static final int MAX_PS_PDF_BASE64 = 1_500_000;
    /** FO shema: {@code Sadrzaj} najviše 2000 znakova (Base64), PS 5500 bajtova. */
    static final int MAX_FO_SADRZAJ_BASE64 = 2_000;

    private final ObjectProvider<KpClient> client;
    private final KpOtpremaStore store;
    private final EgopFilingStore filingStore;
    private final LessorRepository lessorRepository;
    private final RnRepository rnRepository;
    private final EgopRetryPolicy retryPolicy;
    private final KpExecutor executor;
    private final KpZamjenskaDostava zamjenska;
    private final DocumentLabels labels;
    private final KpProperties properties;
    private final boolean enabled;

    public KpOtpremaService(ObjectProvider<KpClient> client,
                            KpOtpremaStore store,
                            EgopFilingStore filingStore,
                            LessorRepository lessorRepository,
                            RnRepository rnRepository,
                            EgopRetryPolicy retryPolicy,
                            KpExecutor executor,
                            KpZamjenskaDostava zamjenska,
                            DocumentLabels labels,
                            KpProperties properties,
                            @Value("${hr.infodom.str.integration.egop.enabled:false}") boolean egopEnabled) {
        this.client = client;
        this.store = store;
        this.filingStore = filingStore;
        this.lessorRepository = lessorRepository;
        this.rnRepository = rnRepository;
        this.retryPolicy = retryPolicy;
        this.executor = executor;
        this.zamjenska = zamjenska;
        this.labels = labels;
        this.properties = properties;
        this.enabled = properties.enabled() && egopEnabled;
        if (properties.enabled() && !egopEnabled) {
            log.warn("kp_disabled reason=egop_disabled — str.kp.enabled=true, ali eGOP je ugašen; bez urudžbe"
                    + " akt nema klasu ni urudžbeni broj, pa dostava u korisnički pretinac ne radi");
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
    public Optional<KpOtpremaEntity> register(EgopPismenoEntity pismeno, LessorEntity lessor, Instant aktNastaoAt) {
        if (!enabled || pismeno.getSmjer() != EgopPismenoEntity.Smjer.IZLAZNO) {
            return Optional.empty();
        }
        Optional<KpPrimatelj> primatelj = KpPrimatelj.resolve(lessor, properties.izvorRegistarOib());
        if (primatelj.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(store.register(pismeno.getId(), primatelj.get(), aktNastaoAt));
        } catch (RuntimeException e) {
            log.error("kp_not_registered pismeno={} rn={} vrsta='{}': {} — akt neće otići u KP",
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
                log.error("kp_not_registered reason=missing_lessor akt={} rn={}", akt.getId(), akt.getRn());
                return;
            }
            register(akt, lessor.get(), akt.getCreatedAt());
        } catch (RuntimeException e) {
            log.error("kp_not_registered akt={} rn={}: {} — akt neće otići u KP",
                    akt.getId(), akt.getRn(), e.getMessage(), e);
        }
    }

    /**
     * Otprema pismena ako je urudžbirano, a otprema zapisana i još nije dovršena. Idempotentno.
     *
     * <p><b>Asinkrono</b> ({@link KpExecutor}): zove se iz toka urudžbe, koji ide na niti zahtjeva
     * (izdavanje RB-a, opoziv, povlačenje), a dostava u KP ne treba ništa što korisnik čeka u
     * odgovoru. Ako se zadatak izgubi (restart, pun red), otpremu pokupi {@code EgopRetryJob}.
     *
     * <p>Ne baca iznimke — u asinkronoj niti ih ionako nitko ne bi primio.
     */
    public void otpremi(UUID pismenoId) {
        if (!enabled) {
            return;
        }
        executor.submit(() -> {
            try {
                store.findByPismeno(pismenoId).filter(o -> !o.isDone()).ifPresent(this::send);
            } catch (RuntimeException e) {
                log.error("kp_error pismeno={}: {} — otpremu pokupi retry job", pismenoId, e.getMessage(), e);
            }
        });
    }

    /** Ulaz za {@code EgopRetryJob}, po id-u otpreme. */
    public void retry(UUID otpremaId) {
        if (!enabled) {
            return;
        }
        store.find(otpremaId).filter(o -> !o.isDone()).ifPresent(this::send);
    }

    private void send(KpOtpremaEntity otprema) {
        EgopPismenoEntity pismeno = filingStore.findAkt(otprema.getPismenoId()).orElse(null);
        if (pismeno == null) {
            log.error("kp_skipped reason=missing_pismeno otprema={} pismeno={}", otprema.getId(), otprema.getPismenoId());
            return;
        }
        if (!pismeno.isSynced() || !pismeno.isFiled()) {
            // Urudžba još nije dovršena — otpremu pokrene njezin uspjeh ili retry job.
            log.debug("kp_waiting reason=pismeno_not_synced otprema={} pismeno={}", otprema.getId(), pismeno.getId());
            return;
        }
        Instant now = Instant.now();
        if (otprema.getAktNastaoAt().plus(properties.maxAktAge()).isBefore(now)) {
            store.markSkipped(otprema.getId(), "zastario: akt nastao " + otprema.getAktNastaoAt()
                    + ", urudžbiran nakon više od " + properties.maxAktAge());
            log.warn("kp_skipped reason=stale otprema={} pismeno={} rn={} vrsta='{}' akt_nastao_at={}"
                            + " — akt se ne dostavlja u KP", otprema.getId(), pismeno.getId(), pismeno.getRn(),
                    pismeno.getVrstaPismenaNaziv(), otprema.getAktNastaoAt());
            return;
        }

        KpPoruka poruka;
        StrDocumentType vrsta;
        try {
            vrsta = StrDocumentType.fromVrstaPismenaNaziv(pismeno.getVrstaPismenaNaziv())
                    .orElseThrow(() -> new IllegalStateException("nepoznata vrsta pismena '"
                            + pismeno.getVrstaPismenaNaziv() + "'"));
            poruka = poruka(otprema, pismeno, vrsta, now);
        } catch (RuntimeException e) {
            // Akt koji se ne može složiti (nema PDF-a, prevelik, nema tipa poruke) — ponavljanje
            // ništa ne mijenja dok netko ne popravi podatke ili konfiguraciju.
            exhausted(otprema, pismeno, "STR", e.getMessage(), null);
            return;
        }

        KpOdgovor odgovor;
        try {
            odgovor = client.getObject().posalji(poruka);
        } catch (RuntimeException e) {
            failed(otprema, pismeno, "TRANSPORT", e.getMessage(), null, e);
            return;
        }

        String sifre = String.join(",", odgovor.sifre());
        if (odgovor.ok()) {
            recordSent(otprema, pismeno, poruka, odgovor.obradeno() != null ? odgovor.obradeno() : now,
                    odgovor.gsbId(), "sent");
            return;
        }
        switch (KpGreske.classify(odgovor.sifre())) {
            case DUPLIKAT -> recordSent(otprema, pismeno, poruka, now, odgovor.gsbId(), "duplicate");
            case NEMA_PRETINCA -> nemaPretinca(otprema, pismeno, sifre, odgovor);
            case TRAJNA -> exhausted(otprema, pismeno, sifre, odgovor.opis(), odgovor.gsbId());
            case PROLAZNA -> failed(otprema, pismeno, sifre, odgovor.opis(), odgovor.gsbId(), null);
        }
    }

    /** Poruka s aktom u privitku. Baca iznimku ako se akt ne može poslati kakav jest. */
    KpPoruka poruka(KpOtpremaEntity otprema, EgopPismenoEntity pismeno, StrDocumentType vrsta, Instant now) {
        byte[] pdf = pismeno.getPdfContent();
        if (pdf == null || pdf.length == 0) {
            throw new IllegalStateException("pismeno nema pohranjen PDF");
        }
        if (velicinaZahtjeva(pdf.length) > MAX_PORUKA_BYTES || (otprema.getPrimateljTip() == KpPrimatelj.Tip.PS
                && base64Length(pdf.length) > MAX_PS_PDF_BASE64)) {
            throw new IllegalStateException("PDF akta je prevelik za KP (" + pdf.length + " B, pakiranje "
                    + properties.encoding() + ")");
        }
        String rn = rn(pismeno);
        String sadrzaj = labels.format("kp.sadrzaj", vrsta.vrstaPismenaNaziv(), rn == null ? "—" : rn);
        if (otprema.getPrimateljTip() == KpPrimatelj.Tip.FO
                && base64Length(sadrzaj.getBytes(StandardCharsets.UTF_8).length) > MAX_FO_SADRZAJ_BASE64) {
            throw new IllegalStateException("tekst poruke je predug za KP FO");
        }
        String klasa = filingStore.findSubmission(pismeno.getSubmissionId())
                .map(SubmissionEntity::getEgopKlasa).orElse(null);
        return new KpPoruka(
                otprema.primatelj(),
                otprema.getIdPoruke(),
                properties.tipPoruke(otprema.getPrimateljTip(), vrsta),
                properties.naslov(vrsta),
                sadrzaj,
                klasa,
                pismeno.getUrBroj(),
                // Samo [a-zA-Z0-9 _.-] (FileNameType) — slug i RB to već jesu.
                vrsta.slug() + (rn == null ? "" : "-" + rn) + ".pdf",
                pdf,
                // JIP: zasad naš id pismena; MINT može odlučiti za oznaku iz eGOP-a.
                pismeno.getId().toString(),
                now);
    }

    private void recordSent(KpOtpremaEntity otprema, EgopPismenoEntity pismeno, KpPoruka poruka, Instant sentAt,
                            String gsbId, String ishod) {
        try {
            store.markSent(otprema.getId(), sentAt, poruka.tipPoruke(), properties.isPravniUcinak(), gsbId);
            log.info("kp_sent ishod={} otprema={} pismeno={} rn={} vrsta='{}' primatelj_tip={} tip={} urbroj={}"
                            + " gsb_id={}", ishod, otprema.getId(), pismeno.getId(), pismeno.getRn(),
                    pismeno.getVrstaPismenaNaziv(), otprema.getPrimateljTip(), poruka.tipPoruke(),
                    pismeno.getUrBroj(), gsbId);
        } catch (RuntimeException e) {
            // Poruka JE u pretincu. Retry je pošalje ponovo s istim IdPoruke i dobije duplikat,
            // koji se također bilježi kao poslano — nema dvostruke dostave.
            log.error("kp_sent_not_recorded otprema={} pismeno={} rn={}: {} — poruka je u pretincu,"
                            + " retry će dobiti duplikat (K009/Z012)",
                    otprema.getId(), pismeno.getId(), pismeno.getRn(), e.getMessage(), e);
        }
    }

    /**
     * Primatelj nema aktivan pretinac: konačno za KP, a akt mu ide e-poštom (odluka 21. 10. 2026.).
     */
    private void nemaPretinca(KpOtpremaEntity otprema, EgopPismenoEntity pismeno, String sifre, KpOdgovor odgovor) {
        store.markNemaPretinca(otprema.getId(), sifre, odgovor.opis(), odgovor.gsbId());
        log.warn("kp_nema_pretinca otprema={} pismeno={} rn={} vrsta='{}' primatelj_tip={} sifre={}"
                        + " — akt ide e-poštom", otprema.getId(), pismeno.getId(), pismeno.getRn(),
                pismeno.getVrstaPismenaNaziv(), otprema.getPrimateljTip(), sifre);
        zamjenska.dostavi(otprema.getId());
    }

    private void failed(KpOtpremaEntity otprema, EgopPismenoEntity pismeno, String sifre, String opis,
                        String gsbId, Exception cause) {
        Instant nextAttemptAt = retryPolicy.nextAttemptAt(Instant.now(), otprema.getAttempts());
        int attempts = store.markFailed(otprema.getId(), sifre, opis, nextAttemptAt, gsbId);
        if (retryPolicy.isExhausted(attempts)) {
            // Bez automatske e-pošte: prekid rada SZP-a nije isto što i „primatelj nema KP", a
            // poruka je možda i stigla. Rješava se ručno.
            log.error("kp_retry_exhausted otprema={} pismeno={} rn={} vrsta='{}' attempts={} sifre={} last_error={}"
                            + " — akt se više neće automatski slati u KP", otprema.getId(), pismeno.getId(),
                    pismeno.getRn(), pismeno.getVrstaPismenaNaziv(), attempts, sifre, opis);
        } else {
            log.error("kp_failed otprema={} pismeno={} rn={} vrsta='{}' attempt={} next_attempt_at={} sifre={}: {}",
                    otprema.getId(), pismeno.getId(), pismeno.getRn(), pismeno.getVrstaPismenaNaziv(), attempts,
                    nextAttemptAt, sifre, opis, cause);
        }
    }

    private void exhausted(KpOtpremaEntity otprema, EgopPismenoEntity pismeno, String sifre, String opis,
                           String gsbId) {
        store.markExhausted(otprema.getId(), sifre, opis, retryPolicy.maxAttempts(), gsbId);
        log.error("kp_config_error otprema={} pismeno={} rn={} vrsta='{}' primatelj_tip={} sifre={}: {}"
                        + " — trajna greška; nakon ispravka otpremu vratiti ručno (attempts = 0)",
                otprema.getId(), pismeno.getId(), pismeno.getRn(), pismeno.getVrstaPismenaNaziv(),
                otprema.getPrimateljTip(), sifre, opis);
    }

    /** Registracijska pismena nemaju RB na sebi ({@code rn} je prazan), pa se čita s submissiona. */
    private String rn(EgopPismenoEntity pismeno) {
        if (pismeno.getRn() != null) {
            return pismeno.getRn();
        }
        List<RnEntity> rns = rnRepository.findBySubmissionId(pismeno.getSubmissionId());
        return rns.stream().findFirst().map(RnEntity::getRn).orElse(null);
    }

    /**
     * Procjena veličine zahtjeva za PDF od {@code pdfBytes}. Privitak putuje kao Base64 (×4/3); uz
     * {@code data-encoding=BASE64} cijela poslovna poruka, s privitkom, kodira se još jednom.
     */
    long velicinaZahtjeva(long pdfBytes) {
        long poslovnaPoruka = base64Length(pdfBytes) + OMOTNICA_REZERVA_BYTES;
        return properties.encoding() == KpProperties.DataEncoding.BASE64
                ? base64Length(poslovnaPoruka)
                : poslovnaPoruka;
    }

    private static long base64Length(long bytes) {
        return 4 * ((bytes + 2) / 3);
    }
}
