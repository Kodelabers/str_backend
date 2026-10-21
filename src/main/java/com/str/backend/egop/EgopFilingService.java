package com.str.backend.egop;

import com.str.backend.document.FilingReference;
import com.str.backend.document.StrDocumentType;
import com.str.backend.domain.EgopSyncStatus;
import com.str.backend.egop.codebook.VrstaUpisneKnjige;
import com.str.backend.egop.exception.EgopBadRequestException;
import com.str.backend.egop.exception.EgopException;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.request.SubmissionEntity;
import hr.infodom.egov.pismeno.KreirajDokumentZaPismeno;
import hr.infodom.egov.pismeno.KreirajIzlaznoPismeno;
import hr.infodom.egov.pismeno.KreirajPismeno2;
import hr.infodom.egov.pismeno.PismenoBasicInfo2;
import hr.infodom.egov.pismeno.PismenoInfo;
import hr.infodom.egov.predmet.DohvatiPodatkePredmeta;
import hr.infodom.egov.predmet.KreirajPredmet2;
import hr.infodom.egov.predmet.OdrediRjesavatelja;
import hr.infodom.egov.predmet.PredmetBasicInfo2;
import hr.infodom.egov.predmet.PredmetInfo;
import hr.infodom.egov.subjekt.DohvatiPodatkeSubjekta;
import hr.infodom.egov.subjekt.KreirajSubjekta;
import hr.infodom.egov.subjekt.SubjektInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Urudžbiranje registracije u eGOP (pisarnica MINT-a), po toku dogovorenom s
 * InfoDomom (mail 22.07.2026 + referentni klijent):
 *
 * <ol>
 *   <li>subjekt (iznajmljivač) mora postojati u eGOP registru — lookup pa create;</li>
 *   <li>neupravni predmet vrste "Dodjela registracijskog STR broja" (klasa 334-06/10) — jedan po
 *       submissionu, provjeri postojanje pa kreiraj;</li>
 *   <li>ulazno pismeno (zahtjev) + PDF dokument — PDF se renderira tek nakon
 *       kreiranja pismena jer tada postoje KLASA i URBROJ;</li>
 *   <li>izlazno pismeno (obavijest o dodjeli) + PDF dokument;</li>
 *   <li>otprema izlaznog pismena u korisnički pretinac ({@link EgopKpOtpremaService}) — iza
 *       {@code str.egop.kp.enabled}, i bez utjecaja na ishod urudžbe.</li>
 * </ol>
 *
 * Napredak se vodi kroz {@link EgopSyncStatus} na submissionu; svaki dovršeni korak
 * se odmah sprema pa ponovni pokušaj nastavlja gdje je stao (eGOP nema idempotency
 * ključeve — provjera postojanja + spremljeni identifikatori su jedina zaštita od
 * duplikata).
 *
 * <p><b>Transakcije:</b> ova klasa se namjerno <i>ne</i> izvodi unutar transakcije.
 * Sav upis ide kroz {@link EgopFilingStore}, gdje svaki korak ima vlastitu kratku
 * transakciju — inače bi jedna transakcija stajala otvorena kroz cijeli lanac SOAP
 * poziva (do ~7 poziva × read timeout).
 */
@Service
public class EgopFilingService {

    private static final Logger log = LoggerFactory.getLogger(EgopFilingService.class);

    private final EgopClient egopClient;
    private final EgopFilingStore store;
    private final EgopVrstaResolver vrste;
    private final EgopKpOtpremaService kpOtprema;

    private final String appUsername;
    private final String rjesavatelj;
    private final VrstaUpisneKnjige upisnaKnjiga;

    /**
     * Nazivi vrsta pismena registracijskog toka. Dolaze iz {@link StrDocumentType} jer je
     * naziv ono što se zapisuje na {@code egop_pismeno}; zamjena eGOP <i>šifre</i> ide kroz
     * {@link EgopVrstaResolver}, ne kroz naziv.
     */
    private static final String ULAZNO_PISMENO_NAZIV = StrDocumentType.ZAHTJEV.vrstaPismenaNaziv();
    private static final String IZLAZNO_PISMENO_NAZIV = StrDocumentType.DODJELA.vrstaPismenaNaziv();

