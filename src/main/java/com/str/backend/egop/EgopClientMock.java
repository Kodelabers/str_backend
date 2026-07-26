package com.str.backend.egop;

import com.str.backend.document.StrDocumentType;
import hr.infodom.egov.pismeno.DohvatiDokumentZaPismeno;
import hr.infodom.egov.pismeno.DohvatiDokumentZaPrilog;
import hr.infodom.egov.pismeno.DohvatiListuPrilogaPismena;
import hr.infodom.egov.pismeno.DohvatiPodatkePismena;
import hr.infodom.egov.pismeno.DokumentInfo;
import hr.infodom.egov.pismeno.KreirajDokumentZaPismeno;
import hr.infodom.egov.pismeno.KreirajPismeno2;
import hr.infodom.egov.pismeno.KreirajPrilogIPridruziDokument;
import hr.infodom.egov.pismeno.ObrisiDokumentPriloga;
import hr.infodom.egov.pismeno.ObrisiDokumentZaPismeno;
import hr.infodom.egov.pismeno.PismenoBasicInfo2;
import hr.infodom.egov.pismeno.PismenoInfo;
import hr.infodom.egov.pismeno.PriloziPismenaInfo;
import hr.infodom.egov.pismeno.StornirajPismeno;
import hr.infodom.egov.predmet.DohvatiPismenaPredmeta;
import hr.infodom.egov.predmet.DohvatiPodatkePredmeta;
import hr.infodom.egov.predmet.DohvatiPredmetId;
import hr.infodom.egov.predmet.DohvatiPredmeteZaKorisnikaURjesavanju;
import hr.infodom.egov.predmet.KreirajPredmet2;
import hr.infodom.egov.predmet.OdrediRjesavatelja;
import hr.infodom.egov.predmet.PismenoInfo2;
import hr.infodom.egov.predmet.PredmetBasicInfo2;
import hr.infodom.egov.predmet.PredmetBasicInfo3;
import hr.infodom.egov.predmet.PredmetInfo;
import hr.infodom.egov.predmet.PredmetInfo2;
import hr.infodom.egov.predmet.StornirajPredmet;
import hr.infodom.egov.predmet.ZatvoriPredmet;
import hr.infodom.egov.subjekt.DohvatiPodatkeSubjekta;
import hr.infodom.egov.subjekt.KreirajSubjekta;
import hr.infodom.egov.subjekt.SubjektInfo;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock eGOP klijenta — aktivan kad je {@code hr.infodom.str.integration.egop.enabled}
 * false ili nepostavljen (local/mock/test profili). Vraća deterministične odgovore
 * kako bi registracijski tok radio end-to-end bez pristupa eGOP-u.
 */
@Component
@ConditionalOnProperty(
        name = "hr.infodom.str.integration.egop.enabled",
        havingValue = "false",
        matchIfMissing = true
)
class EgopClientMock implements EgopClient {

    private static final Logger log = LoggerFactory.getLogger(EgopClientMock.class);

    /**
     * Mock je aktivan uz {@code matchIfMissing = true}, pa tipfeler u {@code EGOP_ENABLED}
     * tiho vraća izmišljene podatke. Bez prefiksa bi izmišljena KLASA/URBROJ u
     * {@code submission.egop_klasa} bili nerazlučivi od pravih.
     */
    private static final String MOCK_PREFIX = "MOCK-";

    private static final AtomicInteger PREDMET_SEQ = new AtomicInteger(100);
    private static final AtomicInteger PISMENO_SEQ = new AtomicInteger(1000);

    private final boolean egopEnabled;

    EgopClientMock(@Value("${hr.infodom.str.integration.egop.enabled:false}") boolean egopEnabled) {
        this.egopEnabled = egopEnabled;
    }

    @PostConstruct
    void init() {
        log.warn("Loading eGOP MOCK!");
    }

    @Override
    public Map<String, String> getVrstePoslovnihSubjekata() {
        return Map.of(
                "Pravna osoba", "2",
                "Fizička osoba", "3",
                "Trgovačko društvo", "5",
                "Obrt", "11"
        );
    }

    @Override
    public Map<String, Integer> getUstroj() {
        return Map.ofEntries(
                Map.entry("Pododsjek pisarnice", 6),
                Map.entry("SAMOSTALNI SEKTOR TURISTICKE INSPEKCIJE", 426),
                Map.entry("Sluzba inspekcijskog nadzora u podrucju ugostiteljstva i turizma", 427),
                Map.entry("Sluzba turisticke inspekcije Podrucna jedinica Zagreb (sjediste)", 428),
                Map.entry("Sluzba za standarde i kategorizaciju", 526),
                Map.entry("Odjel za kategorizaciju", 527),
                Map.entry("MINISTARSTVO TURIZMA", 559)
        );
    }

