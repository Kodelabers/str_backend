package com.str.backend.egop;

import com.str.backend.document.FilingReference;
import com.str.backend.document.StrDocumentType;
import com.str.backend.domain.EgopSyncStatus;
import com.str.backend.egop.codebook.VrstaPoslovnihSubjekata;
import com.str.backend.egop.codebook.VrstaUpisneKnjige;
import com.str.backend.egop.exception.EgopBadRequestException;
import com.str.backend.egop.exception.EgopException;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.request.SubmissionEntity;
import hr.infodom.egov.pismeno.KreirajDokumentZaPismeno;
import hr.infodom.egov.pismeno.KreirajPismeno2;
import hr.infodom.egov.pismeno.PismenoBasicInfo2;
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
 *   <li>neupravni predmet vrste "Izdavanje Registracijskog broja" — jedan po
 *       submissionu, provjeri postojanje pa kreiraj;</li>
 *   <li>ulazno pismeno (zahtjev) + PDF dokument — PDF se renderira tek nakon
 *       kreiranja pismena jer tada postoje KLASA i URBROJ;</li>
 *   <li>izlazno pismeno (obavijest o dodjeli) + PDF dokument.</li>
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

    private final String appUsername;
    private final String rjesavatelj;
    private final String vrstaPredmetaNaziv;
    private final String ulaznoPismenoNaziv;
    private final String izlaznoPismenoNaziv;
    private final String nadleznaOrgJedinicaNaziv;

    public EgopFilingService(EgopClient egopClient,
                             EgopFilingStore store,
                             EgopProperties properties,
                             @Value("${str.egop.rjesavatelj:}") String rjesavatelj,
                             @Value("${str.egop.vrsta-predmeta:Izdavanje Registracijskog broja}") String vrstaPredmetaNaziv,
                             // Defaulti dolaze iz StrDocumentType — naziv je ključ eGOP šifrarnika,
                             // pa mora imati jedan izvor. Property ostaje da se naziv može zamijeniti
                             // bez rebuilda ako ga InfoDom promijeni.
                             @Value("${str.egop.vrsta-pismena-zahtjev:}") String ulaznoPismenoNaziv,
                             @Value("${str.egop.vrsta-pismena-dodjela:}") String izlaznoPismenoNaziv,
                             @Value("${str.egop.nadlezna-org-jedinica:MINISTARSTVO TURIZMA}") String nadleznaOrgJedinicaNaziv) {
        this.egopClient = egopClient;
        this.store = store;
        this.appUsername = properties.qualifiedAppUsername();
        // InfoDom (26.07.2026): rješavatelj mora biti osoba s username-om; probna
        // vrijednost je servisni račun, a property dopušta zamjenu bez rebuilda.
        this.rjesavatelj = (rjesavatelj == null || rjesavatelj.isBlank())
                ? this.appUsername
                : rjesavatelj;
        this.vrstaPredmetaNaziv = vrstaPredmetaNaziv;
        this.ulaznoPismenoNaziv = orDefault(ulaznoPismenoNaziv, StrDocumentType.ZAHTJEV);
        this.izlaznoPismenoNaziv = orDefault(izlaznoPismenoNaziv, StrDocumentType.DODJELA);
        this.nadleznaOrgJedinicaNaziv = nadleznaOrgJedinicaNaziv;
    }

    private static String orDefault(String configured, StrDocumentType type) {
        return (configured == null || configured.isBlank())
                ? type.vrstaPismenaNaziv() : configured;
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

        EgopPismenoEntity ulazno = ensurePismeno(submission, ulaznoPismenoNaziv,
                EgopPismenoEntity.Smjer.ULAZNO, subjektOznaka, lessor.getLessorOib());
        FilingReference ulaznaOznaka = filing(submission, ulazno);
        byte[] zahtjevPdf = documents.zahtjev(ulaznaOznaka);
        attachPdfOnce(ulazno, zahtjevPdf);
        advance(submission, EgopSyncStatus.PISMENO_OK);

        // Smjer (ulazno/izlazno) ne šaljemo — eGOP ga određuje sam iz konfiguracije vrste
        // pismena; Smjer na našem entitetu je samo interna evidencija. Dostavu obavijesti
        // (KP eGrađana za EU) također rješava eGOP strana, ne STR.
        EgopPismenoEntity izlazno = ensurePismeno(submission, izlaznoPismenoNaziv,
                EgopPismenoEntity.Smjer.IZLAZNO, subjektOznaka, lessor.getLessorOib());
        // Izlazno pismeno nosi vlastiti URBROJ, pa i vlastiti PDF — ranije se ovdje prilagala
        // kopija PDF-a zahtjeva, dokument s krivim urudžbenim brojem i krivim naslovom.
        attachPdfOnce(izlazno, documents.obavijestODodjeli(filing(submission, izlazno)));

        // Na submission ide oznaka ULAZNOG pismena — to je akt kojim je postupak pokrenut.
        String filingNumber = formatFilingNumber(ulaznaOznaka);
        store.saveSuccess(submission.getSubmissionId(), filingNumber, zahtjevPdf);
        submission.applyEgopSyncStatus(EgopSyncStatus.SYNCED);
        log.info("egop_filing_ok submission={} klasa={} urbroj_ulazno={} urbroj_izlazno={}",
                submission.getSubmissionId(), submission.getEgopKlasa(), ulazno.getUrBroj(), izlazno.getUrBroj());
        return new FilingResult(filingNumber, zahtjevPdf);
    }

    /** KLASA je zajednička svim pismenima predmeta, URBROJ je pojedinačan. */
    private static FilingReference filing(SubmissionEntity submission, EgopPismenoEntity pismeno) {
        return new FilingReference(submission.getEgopKlasa(), pismeno.getUrBroj());
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

        String vrstaNaziv = lessor.isLegalEntityOwner()
                ? VrstaPoslovnihSubjekata.PRAVNA_OSOBA.getNaziv()
                : VrstaPoslovnihSubjekata.FIZICKA_OSOBA.getNaziv();
        request.setTipOsobe(resolveRequired(egopClient.getVrstePoslovnihSubjekata(), vrstaNaziv,
                "vrsta poslovnog subjekta"));

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
     * Jedan eGOP predmet po submissionu; identitet (godina + rbr + klasa) se trajno
     * sprema jer se u isti predmet kasnije urudžbiraju i akti životnog ciklusa RB-a.
     */
    private void ensurePredmet(SubmissionEntity submission, LessorEntity lessor, Integer subjektOznaka)
            throws EgopException {
        if (submission.getEgopUredskaGodina() != null && submission.getEgopRbrPredmeta() != null
                && predmetPostoji(submission)) {
            return;
        }

        KreirajPredmet2 request = new KreirajPredmet2();
        request.setUserName(appUsername);
        request.setUpisnaKnjiga(VrstaUpisneKnjige.NP.getCode());
        request.setVrstaPredmeta(resolveRequired(egopClient.getVrstePredmeta(), vrstaPredmetaNaziv,
                "vrsta predmeta"));
        request.setNadleznaOrgJedinica(resolveRequired(egopClient.getUstroj(), nadleznaOrgJedinicaNaziv,
                "nadležna org. jedinica"));
        request.setSubjektOznaka(subjektOznaka);
        request.setSubjektOIB(lessor.getLessorOib());
        String identitet = lessor.getLessorOib() != null
                ? subjektNaziv(lessor) + ", OIB " + lessor.getLessorOib()
                : subjektNaziv(lessor);
        request.setNazivPredmeta(vrstaPredmetaNaziv + " — " + identitet);
        request.setDatumOtvaranja(EgopDates.toXml(LocalDateTime.now()));

        PredmetBasicInfo2 predmet = egopClient.kreirajPredmet2(request);
        submission.applyEgopPredmet(predmet.getUredskaGodina(), predmet.getRbrPredmeta(),
                predmet.getKlasifikacijskaOznaka());
        store.savePredmet(submission.getSubmissionId(), predmet.getUredskaGodina(),
                predmet.getRbrPredmeta(), predmet.getKlasifikacijskaOznaka());

        odrediRjesavatelja(submission, predmet);
    }

    /**
     * Servisni račun mora biti nadležan za predmet da bi smio kreirati pismena
     * (spec str. 30/50). Ostaje best-effort — predmet u ovom trenutku već postoji, pa
     * bi rušenje ostavilo siroče — ali pad se logira glasno: InfoDom je upozorio da je
     * rješavatelj upravo ono što možda neće raditi sa servisnim računom, a stvarna
     * posljedica se vidi tek kasnije, kod kreiranja pismena.
     */
    private void odrediRjesavatelja(SubmissionEntity submission, PredmetBasicInfo2 predmet) {
        try {
            OdrediRjesavatelja request = new OdrediRjesavatelja();
            request.setUserName(appUsername);
            request.setUredskaGodina((short) predmet.getUredskaGodina());
            request.setRbrPredmeta(predmet.getRbrPredmeta());
            request.setRjesavatelj(rjesavatelj);
            egopClient.odrediRjesavatelja(request);
            log.info("egop_odredi_rjesavatelja_ok submission={} predmet={}/{} rjesavatelj={}",
                    submission.getSubmissionId(), predmet.getUredskaGodina(), predmet.getRbrPredmeta(),
                    rjesavatelj);
        } catch (EgopException e) {
            log.error("egop_odredi_rjesavatelja_failed submission={} predmet={}/{} rjesavatelj={}:"
                            + " {} — kreiranje pismena će vjerojatno pasti zbog nenadležnosti;"
                            + " probati drugu vrijednost u str.egop.rjesavatelj",
                    submission.getSubmissionId(), predmet.getUredskaGodina(), predmet.getRbrPredmeta(),
                    rjesavatelj, e.getMessage(), e);
        }
    }

    private boolean predmetPostoji(SubmissionEntity submission) throws EgopException {
        DohvatiPodatkePredmeta request = new DohvatiPodatkePredmeta();
        request.setUserName(appUsername);
        request.setUredskaGodina(submission.getEgopUredskaGodina().shortValue());
        request.setRbrPredmeta(submission.getEgopRbrPredmeta());
        // -100 ("predmet ne postoji") prolazi kao validan odgovor s OperationSucceeded=false
        PredmetInfo predmet = egopClient.dohvatiPodatkePredmeta(request);
        return predmet != null && predmet.isOperationSucceeded();
    }

    private EgopPismenoEntity ensurePismeno(SubmissionEntity submission, String vrstaNaziv,
                                            EgopPismenoEntity.Smjer smjer, Integer subjektOznaka,
                                            String subjektOib) throws EgopException {
        Optional<EgopPismenoEntity> existing = store.findPismeno(submission.getSubmissionId(),
                vrstaNaziv, EgopPismenoEntity.ACT_REF_REGISTRACIJA);
        if (existing.isPresent()) {
            return existing.get();
        }

        PismenoBasicInfo2 pismeno = kreirajPismeno(submission, vrstaNaziv, subjektOznaka, subjektOib);
        EgopPismenoEntity entity = EgopPismenoEntity.create(
                submission.getSubmissionId(), vrstaNaziv, smjer, pismeno.getJop(), pismeno.getUrBroj());
        return store.savePismeno(entity);
    }

    private PismenoBasicInfo2 kreirajPismeno(SubmissionEntity submission, String vrstaNaziv,
                                             Integer subjektOznaka, String subjektOib)
            throws EgopException {
        KreirajPismeno2 request = new KreirajPismeno2();
        request.setUserName(appUsername);
        request.setRbrSpisa(submission.getEgopRbrPredmeta());
        request.setUredskaGodina(submission.getEgopUredskaGodina().shortValue());
        request.setVrstaPismena(resolveRequired(egopClient.getVrstePismena(), vrstaNaziv, "vrsta pismena"));
        request.setSubjektOznaka(subjektOznaka);
        request.setSubjektOIB(subjektOib);
        request.setNazivPismena(vrstaNaziv);
        request.setDatumNastanka(EgopDates.toXml(LocalDateTime.now()));
        return egopClient.kreirajPismeno2(request);
    }

    /**
     * Urudžbira jedan akt životnog ciklusa RB-a u <b>isti</b> predmet u kojem je registracija.
     *
     * <p>Subjekt i predmet se osiguravaju i ovdje, ne samo pri registraciji: ako je
     * registracijsko urudžbiranje trajno palo, predmet u eGOP-u ne postoji, a
     * {@code KreirajPismeno2} ga ne stvara — pismeno bi bilo odbijeno. Isto vrijedi ako je
     * predmet u međuvremenu nestao s eGOP strane.
     *
     * <p>PDF se <b>ne renderira</b> ovdje nego dolazi snimljen na aktu; vidi
     * {@link EgopPismenoEntity#getPdfContent()}.
     */
    public void fileAct(EgopPismenoEntity akt, SubmissionEntity submission, LessorEntity lessor)
            throws EgopException {
        if (akt.getPdfContent() == null || akt.getPdfContent().length == 0) {
            throw new EgopBadRequestException(
                    "Akt " + akt.getId() + " nema snimljen PDF — uredsko poslovanje traži dokument"
                            + " uz svako pismeno, pa se prazan akt ne šalje.");
        }

        Integer subjektOznaka = ensureSubjekt(lessor);
        ensurePredmet(submission, lessor, subjektOznaka);

        if (!akt.isFiled()) {
            PismenoBasicInfo2 pismeno = kreirajPismeno(submission, akt.getVrstaPismenaNaziv(),
                    subjektOznaka, lessor.getLessorOib());
            akt.applyPismeno(pismeno.getJop(), pismeno.getUrBroj());
            store.applyPismeno(akt.getId(), pismeno.getJop(), pismeno.getUrBroj());
        }

        // markDocumentAttached ujedno postavlja SYNCED — prilaganje dokumenta je zadnji korak.
        attachPdfOnce(akt, akt.getPdfContent());

        log.info("egop_akt_filed akt={} rn={} vrsta='{}' klasa={} urbroj={}",
                akt.getId(), akt.getRn(), akt.getVrstaPismenaNaziv(),
                submission.getEgopKlasa(), akt.getUrBroj());
    }

    /**
     * Prilaže PDF samo ako već nije priložen — eGOP nema idempotentno prilaganje,
     * pa bi retry nakon djelomičnog pada duplicirao dokument na pismenu.
     */
    private void attachPdfOnce(EgopPismenoEntity pismeno, byte[] pdf) throws EgopException {
        if (pismeno.isDocumentAttached()) {
            return;
        }
        KreirajDokumentZaPismeno request = new KreirajDokumentZaPismeno();
        request.setUserName(appUsername);
        request.setJop(pismeno.getJop());
        request.setExtension("pdf");
        request.setAttachment(pdf);
        egopClient.kreirajDokumentZaPismeno(request);
        pismeno.markDocumentAttached();
        store.markDocumentAttached(pismeno.getId());
    }

    private <T> T resolveRequired(java.util.Map<String, T> codebook, String naziv, String opis)
            throws EgopBadRequestException {
        T id = EgopNaziv.resolveId(codebook, naziv);
        if (id == null) {
            throw new EgopBadRequestException(
                    "eGOP šifrarnik nema unos za " + opis + " '" + naziv + "' — provjeriti konfiguraciju i MDM");
        }
        return id;
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