    public EgopFilingService(EgopClient egopClient,
                             EgopFilingStore store,
                             EgopVrstaResolver vrste,
                             EgopKpOtpremaService kpOtprema,
                             EgopProperties properties,
                             @Value("${str.egop.rjesavatelj:}") String rjesavatelj,
                             // Konfigurabilna jer posudbena vrsta predmeta ne mora biti dopuštena
                             // u neupravnoj knjizi — tada se bez rebuilda proba UP/I.
                             @Value("${str.egop.upisna-knjiga:NP}") String upisnaKnjiga) {
        this.egopClient = egopClient;
        this.store = store;
        this.vrste = vrste;
        this.kpOtprema = kpOtprema;
        this.appUsername = properties.qualifiedAppUsername();
        // InfoDom (26.07.2026): rješavatelj mora biti osoba s username-om; probna
        // vrijednost je servisni račun, a property dopušta zamjenu bez rebuilda.
        this.rjesavatelj = (rjesavatelj == null || rjesavatelj.isBlank())
                ? this.appUsername
                : rjesavatelj;
        this.upisnaKnjiga = VrstaUpisneKnjige.getByCode(upisnaKnjiga);
    }

    /**
     * Rezultat urudžbiranja: filing broj ("KLASA: ..., URBROJ: ...") i PDF zahtjeva
     * kakav je poslan u eGOP.
     */
    public record FilingResult(String filingNumber, byte[] zahtjevPdf) {}

    /**
     * Urudžbira registraciju u eGOP. {@code pdfSupplier} dobiva filing broj (KLASA +
     * URBROJ ulaznog pismena) i vraća renderirani PDF zahtjeva — redoslijed je bitan:
     * urudžbeni broj nastaje kreiranjem pismena i tek onda smije u PDF.
     *
     * <p>Sve promjene statusa se spremaju odmah; iznimka bilo kojeg koraka propagira
     * pozivatelju koji odlučuje o retryju (RN ostaje valjan bez obzira na ishod).
     */
    public FilingResult fileRegistration(SubmissionEntity submission, LessorEntity lessor,
                                         EgopDocumentSupplier documents) throws EgopException {
        Integer subjektOznaka = ensureSubjekt(lessor);
        advance(submission, EgopSyncStatus.SUBJEKT_OK);

        ensurePredmet(submission, lessor, subjektOznaka);
        advance(submission, EgopSyncStatus.PREDMET_OK);

        EgopPismenoEntity ulazno = ensurePismeno(submission, ULAZNO_PISMENO_NAZIV,
                EgopPismenoEntity.Smjer.ULAZNO, subjektOznaka, lessor);
        FilingReference ulaznaOznaka = filing(submission, ulazno);
        byte[] zahtjevPdf = documents.zahtjev(ulaznaOznaka);
        attachPdfOnce(ulazno, zahtjevPdf, appUsername);
        advance(submission, EgopSyncStatus.PISMENO_OK);

        // Smjer (ulazno/izlazno) ne šaljemo — eGOP ga određuje sam iz konfiguracije vrste
        // pismena; Smjer na našem entitetu je samo interna evidencija.
        // Otpremu u KP zapisuje ensurePismeno, kad pismeno nastane; šalje se na kraju ove metode.
        EgopPismenoEntity izlazno = ensurePismeno(submission, IZLAZNO_PISMENO_NAZIV,
                EgopPismenoEntity.Smjer.IZLAZNO, subjektOznaka, lessor);
        // Izlazno pismeno nosi vlastiti URBROJ, pa i vlastiti PDF — ranije se ovdje prilagala
        // kopija PDF-a zahtjeva, dokument s krivim urudžbenim brojem i krivim naslovom.
        attachPdfOnce(izlazno, documents.obavijestODodjeli(filing(submission, izlazno)), appUsername);

        // Na submission ide oznaka ULAZNOG pismena — to je akt kojim je postupak pokrenut.
        String filingNumber = formatFilingNumber(ulaznaOznaka);
        store.saveSuccess(submission.getSubmissionId(), filingNumber, zahtjevPdf);
        submission.applyEgopSyncStatus(EgopSyncStatus.SYNCED);
        log.info("egop_filing_ok submission={} klasa={} urbroj_ulazno={} urbroj_izlazno={}",
                submission.getSubmissionId(), submission.getEgopKlasa(), ulazno.getUrBroj(), izlazno.getUrBroj());

        // Dostava u korisnički pretinac (EU/OIB). Ne baca iznimke: pad otpreme ne mijenja ishod
        // urudžbe, a ponavlja ga EgopRetryJob.
        kpOtprema.otpremi(izlazno.getId());
        return new FilingResult(filingNumber, zahtjevPdf);
    }