    @Override
    public Map<String, String> getVrstePredmeta() {
        return Map.of("Izdavanje Registracijskog broja", "9282");
    }

    /**
     * Nazivi dolaze iz {@link StrDocumentType} da se mock ne raziđe sa stvarnim vrstama —
     * ranije je ovdje stajao ručni popis, pa je promjena naziva na jednom mjestu tiho
     * razbijala razrješavanje šifre na drugom. Šifre su izmišljene (mock).
     */
    @Override
    public Map<String, String> getVrstePismena() {
        Map<String, String> vrste = new LinkedHashMap<>();
        int sifra = 101;
        for (StrDocumentType type : StrDocumentType.values()) {
            vrste.put(type.vrstaPismenaNaziv(), String.valueOf(sifra++));
        }
        return Map.copyOf(vrste);
    }

    @Override
    public Map<String, Integer> getVrstePriloga() {
        return Map.of();
    }

    @Override
    public void healthCheck() {
        if (!egopEnabled) {
            log.warn("Egop client NOT enabled: using MOCK!");
        }
    }

    @Override
    public SubjektInfo dohvatiPodatkeSubjekta(DohvatiPodatkeSubjekta request) {
        log.info("Mock eGOP dohvatiPodatkeSubjekta called: oznaka={}, oib={}",
                request.getOznakaSubjekta(), request.getOib());
        SubjektInfo mockResponse = new SubjektInfo();
        mockResponse.setOperationSucceeded(true);
        mockResponse.setOznakaSubjekta(request.getOznakaSubjekta() != null
                ? request.getOznakaSubjekta()
                : 135333);
        mockResponse.setTipOsobe("3");
        mockResponse.setNaziv("Mock Subjekt");
        mockResponse.setKratkiNaziv("Mock Subjekt");
        mockResponse.setOznakaVrste("3");
        mockResponse.setUrBroj("383");
        mockResponse.setOIB(request.getOib() != null ? request.getOib() : "12312312316");
        mockResponse.setAdresa("Četvrt Ribnjaka 17, 21310 Omiš");
        mockResponse.setPostanskiBroj("21310");
        mockResponse.setNazivDrzave("Hrvatska");
        return mockResponse;
    }

    @Override
    public SubjektInfo kreirajSubjekta(KreirajSubjekta request) {
        log.info("Mock eGOP kreirajSubjekta called: oib={}, naziv={}", request.getOib(), request.getNaziv());
        SubjektInfo mockResponse = new SubjektInfo();
        mockResponse.setOperationSucceeded(true);
        mockResponse.setOznakaSubjekta(135333);
        mockResponse.setOIB(request.getOib());
        mockResponse.setNaziv(request.getNaziv());
        return mockResponse;
    }

    @Override
    public PredmetBasicInfo2 kreirajPredmet2(KreirajPredmet2 request) {
        log.info("Mock eGOP kreirajPredmet2 called: vrsta={}, subjektOznaka={}",
                request.getVrstaPredmeta(), request.getSubjektOznaka());
        int rbr = PREDMET_SEQ.incrementAndGet();
        PredmetBasicInfo2 mockResponse = new PredmetBasicInfo2();
        mockResponse.setOperationSucceeded(true);
        mockResponse.setUredskaGodina(LocalDateTime.now().getYear());
        mockResponse.setRbrPredmeta(rbr);
        mockResponse.setKlasifikacijskaOznaka(MOCK_PREFIX + "334-01/" + (LocalDateTime.now().getYear() % 100) + "-01/" + rbr);
        return mockResponse;
    }

    @Override
    public List<PredmetInfo2> dohvatiPredmeteZaKorisnikaURjesavanju(DohvatiPredmeteZaKorisnikaURjesavanju request) {
        log.info("Mock eGOP dohvatiPredmeteZaKorisnikaURjesavanju called for username: {}", request.getUsername());
        List<PredmetInfo2> predmeti = new ArrayList<>();
        predmeti.add(mockPredmet(2025, 101, "NP", MOCK_PREFIX + "334-01/25-01/101",
                "Izdavanje Registracijskog broja - Mock 1", "1", LocalDateTime.of(2025, 3, 10, 9, 0)));
        predmeti.add(mockPredmet(2025, 102, "NP", MOCK_PREFIX + "334-01/25-01/102",
                "Izdavanje Registracijskog broja - Mock 2", "1", LocalDateTime.of(2025, 4, 2, 11, 30)));
        return predmeti;
    }

