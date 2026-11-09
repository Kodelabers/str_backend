package com.str.backend.str.verifikacija;

import com.str.backend.categorization.CategorizationDecisionEntity;
import com.str.backend.categorization.CategorizationDecisionRepository;
import com.str.backend.categorization.CategorizationDecisionStatus;
import com.str.backend.domain.RnStatus;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import com.str.backend.str.RnFacilityVerification;
import com.str.backend.str.verifikacija.VerifikacijaOutboxEntity.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Jedan pokušaj upisa zahtjeva za žurnu verifikaciju u eTurizam, u <b>jednoj</b> transakciji
 * ({@code REQUIRES_NEW}, s vremenskim ograničenjem): zaključani outbox redak → provjera da upis još
 * ima smisla → zahtjev u {@code str.verification_request} → obavijesti službenicima → zatvoren outbox
 * redak. Ista baza, pa je upis atomaran: bilo koja greška poništava i zahtjev i obavijesti, a outbox
 * ostaje {@code PENDING} za sljedeći pokušaj. Neuspjeh bilježi pozivatelj, u zasebnoj transakciji
 * ({@link VerifikacijaOutbox#recordFailure}).
 *
 * <p><b>Novi objekt uz predano rješenje</b> ({@link VerifikacijaOutboxEntity.Vrsta#NOVI_OBJEKT_RJESENJE}):
 * zahtjeva nema (objekt u eTurizmu nema dokument), pa pokušaj upisuje samo obavijesti službenicima
 * nadležne županije i zatvara redak kao {@link Status#OBAVIJESTI_UPISANE}. Dok su obavijesti ugašene,
 * takav redak se ne dira — ostaje {@code PENDING} i ne troši pokušaje.
 *
 * <p><b>Provjera pri obradi.</b> Redak može čekati (zastavica ugašena, retry), a za to vrijeme RB može
 * biti opozvan ili povučen, ili objekt verificiran. Tada se ništa ne upisuje i redak se zatvara kao
 * {@link Status#PRESKOCENO} — inače bi službenik dobio obavijest „dodijeljen je registracijski broj"
 * za broj koji više ne vrijedi, ili za objekt koji je već provjeren.
 *
 * <p><b>Vremensko ograničenje.</b> Inline pokušaj radi na niti HTTP zahtjeva
 * ({@code str.eturizam.verifikacija.inline-timeout}, zadano 3 s), a retry job na jedinoj zajedničkoj
 * niti {@code @Scheduled} poslova ({@code ...timeout}, zadano 10 s). Bez ograničenja bi čekanje na
 * zaključan redak (npr. eTurizamov nepotvrđeni INSERT za isti dokument) objesilo odgovor ili zaustavilo
 * i eGOP i mail retry. {@code JpaTransactionManager} ograničenje prenosi na JDBC naredbe
 * ({@code JdbcTemplate} → {@code setQueryTimeout}) i na JPA upite, uključujući {@code SELECT … FOR UPDATE};
 * istek je obična greška pokušaja.
 *
 * <p><b>Tko smije pokušati.</b> Pokušaj nosi broj pokušaja koji je pozivatelj vidio; nakon
 * zaključavanja redak mora imati upravo toliko pokušaja, a za retry job i dospjeli rok. Inline
 * pokušaj (odmah nakon commita) smije prije roka, ali samo kao prvi. Tako dvije instance ili
 * zakašnjeli inline pokušaj ne obrađuju redak koji je drugi već obradio ili odgodio, a
 * {@link VerifikacijaOutbox#recordFailure} isti neuspjeh ne broji dvaput.
 *
 * <p>Zahtjev za dokument koji već postoji (bilo koji {@code active}) se ne mijenja i za njega se ne
 * šalju obavijesti ({@link Status#ZAHTJEV_POSTOJAO}). Zapisuje se samo njegov id (privremeno
 * pravilo, potvrditi s eTurizmom).
 */
@Component
public class EturizamVerifikacijaWriter {

    private static final Logger log = LoggerFactory.getLogger(EturizamVerifikacijaWriter.class);

    /** Zona baze eTurizma; {@code timestamp} stupci drže lokalno vrijeme (W0, 5. 11. 2026.). */
    static final ZoneId ETURIZAM_ZONE = ZoneId.of("Europe/Zagreb");

    /**
     * @param ocekivaniPokusaji broj pokušaja koji je pozivatelj vidio na retku
     * @param inline            prvi pokušaj odmah nakon commita — smije prije roka {@code next_attempt_at}
     */
    public record Pokusaj(String rn, int ocekivaniPokusaji, boolean inline) {

        public static Pokusaj inline(String rn) {
            return new Pokusaj(rn, 0, true);
        }

        public static Pokusaj izReda(VerifikacijaOutboxEntity zapis) {
            return new Pokusaj(zapis.getRn(), zapis.getAttempts(), false);
        }
    }

    private final VerifikacijaOutboxRepository outboxRepository;
    private final EturizamVerifikacijaRepository eturizam;
    private final RnRepository rnRepository;
    private final RnFacilityVerification facilityVerification;
    private final CategorizationDecisionRepository decisionRepository;
    private final EturizamVerifikacijaProperties properties;
    private final Clock clock;
    private final TransactionTemplate inlineTx;
    private final TransactionTemplate jobTx;

    public EturizamVerifikacijaWriter(VerifikacijaOutboxRepository outboxRepository,
                                      EturizamVerifikacijaRepository eturizam,
                                      RnRepository rnRepository,
                                      RnFacilityVerification facilityVerification,
                                      CategorizationDecisionRepository decisionRepository,
                                      EturizamVerifikacijaProperties properties,
                                      Clock clock,
                                      PlatformTransactionManager transactionManager) {
        this.outboxRepository = outboxRepository;
        this.eturizam = eturizam;
        this.rnRepository = rnRepository;
        this.facilityVerification = facilityVerification;
        this.decisionRepository = decisionRepository;
        this.properties = properties;
        this.clock = clock;
        this.inlineTx = transakcija(transactionManager, properties.inlineTimeout());
        this.jobTx = transakcija(transactionManager, properties.timeout());
    }

    /** Nova transakcija s ograničenjem trajanja (zaokruženo na sekunde, najmanje 1 s). */
    static TransactionTemplate transakcija(PlatformTransactionManager transactionManager, Duration timeout) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setTimeout((int) Math.max(1, timeout.toSeconds()));
        return template;
    }

    /**
     * @return ishod upisa; prazno kad pokušaja nije bilo: za RB nema retka na čekanju (verificiran
     * objekt, ili novi objekt bez predanog rješenja), redak je obradio ili odgodio drugi pokušaj, mu (za
     * job) rok još nije došao, ili je redak novog objekta a obavijesti su ugašene.
     * Prazno odvaja „preskočeno" od „obrađeno" — po tome to provjeravaju testovi.
     */
    public Optional<Status> upisi(Pokusaj pokusaj) {
        RuntimeException[] izvorna = new RuntimeException[1];
        try {
            return (pokusaj.inline() ? inlineTx : jobTx).execute(status -> {
                try {
                    return upisiUTransakciji(pokusaj);
                } catch (RuntimeException e) {
                    izvorna[0] = e;
                    throw e;
                }
            });
        } catch (RuntimeException e) {
            // Kad i rollback padne (npr. veza je zatvorena nakon isteka), TransactionTemplate baca grešku
            // rollbacka, a izvornu samo logira. Za razlog u outboxu i razvrstavanje važna je izvorna.
            if (izvorna[0] != null && e != izvorna[0]) {
                izvorna[0].addSuppressed(e);
                throw izvorna[0];
            }
            throw e;
        }
    }

    private Optional<Status> upisiUTransakciji(Pokusaj pokusaj) {
        String rn = pokusaj.rn();
        VerifikacijaOutboxEntity zapis = outboxRepository.lockByRn(rn).orElse(null);
        if (zapis == null || !zapis.isPending()) {
            return Optional.empty();
        }
        boolean nijeDospio = !pokusaj.inline() && zapis.getNextAttemptAt() != null
                && zapis.getNextAttemptAt().isAfter(clock.instant());
        if (zapis.getAttempts() != pokusaj.ocekivaniPokusaji() || nijeDospio) {
            log.debug("eturizam_verifikacija_preskoceno rn={} attempts={} ocekivano={} next_attempt_at={} — "
                            + "redak je u međuvremenu obradio ili odgodio drugi pokušaj",
                    rn, zapis.getAttempts(), pokusaj.ocekivaniPokusaji(), zapis.getNextAttemptAt());
            return Optional.empty();
        }
        if (zapis.isNoviObjekt() && !properties.obavijesti().enabled()) {
            // Novi objekt nema zahtjev, samo obavijesti: bez njih redak čeka, ne zatvara se prazan.
            log.debug("eturizam_verifikacija_novi_ceka rn={} — obavijesti su ugašene", rn);
            return Optional.empty();
        }

        String razlog = razlogPreskakanja(zapis);
        if (razlog != null) {
            zapis.skip(clock.instant(), razlog);
            outboxRepository.save(zapis);
            log.info("eturizam_verifikacija_preskoceno rn={} vrsta={} document={} facility={} decision={} razlog={} "
                            + "— ništa se ne upisuje",
                    rn, zapis.getVrsta(), zapis.getDocumentId(), zapis.getFacilityId(), zapis.getDecisionId(), razlog);
            return Optional.of(Status.PRESKOCENO);
        }

        if (zapis.isNoviObjekt()) {
            return Optional.of(upisiObavijestiNovogObjekta(zapis));
        }

        long documentId = zapis.getDocumentId();
        LocalDateTime sada = LocalDateTime.now(clock.withZone(ETURIZAM_ZONE));

        Optional<Long> postojeci = eturizam.findRequestId(documentId);
        if (postojeci.isPresent()) {
            zapis.complete(Status.ZAHTJEV_POSTOJAO, postojeci.get(), 0, clock.instant());
            outboxRepository.save(zapis);
            log.info("eturizam_verifikacija_postojao rn={} document={} verification_request={} — zahtjev za dokument "
                    + "već postoji (eTurizam ili drugi RB istog dokumenta), ne mijenja se i obavijesti se ne šalju",
                    rn, documentId, postojeci.get());
            return Optional.of(Status.ZAHTJEV_POSTOJAO);
        }

        long zahtjevId = eturizam.insertRequest(documentId, properties.korisnik(), sada);
        int obavijesti = properties.obavijesti().enabled() ? obavijesti(zapis, zahtjevId, sada) : 0;
        zapis.complete(Status.ZAHTJEV_UPISAN, zahtjevId, obavijesti, clock.instant());
        outboxRepository.save(zapis);
        log.info("eturizam_verifikacija_upisano rn={} document={} verification_request={} obavijesti={}{}",
                rn, documentId, zahtjevId, obavijesti,
                properties.obavijesti().enabled() ? "" : " (obavijesti ugašene)");
        return Optional.of(Status.ZAHTJEV_UPISAN);
    }

    /**
     * Zašto upis više nema smisla, ili {@code null}. RB se čita iz {@code str_rn}; verificiranost je
     * današnja, po pravilu stupca „Verificiran" ({@link RnFacilityVerification}, objekt po
     * {@code system_uuid}). Pad tog upita je obični neuspjeh pokušaja — bez potvrde se ne upisuje.
     * Za novi objekt umjesto verificiranosti se gleda rješenje: obavijest ide samo dok je predano
     * ({@code SUBMITTED}), a ne kad ga je službenik u međuvremenu prihvatio ili odbio.
     * Suspendiran RB (ili prijedlog suspenzije) i dalje traži verifikaciju (otvoreno pitanje, plan § 6).
     */
    private String razlogPreskakanja(VerifikacijaOutboxEntity zapis) {
        RnStatus status = rnRepository.findById(zapis.getRn()).map(RnEntity::getStatus).orElse(null);
        if (status == null) {
            return "RB ne postoji u str_rn.registration_number";
        }
        if (status == RnStatus.REVOKED || status == RnStatus.WITHDRAWN) {
            return "RB je " + status + " prije obrade";
        }
        if (zapis.isNoviObjekt()) {
            // Službenik je rješenje već prihvatio ili odbio na internom portalu STR-a: žurnost je prošla.
            CategorizationDecisionStatus rjesenje = decisionRepository.findById(zapis.getDecisionId())
                    .map(CategorizationDecisionEntity::getStatus).orElse(null);
            if (rjesenje != CategorizationDecisionStatus.SUBMITTED) {
                return rjesenje == null ? "rješenje ne postoji" : "rješenje je u međuvremenu obrađeno (" + rjesenje + ")";
            }
            return null;
        }
        if (facilityVerification.verifiedFacilityIds(List.of(zapis.getFacilityId())).contains(zapis.getFacilityId())) {
            return "objekt je u međuvremenu verificiran u eTurizmu";
        }
        return null;
    }

    /**
     * Po jedna obavijest svakom aktivnom internom korisniku nadležne jedinice dokumenta, najviše
     * {@code obavijesti.max-primatelja}. Bez jedinice ili bez korisnika zahtjev ipak ostaje upisan —
     * eTurizam ga vidi na listi — uz WARN.
     */
    private int obavijesti(VerifikacijaOutboxEntity zapis, long zahtjevId, LocalDateTime sada) {
        Optional<Long> jedinica = eturizam.findJurisdictionUnit(zapis.getDocumentId());
        if (jedinica.isEmpty()) {
            log.warn("eturizam_verifikacija_bez_jedinice rn={} document={} — dokument nema nadležnu jedinicu, "
                    + "obavijesti se ne šalju (zahtjev je upisan)", zapis.getRn(), zapis.getDocumentId());
            return 0;
        }
        EturizamVerifikacijaProperties.Obavijesti postavke = properties.obavijesti();
        int max = postavke.maxPrimatelja();
        List<Long> primatelji = eturizam.findRecipients(jedinica.get(), max + 1);
        if (primatelji.isEmpty()) {
            log.warn("eturizam_verifikacija_bez_primatelja rn={} document={} jedinica={} — jedinica nema aktivnih "
                            + "internih korisnika, obavijesti se ne šalju (zahtjev je upisan)",
                    zapis.getRn(), zapis.getDocumentId(), jedinica.get());
            return 0;
        }
        if (primatelji.size() > max) {
            log.warn("eturizam_verifikacija_previse_primatelja rn={} document={} jedinica={} max={} — jedinica ima "
                            + "više aktivnih internih korisnika od granice, obavijest dobiva samo prvih {} (po id-u)",
                    zapis.getRn(), zapis.getDocumentId(), jedinica.get(), max, max);
            primatelji = primatelji.subList(0, max);
        }

        String tekst = VerifikacijaObavijest.tekst(postavke.tekst(), zapis.getRn(),
                VerifikacijaObavijest.opisObjekta(zapis.getObjektNaziv(), zapis.getObjektMjesto(), zapis.getFacilityId()));
        String podaci = VerifikacijaObavijest.podaci(
                zapis.getRn(), zapis.getDocumentId(), zapis.getFacilityId(), zahtjevId);
        for (Long korisnik : primatelji) {
            eturizam.insertNotification(korisnik, postavke.vrsta(), tekst, postavke.objektTip(), zahtjevId, podaci, sada);
        }
        return primatelji.size();
    }

    /**
     * Novi objekt uz predano rješenje: obavijesti službenicima nadležne županije, bez zahtjeva, pa
     * zatvoren redak ({@link Status#OBAVIJESTI_UPISANE}). Kad se županija ne prepozna ili nema
     * službenika, redak se zatvara s 0 obavijesti i WARN-om — ponovni pokušaj to ne bi promijenio, a
     * rješenje službenici i dalje vide na internom portalu STR-a.
     */
    private Status upisiObavijestiNovogObjekta(VerifikacijaOutboxEntity zapis) {
        LocalDateTime sada = LocalDateTime.now(clock.withZone(ETURIZAM_ZONE));
        int obavijesti = obavijestiNovogObjekta(zapis, sada);
        zapis.completeObavijesti(obavijesti, clock.instant());
        outboxRepository.save(zapis);
        log.info("eturizam_verifikacija_novi_upisano rn={} decision={} zupanija={} obavijesti={}",
                zapis.getRn(), zapis.getDecisionId(), zapis.getZupanija(), obavijesti);
        return Status.OBAVIJESTI_UPISANE;
    }

    private int obavijestiNovogObjekta(VerifikacijaOutboxEntity zapis, LocalDateTime sada) {
        Optional<Long> zupanija = eturizam.findCountyId(zapis.getZupanija());
        if (zupanija.isEmpty()) {
            log.warn("eturizam_verifikacija_novi_bez_zupanije rn={} zupanija={} — županija objekta nije pronađena "
                    + "u str.county, obavijesti se ne šalju", zapis.getRn(), zapis.getZupanija());
            return 0;
        }
        EturizamVerifikacijaProperties.Obavijesti postavke = properties.obavijesti();
        int max = postavke.maxPrimatelja();
        List<Long> primatelji = eturizam.findCountyRecipients(zupanija.get(), max + 1);
        if (primatelji.isEmpty()) {
            log.warn("eturizam_verifikacija_novi_bez_primatelja rn={} zupanija={} county={} — nijedna aktivna "
                            + "županijska jedinica nadležna za županiju nema aktivnih internih korisnika, obavijesti "
                            + "se ne šalju", zapis.getRn(), zapis.getZupanija(), zupanija.get());
            return 0;
        }
        if (primatelji.size() > max) {
            log.warn("eturizam_verifikacija_previse_primatelja rn={} county={} max={} — županija ima više aktivnih "
                            + "internih korisnika od granice, obavijest dobiva samo prvih {} (po id-u)",
                    zapis.getRn(), zupanija.get(), max, max);
            primatelji = primatelji.subList(0, max);
        }

        String tekst = VerifikacijaObavijest.tekst(postavke.tekstNoviObjekt(), zapis.getRn(),
                VerifikacijaObavijest.opisNovogObjekta(zapis.getObjektNaziv(), zapis.getObjektMjesto()));
        String podaci = VerifikacijaObavijest.podaciNovogObjekta(zapis.getRn(), zapis.getDecisionId());
        for (Long korisnik : primatelji) {
            eturizam.insertNotification(korisnik, postavke.vrsta(), tekst, null, null, podaci, sada);
        }
        return primatelji.size();
    }
}