    /** KLASA je zajednička svim pismenima predmeta, URBROJ i JOP su pojedinačni. */
    private static FilingReference filing(SubmissionEntity submission, EgopPismenoEntity pismeno) {
        return new FilingReference(submission.getEgopKlasa(), pismeno.getUrBroj(), pismeno.getJop());
    }

    /** Oblik koji se sprema na {@code submission.filing_number} i prikazuje korisniku. */
    public static String formatFilingNumber(FilingReference filing) {
        return "KLASA: " + filing.klasa() + ", URBROJ: " + filing.urBroj();
    }

    /**
     * Perzistira napredak i drži proslijeđeni (odspojeni) entitet u istom stanju —
     * pozivatelj ga nakon povratka i dalje čita, pa ne smije ostati zastario.
     */
    private void advance(SubmissionEntity submission, EgopSyncStatus status) {
        store.saveSyncStatus(submission.getSubmissionId(), status);
        submission.applyEgopSyncStatus(status);
    }

    /**
     * Subjekt (iznajmljivač) mora postojati u eGOP registru prije otvaranja predmeta
     * (greška -300 na KreirajPredmet2). Oznaka se trajno sprema na lessor.
     */
    private Integer ensureSubjekt(LessorEntity lessor) throws EgopException {
        if (lessor.getEgopSubjektOznaka() != null) {
            return lessor.getEgopSubjektOznaka();
        }

        // Paralelna registracija istog iznajmljivača mogla je oznaku već upisati.
        Optional<Integer> stored = store.readSubjektOznaka(lessor.getLessorId());
        if (stored.isPresent()) {
            lessor.assignEgopSubjekt(stored.get());
            return stored.get();
        }

        SubjektInfo existing = lookupSubjekt(lessor);
        int oznaka;
        if (existing != null && existing.isOperationSucceeded() && existing.getOznakaSubjekta() > 0) {
            oznaka = existing.getOznakaSubjekta();
        } else {
            oznaka = createSubjekt(lessor);
        }
        // Uvjetni upis: ako je paralelni tok bio brži, vrijedi njegova oznaka.
        int effective = store.storeSubjektOznaka(lessor.getLessorId(), oznaka);
        lessor.assignEgopSubjekt(effective);
        return effective;
    }

    private SubjektInfo lookupSubjekt(LessorEntity lessor) throws EgopException {
        DohvatiPodatkeSubjekta request = new DohvatiPodatkeSubjekta();
        request.setUserName(appUsername);
        if (lessor.getLessorOib() != null) {
            request.setOib(lessor.getLessorOib());
        } else if (lessor.getTaxNumber() != null) {
            // non-EU iznajmljivač nema OIB — strani porezni broj ide u mbJmbg
            request.setMbJmbg(lessor.getTaxNumber());
        } else {
            return null;
        }
        return egopClient.dohvatiPodatkeSubjekta(request);
    }

    private Integer createSubjekt(LessorEntity lessor) throws EgopException {
        KreirajSubjekta request = new KreirajSubjekta();
        request.setUserName(appUsername);
        request.setOib(lessor.getLessorOib());
        if (lessor.getLessorOib() == null) {
            request.setMbJmbg(lessor.getTaxNumber());
        }

        request.setTipOsobe(vrste.tipOsobe(lessor.isLegalEntityOwner()));

        String naziv = subjektNaziv(lessor);
        request.setNaziv(naziv);
        request.setKratkiNaziv(naziv);
        request.setAdresa(formatAdresa(lessor));
        // postanskiBroj: lessor ga ne pohranjuje (poznata rupa u modelu) — eGOP ga
        // po spec-u očekuje, pa se do proširenja registracijske forme šalje bez njega.

        SubjektInfo created = egopClient.kreirajSubjekta(request);
        if (!created.isOperationSucceeded() || created.getOznakaSubjekta() <= 0) {
            throw new EgopBadRequestException(
                    "KreirajSubjekta nije vratio oznaku subjekta za lessor " + lessor.getLessorId());
        }
        return created.getOznakaSubjekta();
    }

