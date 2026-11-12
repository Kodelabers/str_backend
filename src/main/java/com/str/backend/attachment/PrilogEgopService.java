package com.str.backend.attachment;

import com.str.backend.attachment.PrilogRepository.PrilogSazetak;
import com.str.backend.common.Uuids;
import com.str.backend.common.VrstaDatoteke;
import com.str.backend.document.StrDocumentType;
import com.str.backend.egop.EgopAktKorisnik;
import com.str.backend.egop.EgopClient;
import com.str.backend.egop.EgopFilingStore;
import com.str.backend.egop.EgopNaziv;
import com.str.backend.egop.EgopPismenoEntity;
import com.str.backend.egop.EgopPrilozi;
import com.str.backend.egop.EgopRetryPolicy;
import com.str.backend.egop.exception.EgopBadRequestException;
import com.str.backend.egop.exception.EgopException;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import hr.infodom.egov.pismeno.DohvatiListuPrilogaPismena;
import hr.infodom.egov.pismeno.KreirajPrilogIPridruziDokument;
import hr.infodom.egov.pismeno.PriloziPismenaInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Prilaže priloge uz pismeno u eGOP-u ({@code KreirajPrilogIPridruziDokument}: novi prilog pismena
 * s elektroničkim dokumentom, jedan poziv po prilogu). Zove ga {@code EgopFilingService}:
 *
 * <ul>
 *   <li>ulazno pismeno zahtjeva ({@code act_ref=REGISTRACIJA}) ← prilozi {@link VrstaVlasnika#SUBMISSION};</li>
 *   <li>pismeno prijelaza RB-a ({@code act_ref=log_id}) ← prilozi {@link VrstaVlasnika#RN_LOG}, i to
 *       ulazno (podnesak stranke, npr. opoziv) samo prilozi stranke, a izlazno (akt) samo prilozi
 *       službenika ({@link PrilogService#pripadaPismenu});</li>
 *   <li>ulazno pismeno očitovanja ili prigovora ({@code act_ref=PRIGOVOR:<objection_id>}) ← dokument koji je
 *       stranka učitala kao prilog 1 ({@link #prilogIzvornika}, iz toka urudžbe), pa prilozi
 *       {@link VrstaVlasnika#OBJECTION} od rbr 2.</li>
 * </ul>
 *
 * <p><b>Akt ne čeka na priloge</b> ({@link EgopPrilozi}): prvi pokušaj ide nakon glavnog dokumenta,
 * a neuspjeh se bilježi na prilogu (broj pokušaja, sljedeći pokušaj, greška — changeset 149) po
 * {@link EgopRetryPolicy}, istoj kao urudžba. Ponavlja ga {@code EgopRetryJob} ({@link #ponovi});
 * iscrpljen prilog daje ERROR {@code egop_prilog_retry_exhausted}.
 *
 * <p>Prilozi se <b>ne pečate</b> — pečatira se samo akt. Idempotentno po prilogu, u dva sloja:
 * priloženi prilog ima {@code egop_jop} pismena i preskače se (oznaka se upisuje odmah nakon
 * uspješnog poziva, {@code REQUIRES_NEW}); a prije slanja se eGOP pita koje priloge pismeno već ima
 * ({@code DohvatiListuPrilogaPismena}) i prilog s istim {@code rbrPriloga} se preuzima umjesto
 * ponovnog slanja — za slučaj da je poziv prošao, a odgovor ili upis oznake se izgubio.
 * {@code rbrPriloga} je redni broj priloga na pismenu (1, 2, …) po redoslijedu učitavanja, pa je
 * isti i na ponovljenom pokušaju; zato je on ključ preuzimanja.
 *
 * <p><b>Bez transakcije</b> preko SOAP poziva — kao i ostatak eGOP toka.
 */
@Service
public class PrilogEgopService implements EgopPrilozi {

    private static final Logger log = LoggerFactory.getLogger(PrilogEgopService.class);

    private static final String ZAHTJEV_NAZIV = StrDocumentType.ZAHTJEV.vrstaPismenaNaziv();
    /** {@code rbrPriloga} izvornog dokumenta podnositelja očitovanja ili prigovora. */
    static final int RBR_IZVORNIKA = 1;
    /** Stupac {@code pismeno_prilog.egop_greska}. */
    private static final int GRESKA_MAX = 1000;

    private final PrilogRepository repository;
    private final EgopClient egopClient;
    private final EgopRetryPolicy retryPolicy;
    private final EgopFilingStore filingStore;
    private final EgopAktKorisnik korisnik;
    private final RnRepository rnRepository;
    private final String vrstaSifra;
    private final String vrstaNaziv;
    private final Duration grace;
    private final int batchSize;
    private final boolean egopEnabled;

    public PrilogEgopService(PrilogRepository repository,
                             EgopClient egopClient,
                             EgopRetryPolicy retryPolicy,
                             EgopFilingStore filingStore,
                             EgopAktKorisnik korisnik,
                             RnRepository rnRepository,
                             // Šifra vrste priloga u eGOP šifrarniku (MDM DohvatiVrstePrilogaActive);
                             // bez nje se razrješava po nazivu, kao vrste pismena.
                             @Value("${str.egop.prilog.vrsta-sifra:}") String vrstaSifra,
                             @Value("${str.egop.prilog.vrsta-naziv:Prilog}") String vrstaNaziv,
                             @Value("${str.egop.retry.grace:PT5M}") Duration grace,
                             @Value("${str.egop.retry.batch-size:50}") int batchSize,
                             @Value("${hr.infodom.str.integration.egop.enabled:false}") boolean egopEnabled) {
        this.repository = repository;
        this.egopClient = egopClient;
        this.retryPolicy = retryPolicy;
        this.filingStore = filingStore;
        this.korisnik = korisnik;
        this.rnRepository = rnRepository;
        this.vrstaSifra = vrstaSifra;
        this.vrstaNaziv = vrstaNaziv;
        this.grace = grace;
        this.batchSize = batchSize;
        this.egopEnabled = egopEnabled;
    }

    /**
     * Vrsta priloga se inače razrješava tek kod prvog priloga — pa bi kriva {@code EGOP_SIFRA_PRILOGA}
     * odnosno {@code EGOP_NAZIV_PRILOGA} izašla na vidjelo tek kao iscrpljeni prilozi, sate nakon
     * prvog akta. Uz uključen eGOP provjerava se odmah nakon dizanja. <b>Nikad ne obara start</b>
     * (kao {@code StartupDiagnostics}): bez vrste priloga akti i dalje idu, samo bez priloga.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void provjeriVrstuPriloga() {
        if (!egopEnabled) {
            return;
        }
        try {
            log.info("startup_egop_prilog vrsta_id={} vrsta_sifra='{}' vrsta_naziv='{}'", vrstaPriloga(),
                    vrstaSifra, vrstaNaziv);
        } catch (EgopException | RuntimeException e) {
            log.error("startup_egop_prilog_unresolved vrsta_sifra='{}' vrsta_naziv='{}': {} — akti se urudžbiraju i"
                            + " otpremaju BEZ priloga, a prilozi se iscrpe u retryju (egop_prilog_retry_exhausted);"
                            + " postaviti EGOP_SIFRA_PRILOGA ili ispravan EGOP_NAZIV_PRILOGA",
                    vrstaSifra, vrstaNaziv, e.getMessage());
        }
    }

    @Override
    public void prilozi(EgopPismenoEntity pismeno, String korisnik) {
        try {
            prilozi(pismeno, korisnik, Instant.now());
        } catch (RuntimeException e) {
            // Zadnja brana: prilozi ne smiju srušiti tok urudžbe akta.
            log.error("egop_prilozi_error pismeno={} rn={}: {} — prilozi ostaju za EgopRetryJob",
                    pismeno.getId(), pismeno.getRn(), e.getMessage(), e);
        }
    }

    /**
     * Ulaz za {@code EgopRetryJob}: pismena čiji su prilozi pali ili nisu stigli krenuti. Ide kroz
     * isto {@link #prilozi}, pod korisnikom pod kojim je pismeno urudžbirano ({@link EgopAktKorisnik}).
     */
    @Override
    public void ponovi(Instant sada) {
        List<UUID> kandidati = repository.findPismenaZaPonovnoPrilaganje(retryPolicy.maxAttempts(),
                sada.minus(grace), sada, EgopPismenoEntity.ACT_REF_REGISTRACIJA, ZAHTJEV_NAZIV,
                EgopPismenoEntity.ACT_REF_PRIGOVOR_PREFIX);
        if (kandidati.isEmpty()) {
            return;
        }
        List<UUID> batch = kandidati.size() > batchSize ? kandidati.subList(0, batchSize) : kandidati;
        log.info("egop_prilog_retry_start candidates={} batch={}", kandidati.size(), batch.size());
        for (UUID pismenoId : batch) {
            try {
                filingStore.findAkt(pismenoId).ifPresent(p -> prilozi(p, korisnik.za(p), sada));
            } catch (RuntimeException e) {
                log.error("egop_prilog_retry_error pismeno={}: {}", pismenoId, e.getMessage(), e);
            }
        }
        log.info("egop_prilog_retry_done processed={}", batch.size());
    }

    private void prilozi(EgopPismenoEntity pismeno, String korisnik, Instant sada) {
        List<PrilogSazetak> prilozi = priloziPismena(pismeno);
        if (prilozi.isEmpty()) {
            return;
        }
        if (!pismeno.isFiled()) {
            // Tok urudžbe priloge zove tek nakon glavnog dokumenta, a retry samo za SYNCED pismena.
            log.error("egop_prilozi_skipped reason=pismeno_not_filed pismeno={} rn={}", pismeno.getId(), pismeno.getRn());
            return;
        }
        int jop = pismeno.getJop();
        List<Redni> zaSlanje = new ArrayList<>();
        int rbr = prviRbr(pismeno) - 1;
        for (PrilogSazetak prilog : prilozi) {
            rbr++;
            if (!Objects.equals(prilog.getEgopJop(), jop) && dospio(prilog, sada)) {
                zaSlanje.add(new Redni(rbr, prilog));
            }
        }
        if (zaSlanje.isEmpty()) {
            return;
        }

        int vrstaPriloga;
        Map<Integer, PriloziPismenaInfo> uEgopu;
        try {
            vrstaPriloga = vrstaPriloga();
            uEgopu = priloziUEgopu(jop, korisnik);
        } catch (EgopException | RuntimeException e) {
            // Bez popisa priloga iz eGOP-a ne šalje se naslijepo — mogao bi nastati duplikat.
            zaSlanje.forEach(r -> neuspjeh(pismeno, r, e, sada));
            return;
        }

        int prilozeno = 0;
        for (Redni r : zaSlanje) {
            PriloziPismenaInfo postojeci = uEgopu.get(r.rbr());
            if (postojeci != null) {
                preuzmi(pismeno, r, postojeci, sada);
                continue;
            }
            try {
                KreirajPrilogIPridruziDokument request = new KreirajPrilogIPridruziDokument();
                request.setUserName(korisnik);
                request.setJop(jop);
                request.setRbrPriloga(r.rbr());
                request.setIdVrstaPriloga(vrstaPriloga);
                request.setNazivPriloga(r.prilog().getNazivDatoteke());
                request.setExtension(ekstenzija(r.prilog()));
                request.setAttachment(repository.findSadrzaj(r.prilog().getId()));
                egopClient.kreirajPrilogIPridruziDokument(request);
            } catch (EgopException | RuntimeException e) {
                neuspjeh(pismeno, r, e, sada);
                continue;
            }
            // Izvan try bloka: prilog je u eGOP-u, pa pad upisa ne smije postati neuspjeh slanja.
            // Neupisani prilog ostaje kandidat, a retry ga preuzme po rbrPriloga, ne šalje ponovo.
            oznaciPrilozen(pismeno, r, jop);
            prilozeno++;
        }
        if (prilozeno > 0) {
            log.info("egop_prilozi_ok pismeno={} jop={} vrsta='{}' prilozeno={} ukupno={}",
                    pismeno.getId(), jop, pismeno.getVrstaPismenaNaziv(), prilozeno, prilozi.size());
        }
    }

    /** Prilog s rednim brojem na pismenu — {@code rbrPriloga} je položaj među svim prilozima pismena. */
    private record Redni(int rbr, PrilogSazetak prilog) {}

    /**
     * {@code rbrPriloga} prvog priloga iz {@code pismeno_prilog}. Podnesak s PDF-om podneska kao glavnim
     * dokumentom ima na rbr 1 izvornik podnositelja ({@link #prilogIzvornika}), pa ostale datoteke počinju od
     * 2. Podnesci zapisani prije PDF-a podneska (glavni dokument bio je izvornik) i sva ostala pismena: od 1.
     */
    private static int prviRbr(EgopPismenoEntity pismeno) {
        return pismeno.objectionId().isPresent() && pismeno.getPdfContent() != null ? RBR_IZVORNIKA + 1 : 1;
    }

    @Override
    public void prilogIzvornika(EgopPismenoEntity pismeno, String korisnik, String naziv, String ekstenzija,
                                byte[] sadrzaj) throws EgopException {
        if (!pismeno.isFiled()) {
            throw new IllegalStateException("Pismeno " + pismeno.getId() + " nije kreirano u eGOP-u");
        }
        int jop = pismeno.getJop();
        PriloziPismenaInfo postojeci = priloziUEgopu(jop, korisnik).get(RBR_IZVORNIKA);
        if (postojeci != null) {
            if (!postojeci.isPostojiEDokument()) {
                throw new EgopBadRequestException("eGOP već ima prilog rbr " + RBR_IZVORNIKA
                        + " bez elektroničkog dokumenta — provjeriti pismeno " + jop + " u eGOP-u");
            }
            log.info("egop_prilog_izvornik_adopted pismeno={} jop={} naziv='{}' egop_naziv='{}' — već je u eGOP-u,"
                    + " ne šalje se ponovo", pismeno.getId(), jop, naziv, postojeci.getNazivPriloga());
            return;
        }
        KreirajPrilogIPridruziDokument request = new KreirajPrilogIPridruziDokument();
        request.setUserName(korisnik);
        request.setJop(jop);
        request.setRbrPriloga(RBR_IZVORNIKA);
        request.setIdVrstaPriloga(vrstaPriloga());
        request.setNazivPriloga(naziv);
        request.setExtension(ekstenzija);
        request.setAttachment(sadrzaj);
        egopClient.kreirajPrilogIPridruziDokument(request);
        log.info("egop_prilog_izvornik_ok pismeno={} jop={} vrsta='{}' naziv='{}' ekstenzija={}", pismeno.getId(), jop,
                pismeno.getVrstaPismenaNaziv(), naziv, ekstenzija);
    }

    /** Nije iscrpljen i došao mu je red (ili još nije ni pokušan). */
    private boolean dospio(PrilogSazetak prilog, Instant sada) {
        if (retryPolicy.isExhausted(prilog.getEgopPokusaji())) {
            return false;
        }
        Instant sljedeci = prilog.getEgopSljedeciPokusajU();
        return sljedeci == null || !sljedeci.isAfter(sada);
    }

    /** Prilozi koje pismeno u eGOP-u već ima, po {@code rbrPriloga}. */
    private Map<Integer, PriloziPismenaInfo> priloziUEgopu(int jop, String korisnik) throws EgopException {
        DohvatiListuPrilogaPismena request = new DohvatiListuPrilogaPismena();
        request.setUserName(korisnik);
        request.setJop(jop);
        Map<Integer, PriloziPismenaInfo> poRbr = new HashMap<>();
        for (PriloziPismenaInfo info : egopClient.dohvatiListuPrilogaPismena(request)) {
            poRbr.putIfAbsent(info.getRbrPriloga(), info);
        }
        return poRbr;
    }

    /**
     * Prilog s našim {@code rbrPriloga} već postoji: raniji poziv je prošao, a oznaka se nije upisala.
     * Preuzima se bez ponovnog slanja. Prilog bez elektroničkog dokumenta nije dovršen — ponovno slanje
     * pod istim rbr bi se sudarilo s njim, pa se bilježi kao neuspjeh i traži ručnu provjeru.
     */
    private void preuzmi(EgopPismenoEntity pismeno, Redni r, PriloziPismenaInfo postojeci, Instant sada) {
        if (!postojeci.isPostojiEDokument()) {
            neuspjeh(pismeno, r, new EgopBadRequestException("eGOP već ima prilog rbr " + r.rbr()
                    + " bez elektroničkog dokumenta — provjeriti pismeno u eGOP-u"), sada);
            return;
        }
        if (!r.prilog().getNazivDatoteke().equalsIgnoreCase(Objects.toString(postojeci.getNazivPriloga(), "").strip())) {
            log.warn("egop_prilog_adopted_name_mismatch pismeno={} prilog={} rbr={} naziv='{}' egop_naziv='{}'",
                    pismeno.getId(), r.prilog().getId(), r.rbr(), r.prilog().getNazivDatoteke(),
                    postojeci.getNazivPriloga());
        }
        oznaciPrilozen(pismeno, r, pismeno.getJop());
        log.info("egop_prilog_adopted pismeno={} jop={} prilog={} rbr={} — već je u eGOP-u, ne šalje se ponovo",
                pismeno.getId(), pismeno.getJop(), r.prilog().getId(), r.rbr());
    }

    private void oznaciPrilozen(EgopPismenoEntity pismeno, Redni r, int jop) {
        try {
            repository.markEgopPrilozeno(r.prilog().getId(), jop, r.rbr(), Instant.now());
        } catch (RuntimeException e) {
            log.error("egop_prilog_not_recorded pismeno={} prilog={} rbr={}: {} — prilog JE u eGOP-u; retry ga"
                            + " preuzima preko DohvatiListuPrilogaPismena i ne šalje ponovo",
                    pismeno.getId(), r.prilog().getId(), r.rbr(), e.getMessage(), e);
        }
    }

    private void neuspjeh(EgopPismenoEntity pismeno, Redni r, Exception cause, Instant sada) {
        int dosad = r.prilog().getEgopPokusaji();
        Instant sljedeci = retryPolicy.nextAttemptAt(sada, dosad);
        String greska = greska(cause);
        try {
            repository.markEgopNeuspjeh(r.prilog().getId(), greska, sljedeci);
        } catch (RuntimeException e) {
            log.error("egop_prilog_failure_not_recorded prilog={}: {}", r.prilog().getId(), e.getMessage(), e);
        }
        int pokusaji = dosad + 1;
        if (retryPolicy.isExhausted(pokusaji)) {
            log.error("egop_prilog_retry_exhausted rn={} submission={} pismeno={} jop={} vrsta='{}' prilog={} rbr={}"
                            + " naziv='{}' attempts={} last_error={} — prilog NIJE u eGOP-u ni u korisničkom pretincu"
                            + " i više se ne pokušava automatski; priložiti ručno ili vratiti u red"
                            + " (pismeno_prilog.egop_pokusaji = 0)",
                    rn(pismeno), pismeno.getSubmissionId(), pismeno.getId(), pismeno.getJop(),
                    pismeno.getVrstaPismenaNaziv(), r.prilog().getId(), r.rbr(), r.prilog().getNazivDatoteke(),
                    pokusaji, greska);
        } else {
            log.error("egop_prilog_failed rn={} pismeno={} jop={} prilog={} rbr={} attempt={} next_attempt_at={}: {}"
                            + " — akt ide dalje bez ovog priloga, ponavlja ga EgopRetryJob",
                    rn(pismeno), pismeno.getId(), pismeno.getJop(), r.prilog().getId(), r.rbr(), pokusaji, sljedeci,
                    greska);
        }
    }

    /** Pismena registracije nemaju RB na sebi — čita se sa submissiona, samo za log. */
    private String rn(EgopPismenoEntity pismeno) {
        if (pismeno.getRn() != null) {
            return pismeno.getRn();
        }
        try {
            return rnRepository.findBySubmissionId(pismeno.getSubmissionId()).stream()
                    .findFirst().map(RnEntity::getRn).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String greska(Exception cause) {
        String poruka = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        return poruka.length() > GRESKA_MAX ? poruka.substring(0, GRESKA_MAX) : poruka;
    }

    /** Prilozi koji idu uz ovo pismeno; prazno za pismena bez priloga (npr. obavijest o dodjeli). */
    List<PrilogSazetak> priloziPismena(EgopPismenoEntity pismeno) {
        if (EgopPismenoEntity.ACT_REF_REGISTRACIJA.equals(pismeno.getActRef())) {
            return pismeno.getSmjer() == EgopPismenoEntity.Smjer.ULAZNO
                    && ZAHTJEV_NAZIV.equals(pismeno.getVrstaPismenaNaziv())
                    ? repository.findSazeciVlasnika(VrstaVlasnika.SUBMISSION, pismeno.getSubmissionId())
                    : List.of();
        }
        // Očitovanje i prigovor: sve ostale datoteke koje je stranka predala uz podnesak (njezin glavni
        // dokument ide zasebno, prilogIzvornika). Učitava ih samo stranka, pa filtra po autoru nema.
        Optional<UUID> objectionId = pismeno.objectionId();
        if (objectionId.isPresent()) {
            return repository.findSazeciVlasnika(VrstaVlasnika.OBJECTION, objectionId.get());
        }
        Optional<UUID> logId = Uuids.parse(pismeno.getActRef());
        if (logId.isEmpty()) {
            return List.of();
        }
        boolean izlazno = pismeno.getSmjer() == EgopPismenoEntity.Smjer.IZLAZNO;
        return repository.findSazeciVlasnika(VrstaVlasnika.RN_LOG, logId.get()).stream()
                .filter(p -> PrilogService.pripadaPismenu(p, izlazno))
                .toList();
    }

    private int vrstaPriloga() throws EgopBadRequestException {
        if (vrstaSifra != null && !vrstaSifra.isBlank()) {
            try {
                return Integer.parseInt(vrstaSifra.trim());
            } catch (NumberFormatException e) {
                throw new EgopBadRequestException("str.egop.prilog.vrsta-sifra nije broj: '" + vrstaSifra + "'");
            }
        }
        Integer id = EgopNaziv.resolveId(egopClient.getVrstePriloga(), vrstaNaziv);
        if (id == null) {
            throw new EgopBadRequestException("eGOP šifrarnik nema vrstu priloga '" + vrstaNaziv
                    + "' — zadati str.egop.prilog.vrsta-sifra ili ispravan str.egop.prilog.vrsta-naziv");
        }
        return id;
    }

    /** {@code content_type} je uvijek iz {@link VrstaDatoteke} (određen pri učitavanju). */
    private static String ekstenzija(PrilogSazetak prilog) {
        return VrstaDatoteke.poContentType(prilog.getContentType())
                .map(VrstaDatoteke::ekstenzija)
                .orElseThrow(() -> new IllegalStateException("Prilog " + prilog.getId()
                        + " ima nepoznat content_type '" + prilog.getContentType() + "'"));
    }
}
