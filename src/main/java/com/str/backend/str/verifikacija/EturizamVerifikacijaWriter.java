package com.str.backend.str.verifikacija;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
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
 * ({@code REQUIRES_NEW}, s vremenskim ograničenjem): zaključani outbox redak → zahtjev u
 * {@code str.verification_request} → obavijesti službenicima → zatvoren outbox redak. Ista baza, pa
 * je upis atomaran: bilo koja greška poništava i zahtjev i obavijesti, a outbox ostaje
 * {@code PENDING} za sljedeći pokušaj. Neuspjeh bilježi pozivatelj, u zasebnoj transakciji
 * ({@link VerifikacijaOutbox#recordFailure}).
 *
 * <p><b>Vremensko ograničenje</b> ({@code str.eturizam.verifikacija.timeout}, zadano 10 s): inline
 * pokušaj radi na niti HTTP zahtjeva, a retry job na jedinoj zajedničkoj niti {@code @Scheduled}
 * poslova. Bez ograničenja bi čekanje na zaključan redak (npr. eTurizamov nepotvrđeni INSERT za isti
 * dokument) objesilo odgovor ili zaustavilo i eGOP i mail retry. {@code JpaTransactionManager}
 * ograničenje prenosi na JDBC naredbe ({@code JdbcTemplate} → {@code setQueryTimeout}) i na JPA
 * upite, uključujući {@code SELECT … FOR UPDATE}; istek je obična greška pokušaja.
 *
 * <p><b>Tko smije pokušati.</b> Pokušaj nosi broj pokušaja koji je pozivatelj vidio; nakon
 * zaključavanja redak mora imati upravo toliko pokušaja, a za retry job i dospjeli rok. Inline
 * pokušaj (odmah nakon commita) smije prije roka, ali samo kao prvi. Tako dvije instance ili
 * zakašnjeli inline pokušaj ne obrađuju redak koji je drugi već obradio ili odgodio, a
 * {@link VerifikacijaOutbox#recordFailure} isti neuspjeh ne broji dvaput.
 *
 * <p>Zahtjev za dokument koji već postoji (bilo koji {@code active}) se ne mijenja i za njega se ne
 * šalju obavijesti — nije ga upisao STR. Zapisuje se samo njegov id (privremeno pravilo, potvrditi
 * s eTurizmom).
 */
@Component
public class EturizamVerifikacijaWriter {

    private static final Logger log = LoggerFactory.getLogger(EturizamVerifikacijaWriter.class);

    /** Zona baze eTurizma; {@code timestamp} stupci drže lokalno vrijeme (W0, 5. 11. 2026.). */
    static final ZoneId ETURIZAM_ZONE = ZoneId.of("Europe/Zagreb");

    /**
     * Naziv transakcije pokušaja. {@link EturizamVerifikacijaRepository} po njemu odbija poziv iz bilo
     * koje druge transakcije (npr. one koja izdaje RB).
     */
    static final String TX_NAME = "eturizam-verifikacija";

    /** {@code korisnicka_obavijest.vrsta} i {@code objekt_tip} su {@code varchar(64)}. */
    static final int MAX_SIFRA = 64;

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
    private final AccommodationRepository accommodationRepository;
    private final EturizamVerifikacijaProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;

    public EturizamVerifikacijaWriter(VerifikacijaOutboxRepository outboxRepository,
                                      EturizamVerifikacijaRepository eturizam,
                                      AccommodationRepository accommodationRepository,
                                      EturizamVerifikacijaProperties properties,
                                      Clock clock,
                                      PlatformTransactionManager transactionManager) {
        this.outboxRepository = outboxRepository;
        this.eturizam = eturizam;
        this.accommodationRepository = accommodationRepository;
        this.properties = properties;
        this.clock = clock;
        this.tx = transakcija(transactionManager, properties.timeout());
        provjeriSifre(properties.obavijesti());
    }

    /** Transakcija pokušaja: nova, imenovana ({@link #TX_NAME}), s ograničenjem trajanja (najmanje 1 s). */
    static TransactionTemplate transakcija(PlatformTransactionManager transactionManager, Duration timeout) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setName(TX_NAME);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setTimeout((int) Math.max(1, timeout.toSeconds()));
        return template;
    }

    /**
     * Preduga šifra obavijesti ne ruši start (obavijesti su sporedne), ali svaki bi pokušaj pao i nakon
     * {@code max-attempts} redak bi otišao u ABANDONED — zato ERROR odmah, s imenom ključa. Prazne
     * vrijednosti ne postoje: zamjenjuju ih zadane ({@link EturizamVerifikacijaProperties.Obavijesti}).
     */
    static boolean provjeriSifre(EturizamVerifikacijaProperties.Obavijesti obavijesti) {
        if (!obavijesti.enabled()) {
            return true;
        }
        boolean ispravno = true;
        if (obavijesti.vrsta().length() > MAX_SIFRA) {
            log.error("eturizam_verifikacija_config str.eturizam.verifikacija.obavijesti.vrsta ima {} znakova "
                    + "(najviše {}) — svaki upis obavijesti će pasti", obavijesti.vrsta().length(), MAX_SIFRA);
            ispravno = false;
        }
        if (obavijesti.objektTip().length() > MAX_SIFRA) {
            log.error("eturizam_verifikacija_config str.eturizam.verifikacija.obavijesti.objekt-tip ima {} znakova "
                    + "(najviše {}) — svaki upis obavijesti će pasti", obavijesti.objektTip().length(), MAX_SIFRA);
            ispravno = false;
        }
        return ispravno;
    }

    /**
     * @return ishod upisa; prazno kad pokušaja nije bilo: za RB nema retka na čekanju (verificiran ili
     * nov objekt), redak je obradio ili odgodio drugi pokušaj, ili mu (za job) rok još nije došao.
     * Prazno odvaja „preskočeno" od „obrađeno" — po tome to provjeravaju testovi.
     */
    public Optional<Status> upisi(Pokusaj pokusaj) {
        return tx.execute(status -> upisiUTransakciji(pokusaj));
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
        long documentId = zapis.getDocumentId();
        LocalDateTime sada = LocalDateTime.now(clock.withZone(ETURIZAM_ZONE));

        Optional<Long> postojeci = eturizam.findRequestId(documentId);
        if (postojeci.isPresent()) {
            zapis.complete(Status.ZAHTJEV_POSTOJAO, postojeci.get(), 0, clock.instant());
            outboxRepository.save(zapis);
            log.info("eturizam_verifikacija_postojao rn={} document={} verification_request={} — zahtjev za "
                    + "dokument već postoji, ne mijenja se i obavijesti se ne šalju", rn, documentId, postojeci.get());
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
     * Po jedna obavijest svakom aktivnom internom korisniku nadležne jedinice dokumenta. Bez jedinice
     * ili bez korisnika zahtjev ipak ostaje upisan — eTurizam ga vidi na listi — uz WARN.
     */
    private int obavijesti(VerifikacijaOutboxEntity zapis, long zahtjevId, LocalDateTime sada) {
        Optional<Long> jedinica = eturizam.findJurisdictionUnit(zapis.getDocumentId());
        if (jedinica.isEmpty()) {
            log.warn("eturizam_verifikacija_bez_jedinice rn={} document={} — dokument nema nadležnu jedinicu, "
                    + "obavijesti se ne šalju (zahtjev je upisan)", zapis.getRn(), zapis.getDocumentId());
            return 0;
        }
        List<Long> primatelji = eturizam.findRecipients(jedinica.get());
        if (primatelji.isEmpty()) {
            log.warn("eturizam_verifikacija_bez_primatelja rn={} document={} jedinica={} — jedinica nema aktivnih "
                            + "internih korisnika, obavijesti se ne šalju (zahtjev je upisan)",
                    zapis.getRn(), zapis.getDocumentId(), jedinica.get());
            return 0;
        }

        EturizamVerifikacijaProperties.Obavijesti postavke = properties.obavijesti();
        String tekst = VerifikacijaObavijest.tekst(postavke.tekst(), zapis.getRn(), opisObjekta(zapis));
        String podaci = VerifikacijaObavijest.podaci(
                zapis.getRn(), zapis.getDocumentId(), zapis.getFacilityId(), zahtjevId);
        for (Long korisnik : primatelji) {
            eturizam.insertNotification(korisnik, postavke.vrsta(), tekst, postavke.objektTip(), zahtjevId, podaci, sada);
        }
        return primatelji.size();
    }

    /** Naziv i mjesto iz STR smještaja — nakon dopune iz eTurizma ({@code completeFrom}) to je eTurizmov podatak. */
    private String opisObjekta(VerifikacijaOutboxEntity zapis) {
        AccommodationEntity smjestaj = accommodationRepository.findBySubmissionId(zapis.getSubmissionId())
                .stream().findFirst().orElse(null);
        String naziv = smjestaj == null ? null : smjestaj.getName();
        String mjesto = smjestaj == null ? null
                : smjestaj.getSettlement() != null ? smjestaj.getSettlement() : smjestaj.getCity();
        return VerifikacijaObavijest.opisObjekta(naziv, mjesto, zapis.getFacilityId());
    }
}