    /**
     * Predmet registracije (vrsta „Dodjela registracijskog STR broja", klasa 334-06/10) — jedan po
     * submissionu: u njega idu zahtjev (urbroj 1) i obavijest o dodjeli (urbroj 2). Akti nakon
     * dodjele imaju vlastiti predmet, vidi {@link #ensurePredmetAkti}.
     */
    private void ensurePredmet(SubmissionEntity submission, LessorEntity lessor, Integer subjektOznaka)
            throws EgopException {
        if (submission.getEgopUredskaGodina() != null && submission.getEgopRbrPredmeta() != null
                && predmetPostoji(submission.getEgopUredskaGodina(), submission.getEgopRbrPredmeta())) {
            return;
        }
        // Naziv predmeta ostaje naš i čitljiv i kad je šifra vrste posudbena — inače se u
        // urudžbenom zapisniku ne bi vidjelo o čemu je predmet.
        PredmetBasicInfo2 predmet = kreirajPredmet(lessor, subjektOznaka, vrste.vrstaPredmeta(),
                vrste.predmetNaziv() + " — " + identitet(lessor), appUsername);
        submission.applyEgopPredmet(predmet.getUredskaGodina(), predmet.getRbrPredmeta(),
                predmet.getKlasifikacijskaOznaka());
        store.savePredmet(submission.getSubmissionId(), predmet.getUredskaGodina(),
                predmet.getRbrPredmeta(), predmet.getKlasifikacijskaOznaka());

        odrediRjesavatelja(submission, predmet, appUsername, rjesavatelj);
    }

    /**
     * Predmet akata nakon dodjele (vrsta „Suspenzija, povlačenje i reaktiviranje…", klasa
     * 334-06/11) — jedan po RB-u, otvara ga prvi takav akt (odluka 21.10.2026.), pod korisnikom
     * pod kojim ide taj akt ({@link EgopAktKorisnik}).
     */
    private void ensurePredmetAkti(SubmissionEntity submission, LessorEntity lessor, Integer subjektOznaka,
                                   String korisnik, String rn) throws EgopException {
        if (submission.getEgopAktiUredskaGodina() != null && submission.getEgopAktiRbrPredmeta() != null
                && predmetPostoji(submission.getEgopAktiUredskaGodina(), submission.getEgopAktiRbrPredmeta())) {
            return;
        }
        PredmetBasicInfo2 predmet = kreirajPredmet(lessor, subjektOznaka, vrste.vrstaPredmetaAkti(),
                vrste.predmetAktiNaziv() + " — " + rn + " — " + identitet(lessor), korisnik);
        submission.applyEgopAktiPredmet(predmet.getUredskaGodina(), predmet.getRbrPredmeta(),
                predmet.getKlasifikacijskaOznaka());
        store.saveAktiPredmet(submission.getSubmissionId(), predmet.getUredskaGodina(),
                predmet.getRbrPredmeta(), predmet.getKlasifikacijskaOznaka());

        // Službenik je sam svoj rješavatelj; aplikacijski korisnik koristi zadanog, kao registracija.
        odrediRjesavatelja(submission, predmet, korisnik, korisnik.equals(appUsername) ? rjesavatelj : korisnik);
    }

    private PredmetBasicInfo2 kreirajPredmet(LessorEntity lessor, Integer subjektOznaka, String vrstaPredmeta,
                                             String naziv, String korisnik) throws EgopException {
        KreirajPredmet2 request = new KreirajPredmet2();
        request.setUserName(korisnik);
        request.setUpisnaKnjiga(upisnaKnjiga.getCode());
        request.setVrstaPredmeta(vrstaPredmeta);
        request.setNadleznaOrgJedinica(vrste.nadleznaOrgJedinica());
        request.setSubjektOznaka(subjektOznaka);
        request.setSubjektOIB(lessor.getLessorOib());
        request.setNazivPredmeta(naziv);
        request.setDatumOtvaranja(EgopDates.toXml(LocalDateTime.now()));
        return egopClient.kreirajPredmet2(request);
    }