    private PredmetInfo2 mockPredmet(int uredskaGodina, int rbrPredmeta, String upisnaKnjiga, String klasa,
                                     String nazivPredmeta, String statusPredmeta, LocalDateTime datumOtvaranja) {
        PredmetInfo2 predmet = new PredmetInfo2();
        predmet.setOperationSucceeded(true);
        predmet.setUredskaGodina(uredskaGodina);
        predmet.setRbrPredmeta(rbrPredmeta);
        predmet.setUpisnaKnjiga(upisnaKnjiga);
        predmet.setKlasa(klasa);
        predmet.setNazivPredmeta(nazivPredmeta);
        predmet.setStatusPredmeta(statusPredmeta);
        predmet.setDatumOtvaranja(EgopDates.toXml(datumOtvaranja));
        return predmet;
    }

    @Override
    public boolean stornirajPredmet(StornirajPredmet request) {
        return false;
    }

    @Override
    public boolean zatvoriPredmet(ZatvoriPredmet request) {
        log.info("Mock eGOP zatvoriPredmet called: {}/{}", request.getUredskaGodina(), request.getRbrPredmeta());
        return true;
    }

    @Override
    public PismenoBasicInfo2 kreirajPismeno2(KreirajPismeno2 request) {
        log.info("Mock eGOP kreirajPismeno2 called: vrsta={}, rbrSpisa={}, uredskaGodina={}",
                request.getVrstaPismena(), request.getRbrSpisa(), request.getUredskaGodina());
        PismenoBasicInfo2 mockResponse = new PismenoBasicInfo2();
        mockResponse.setOperationSucceeded(true);
        mockResponse.setJop(PISMENO_SEQ.incrementAndGet());
        mockResponse.setUrBroj(MOCK_PREFIX + "529-06/" + (LocalDateTime.now().getYear() % 100) + "-1");
        return mockResponse;
    }

    @Override
    public void stornirajPismeno(StornirajPismeno request) {
        // Stornirano
    }

    @Override
    public DokumentInfo kreirajDokumentZaPismeno(KreirajDokumentZaPismeno request) {
        log.info("Mock eGOP kreirajDokumentZaPismeno called: jop={}, extension={}, size={}",
                request.getJop(), request.getExtension(),
                request.getAttachment() == null ? 0 : request.getAttachment().length);
        DokumentInfo mockResponse = new DokumentInfo();
        mockResponse.setOperationSucceeded(true);
        mockResponse.setJop(request.getJop());
        return mockResponse;
    }

    @Override
    public DokumentInfo kreirajPrilogIPridruziDokument(KreirajPrilogIPridruziDokument request) {
        return null;
    }

    @Override
    public void odrediRjesavatelja(OdrediRjesavatelja request) {
        log.info("Mock eGOP odrediRjesavatelja called: {}/{}", request.getUredskaGodina(), request.getRbrPredmeta());
    }

    @Override
    public List<PismenoInfo2> dohvatiPismenaPredmeta(DohvatiPismenaPredmeta request) {
        return List.of();
    }

    @Override
    public PredmetBasicInfo3 dohvatiPredmet(DohvatiPredmetId request) {
        return null;
    }

    @Override
    public PredmetInfo dohvatiPodatkePredmeta(DohvatiPodatkePredmeta request) {
        log.info("Mock eGOP dohvatiPodatkePredmeta called: {}/{}",
                request.getUredskaGodina(), request.getRbrPredmeta());
        PredmetInfo predmet = new PredmetInfo();
        predmet.setOperationSucceeded(true);
        predmet.setUredskaGodina(request.getUredskaGodina());
        predmet.setRbrPredmeta(request.getRbrPredmeta());
        predmet.setUpisnaKnjiga("NP");
        predmet.setKlasa(MOCK_PREFIX + "334-01/25-01/" + request.getRbrPredmeta());
        predmet.setNazivPredmeta("Izdavanje Registracijskog broja - Mock");
        predmet.setStatusPredmeta("Otvoren");
        predmet.setDatumOtvaranja(EgopDates.toXml(LocalDateTime.of(2025, 3, 10, 9, 0)));
        predmet.setVrstaPredmeta("9282");
        predmet.setSubjektOznaka(135333);
        predmet.setSubjektOIB("12312312316");
        predmet.setSubjektNaziv("Mock Subjekt");
        predmet.setNadleznaUstrojstvenaJedinica(559);
        return predmet;
    }

    @Override
    public PismenoInfo dohvatiPodatkePismena(DohvatiPodatkePismena request) {
        return null;
    }

    @Override
    public DokumentInfo dohvatiDokumentZaPismeno(DohvatiDokumentZaPismeno request) {
        return null;
    }

    @Override
    public DokumentInfo dohvatiDokumentZaPrilog(DohvatiDokumentZaPrilog request) {
        return null;
    }

    @Override
    public void obrisiDokumentZaPismeno(ObrisiDokumentZaPismeno request) {
    }

    @Override
    public List<PriloziPismenaInfo> dohvatiListuPrilogaPismena(DohvatiListuPrilogaPismena request) {
        return List.of();
    }

    @Override
    public void obrisiDokumentPriloga(ObrisiDokumentPriloga request) {
    }
}