    private String identitet(LessorEntity lessor) {
        return lessor.getLessorOib() != null
                ? subjektNaziv(lessor) + ", OIB " + lessor.getLessorOib()
                : subjektNaziv(lessor);
    }

    /**
     * Korisnik koji radi u predmetu mora biti nadležan za njega da bi smio kreirati pismena
     * (spec str. 30/50). Ostaje best-effort — predmet u ovom trenutku već postoji, pa bi
     * rušenje ostavilo siroče — ali pad se logira glasno: stvarna posljedica se vidi tek
     * kasnije, kod kreiranja pismena.
     */
    private void odrediRjesavatelja(SubmissionEntity submission, PredmetBasicInfo2 predmet, String korisnik,
                                    String rjesavateljPredmeta) {
        try {
            OdrediRjesavatelja request = new OdrediRjesavatelja();
            request.setUserName(korisnik);
            request.setUredskaGodina((short) predmet.getUredskaGodina());
            request.setRbrPredmeta(predmet.getRbrPredmeta());
            request.setRjesavatelj(rjesavateljPredmeta);
            egopClient.odrediRjesavatelja(request);
            log.info("egop_odredi_rjesavatelja_ok submission={} predmet={}/{} rjesavatelj={}",
                    submission.getSubmissionId(), predmet.getUredskaGodina(), predmet.getRbrPredmeta(),
                    rjesavateljPredmeta);
        } catch (EgopException e) {
            log.error("egop_odredi_rjesavatelja_failed submission={} predmet={}/{} rjesavatelj={}:"
                            + " {} — kreiranje pismena će vjerojatno pasti zbog nenadležnosti;"
                            + " probati drugu vrijednost u str.egop.rjesavatelj",
                    submission.getSubmissionId(), predmet.getUredskaGodina(), predmet.getRbrPredmeta(),
                    rjesavateljPredmeta, e.getMessage(), e);
        }
    }

    private boolean predmetPostoji(int uredskaGodina, int rbrPredmeta) throws EgopException {
        DohvatiPodatkePredmeta request = new DohvatiPodatkePredmeta();
        request.setUserName(appUsername);
        request.setUredskaGodina((short) uredskaGodina);
        request.setRbrPredmeta(rbrPredmeta);
        // -100 ("predmet ne postoji") prolazi kao validan odgovor s OperationSucceeded=false
        PredmetInfo predmet = egopClient.dohvatiPodatkePredmeta(request);
        return predmet != null && predmet.isOperationSucceeded();
    }

    /**
     * Uz novo izlazno pismeno zapisuje i otpremu u KP — jednom, kad pismeno nastane, a ne pri
     * svakom pokušaju. Akt je nastao kad i RB, pa se zastarjelost mjeri od nastanka submissiona.
     */
    private EgopPismenoEntity ensurePismeno(SubmissionEntity submission, String vrstaNaziv,
                                            EgopPismenoEntity.Smjer smjer, Integer subjektOznaka,
                                            LessorEntity lessor) throws EgopException {
        Optional<EgopPismenoEntity> existing = store.findPismeno(submission.getSubmissionId(),
                vrstaNaziv, EgopPismenoEntity.ACT_REF_REGISTRACIJA);
        if (existing.isPresent()) {
            return existing.get();
        }

        Kreirano kreirano = kreirajPismeno(submission.getEgopUredskaGodina(), submission.getEgopRbrPredmeta(),
                vrstaNaziv, smjer, subjektOznaka, lessor.getLessorOib(), appUsername);
        EgopPismenoEntity saved = store.savePismeno(EgopPismenoEntity.create(
                submission.getSubmissionId(), vrstaNaziv, smjer, kreirano.jop(), kreirano.urBroj(),
                kreirano.vrsta().sifra(), kreirano.vrsta().privremena()));
        if (smjer == EgopPismenoEntity.Smjer.IZLAZNO) {
            kpOtprema.register(saved, lessor, submission.getCreatedAt());
        }
        return saved;
    }

    /** JOP i urbroj novog pismena + šifra vrste pod kojom je stvarno urudžbirano. */
    private record Kreirano(int jop, String urBroj, EgopVrstaResolver.Vrsta vrsta) {}

    /**
     * Ulazno pismeno ide kroz {@code KreirajPismeno2} (sa subjektom), izlazno kroz
     * {@code KreirajIzlaznoPismeno}: {@code KreirajPismeno2} smjer ne prima, a eGOP ga ne izvodi
     * pouzdano iz vrste — na MINT-ovom testu je obavijest pod vrstom „Obavijest" postala ulazna i
     * otprema u KP je odbijena (21.10.2026.).
     */
    private Kreirano kreirajPismeno(int uredskaGodina, int rbrPredmeta, String vrstaNaziv,
                                    EgopPismenoEntity.Smjer smjer, Integer subjektOznaka, String subjektOib,
                                    String korisnik) throws EgopException {
        EgopVrstaResolver.Vrsta vrsta = vrste.vrstaPismena(vrstaNaziv);
        if (smjer == EgopPismenoEntity.Smjer.IZLAZNO) {
            KreirajIzlaznoPismeno request = new KreirajIzlaznoPismeno();
            request.setUserName(korisnik);
            request.setRbrSpisa(rbrPredmeta);
            request.setUredskaGodina((short) uredskaGodina);
            request.setVrstaPismena(vrsta.sifra());
            request.setNazivPismena(vrstaNaziv);
            request.setDatumNastanka(EgopDates.toXml(LocalDateTime.now()));
            PismenoInfo pismeno = egopClient.kreirajIzlaznoPismeno(request);
            log.debug("egop_izlazno_pismeno jop={} urbroj={} ui={}", pismeno.getJop(), pismeno.getUrBroj(),
                    pismeno.getUI());
            return new Kreirano(pismeno.getJop(), pismeno.getUrBroj(), vrsta);
        }
        KreirajPismeno2 request = new KreirajPismeno2();
        request.setUserName(korisnik);
        request.setRbrSpisa(rbrPredmeta);
        request.setUredskaGodina((short) uredskaGodina);
        request.setVrstaPismena(vrsta.sifra());
        request.setSubjektOznaka(subjektOznaka);
        request.setSubjektOIB(subjektOib);
        // Naziv pismena je NAŠ, ne naziv posudbene vrste — to je jedino mjesto na kojem se u
        // eGOP-u vidi o kojem je aktu riječ kad je šifra vrste privremena.
        request.setNazivPismena(vrstaNaziv);
        request.setDatumNastanka(EgopDates.toXml(LocalDateTime.now()));
        PismenoBasicInfo2 pismeno = egopClient.kreirajPismeno2(request);
        return new Kreirano(pismeno.getJop(), pismeno.getUrBroj(), vrsta);
    }

    /**
     * Konačni dokument akta nakon dodjele, s klasom predmeta akata i urudžbenim brojem pismena.
     * Prazno znači da vrijedi snimak iz trenutka događaja (stanje RB-a se u međuvremenu promijenilo).
     */
    @FunctionalInterface
    public interface AktDokument {
        Optional<byte[]> konacni(FilingReference filing);
    }

    /**
     * Urudžbira jedan akt nakon dodjele u predmet akata tog RB-a (klasa 334-06/11), pod
     * korisnikom koji je radnju napravio ({@link EgopAktKorisnik}).
     *
     * <p>Subjekt se osigurava i ovdje: ako je registracijsko urudžbiranje trajno palo, subjekt
     * u eGOP-u možda ne postoji.
     *
     * <p>Akt dolazi sa snimkom PDF-a iz trenutka događaja (prikaz stranci, preslika u mailu).
     * Klasa predmeta akata i urbroj poznati su tek nakon {@code KreirajPismeno2}, pa se prije
     * prilaganja renderira konačni dokument s njima ({@code dokument}) i zamjenjuje snimak.
     */
    public void fileAct(EgopPismenoEntity akt, SubmissionEntity submission, LessorEntity lessor,
                        String korisnik, AktDokument dokument) throws EgopException {
        if (akt.getPdfContent() == null || akt.getPdfContent().length == 0) {
            throw new EgopBadRequestException(
                    "Akt " + akt.getId() + " nema snimljen PDF — uredsko poslovanje traži dokument"
                            + " uz svako pismeno, pa se prazan akt ne šalje.");
        }

        Integer subjektOznaka = ensureSubjekt(lessor);
        // Pismeno kreirano prije uvođenja predmeta akata (21.10.2026.) živi u predmetu registracije:
        // ne otvara se novi predmet i ne renderira dokument s klasom predmeta u kojem pismeno nije.
        boolean pismenoUPredmetuRegistracije = akt.isFiled() && submission.getEgopAktiRbrPredmeta() == null;
        if (!pismenoUPredmetuRegistracije) {
            ensurePredmetAkti(submission, lessor, subjektOznaka, korisnik, akt.getRn());
        }

        if (!akt.isFiled()) {
            Kreirano kreirano = kreirajPismeno(submission.getEgopAktiUredskaGodina(),
                    submission.getEgopAktiRbrPredmeta(), akt.getVrstaPismenaNaziv(), akt.getSmjer(), subjektOznaka,
                    lessor.getLessorOib(), korisnik);
            akt.applyPismeno(kreirano.jop(), kreirano.urBroj(), kreirano.vrsta().sifra(),
                    kreirano.vrsta().privremena());
            store.applyPismeno(akt.getId(), kreirano.jop(), kreirano.urBroj(),
                    kreirano.vrsta().sifra(), kreirano.vrsta().privremena());
        }

        if (!akt.isDocumentAttached() && !pismenoUPredmetuRegistracije) {
            Optional<byte[]> konacni = dokument.konacni(
                    new FilingReference(submission.getEgopAktiKlasa(), akt.getUrBroj(), akt.getJop()));
            if (konacni.isPresent()) {
                akt.replacePdf(konacni.get());
                store.replacePdf(akt.getId(), konacni.get());
            }
        }

        // markDocumentAttached ujedno postavlja SYNCED — prilaganje dokumenta je zadnji korak.
        attachPdfOnce(akt, akt.getPdfContent(), korisnik);

        log.info("egop_akt_filed akt={} rn={} vrsta='{}' klasa={} urbroj={} korisnik={}",
                akt.getId(), akt.getRn(), akt.getVrstaPismenaNaziv(),
                pismenoUPredmetuRegistracije ? submission.getEgopKlasa() : submission.getEgopAktiKlasa(),
                akt.getUrBroj(), korisnik);

        // Otpremu je zapisao RnLifecycleFilingListener kad je akt nastao (ako je KP tada bio uključen).
        kpOtprema.otpremi(akt.getId());
    }

    /**
     * Prilaže PDF samo ako već nije priložen — eGOP nema idempotentno prilaganje,
     * pa bi retry nakon djelomičnog pada duplicirao dokument na pismenu.
     */
    private void attachPdfOnce(EgopPismenoEntity pismeno, byte[] pdf, String korisnik) throws EgopException {
        if (pismeno.isDocumentAttached()) {
            return;
        }
        KreirajDokumentZaPismeno request = new KreirajDokumentZaPismeno();
        request.setUserName(korisnik);
        request.setJop(pismeno.getJop());
        request.setExtension("pdf");
        request.setAttachment(pdf);
        egopClient.kreirajDokumentZaPismeno(request);
        pismeno.markDocumentAttached();
        store.markDocumentAttached(pismeno.getId());
    }

    private String subjektNaziv(LessorEntity lessor) {
        return lessor.isLegalEntityOwner() && lessor.getLegalEntityName() != null
                ? lessor.getLegalEntityName()
                : lessor.getFirstName() + " " + lessor.getLastName();
    }

    private String formatAdresa(LessorEntity lessor) {
        StringBuilder sb = new StringBuilder(lessor.getStreet());
        if (lessor.getStreetNumber() != null) {
            sb.append(" ").append(lessor.getStreetNumber());
        }
        if (lessor.getPlace() != null) {
            sb.append(", ").append(lessor.getPlace());
        }
        return sb.toString();
    }
}
