package com.str.backend.kp.api;

import com.str.backend.attachment.PrilogSadrzaj;
import com.str.backend.attachment.PrilogService;
import com.str.backend.egop.EgopPismenoEntity;
import com.str.backend.egop.EgopPismenoRepository;
import com.str.backend.kp.api.KpApiClient.Odgovor;
import com.str.backend.kp.api.KpApiClient.PorukaZahtjev;
import com.str.backend.kp.api.KpApiClient.Privitak;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Dostava akata u korisnički pretinac preko KP API-ja (T16) — outbox {@code str_rn.kp_poruka}.
 *
 * <ul>
 *   <li>{@link #zabiljezi} — u transakciji radnje: poruke nastaju zajedno s aktom i nakon commita se odmah
 *       šalju, izvan niti zahtjeva ({@link KpPorukaListener}).</li>
 *   <li>{@link #posaljiDospjele} — što nije prošlo (502, timeout, odbijena prijava, pun pool) šalje
 *       {@link KpApiRetryJob}.</li>
 *   <li>{@link #posalji} — jedan pokušaj: zauzmi poruku (kratka transakcija), pozovi KP API bez otvorene
 *       transakcije, zabilježi ishod (kratka transakcija). Nikad ne baca.</li>
 * </ul>
 *
 * <p>KP API isti {@code idPoruke} ne zaprima dvaput, pa ponavljanje nakon nepoznatog ishoda (502, timeout)
 * ne udvostručuje poruku.
 */
@Service
public class KpPorukaService {

    private static final Logger log = LoggerFactory.getLogger(KpPorukaService.class);

    private static final Pattern OIB = Pattern.compile("\\d{11}");

    private final KpPorukaRepository repository;
    private final EgopPismenoRepository pismenoRepository;
    private final PrilogService prilogService;
    private final KpApiClient client;
    private final KpApiExecutor executor;
    private final KpApiProperties properties;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final TransactionTemplate tx;

    public KpPorukaService(KpPorukaRepository repository,
                           EgopPismenoRepository pismenoRepository,
                           PrilogService prilogService,
                           KpApiClient client,
                           KpApiExecutor executor,
                           KpApiProperties properties,
                           ApplicationEventPublisher events,
                           Clock clock,
                           PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.pismenoRepository = pismenoRepository;
        this.prilogService = prilogService;
        this.client = client;
        this.executor = executor;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx.setTimeout(10);
    }

    /**
     * Bilježi poruke za akt, u transakciji koja akt stvara. Dok slanje nije uključeno i konfigurirano
     * ({@link KpApiProperties#aktivno}), ne bilježi ništa: akt ide samo e-poštom, a uključivanje kasnije
     * ne šalje stare akte.
     *
     * @return ID-evi poruka; prazno kad se ne šalje
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> zabiljezi(KpAkt akt) {
        if (!properties.aktivno()) {
            log.info("kp_api_preskoceno rn={} pismeno={} — slanje u KP API nije uključeno, akt ide samo e-poštom",
                    akt.rn(), akt.pismenoId());
            return List.of();
        }
        if (akt.oib() == null || !OIB.matcher(akt.oib()).matches()) {
            log.warn("kp_api_bez_oib rn={} pismeno={} — primatelj nema ispravan OIB, akt ide samo e-poštom",
                    akt.rn(), akt.pismenoId());
            return List.of();
        }
        int tip = properties.tipZa(akt.vrstaAkta());
        Instant now = clock.instant();
        List<KpPorukaPlan.Dio> dijelovi = KpPorukaPlan.izradi(akt, properties.maxVelicina());
        List<UUID> ids = new ArrayList<>(dijelovi.size());
        for (KpPorukaPlan.Dio d : dijelovi) {
            KpPorukaEntity e = repository.save(KpPorukaEntity.pending(akt.pismenoId(), d.dio(), akt.rn(), akt.oib(),
                    tip, akt.izdavateljZuRb(), d.naslov(), d.sadrzaj(), akt.klasa(), akt.urBroj(), d.akt(),
                    KpPorukaPlan.ime(akt.nazivAkta()), d.prilozi(), now));
            ids.add(e.getIdPoruke());
        }
        events.publishEvent(new KpPorukeZabiljezene(ids));
        log.info("kp_api_zabiljezeno rn={} pismeno={} tip={} poruka={} raspored={}",
                akt.rn(), akt.pismenoId(), tip, ids.size(), KpPorukaPlan.opis(dijelovi));
        return ids;
    }

    /** Šalje poruke izvan niti zahtjeva. */
    public void posaljiAsync(List<UUID> ids) {
        for (UUID id : ids) {
            executor.submit(() -> posalji(id));
        }
    }

    /**
     * Predaje poolu do {@code batch-size} dospjelih poruka (retry posao); vraća koliko ih je uzeto.
     * Ne šalje na niti posla: scheduler je zajednički (eGOP, noćni poslovi), a jedan poziv KP-a može čekati
     * {@code connect-timeout + read-timeout}. Poruku koja se i dalje šalje zauzeće štiti od dvostrukog slanja.
     */
    public int posaljiDospjele() {
        List<UUID> ids = repository.slobodne(clock.instant(), PageRequest.of(0, properties.batchSize()));
        if (!ids.isEmpty()) {
            log.info("kp_api_retry_start poruka={}", ids.size());
            posaljiAsync(ids);
        }
        return ids.size();
    }

    /** Jedan pokušaj slanja. Nikad ne baca. */
    public void posalji(UUID id) {
        try {
            KpPorukaEntity poruka = zauzmi(id);
            if (poruka == null) {
                return;
            }
            Odgovor odgovor;
            try {
                odgovor = client.posalji(zahtjev(poruka));
            } catch (RuntimeException e) {
                // PDF akta ili prilog nisu pročitani — puni uzrok ovdje, kratki u kp_poruka.greska.
                log.warn("kp_api_priprema_pala id_poruke={} rn={} dio={} pismeno={}", id, poruka.getRn(), poruka.getDio(),
                        poruka.getPismenoId(), e);
                odgovor = new Odgovor(KpApiClient.Ishod.GRESKA, 0,
                        KpApiClient.skrati("priprema: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
            zabiljeziIshod(id, odgovor);
        } catch (RuntimeException e) {
            log.error("kp_api_slanje_palo id_poruke={} — poruka ostaje na čekanju", id, e);
        }
    }

    private KpPorukaEntity zauzmi(UUID id) {
        return tx.execute(status -> {
            Instant now = clock.instant();
            KpPorukaEntity e = repository.lockById(id).orElse(null);
            if (e == null || !e.slobodna(now)) {
                return null;
            }
            e.zauzmi(now, now.plus(properties.zauzece()));
            return repository.save(e);
        });
    }

    private PorukaZahtjev zahtjev(KpPorukaEntity p) {
        List<Privitak> privici = new ArrayList<>();
        if (p.isAkt()) {
            byte[] pdf = pismenoRepository.findById(p.getPismenoId())
                    .map(EgopPismenoEntity::getPdfContent)
                    .orElseThrow(() -> new IllegalStateException("PDF akta nije pronađen"));
            privici.add(Privitak.pdf(p.getNazivAkta() != null ? p.getNazivAkta() : "akt.pdf", pdf));
        }
        for (Long prilogId : p.priloziIds()) {
            // zaPreuzimanje provjerava i da je prilog vezan uz isti RB.
            PrilogSadrzaj prilog = prilogService.zaPreuzimanje(p.getRn(), prilogId);
            privici.add(Privitak.pdf(KpPorukaPlan.ime(prilog.naziv()), prilog.sadrzaj()));
        }
        return new PorukaZahtjev(p.getIdPoruke(), p.getTipPoruke(), p.getOib(), p.getNaslov(), p.getSadrzaj(),
                p.getKlasa(), p.getUrBroj(), privici.isEmpty() ? null : privici);
    }

    private void zabiljeziIshod(UUID id, Odgovor odgovor) {
        tx.executeWithoutResult(status -> {
            Instant now = clock.instant();
            KpPorukaEntity e = repository.lockById(id).orElse(null);
            if (e == null || !e.isPending()) {
                return;
            }
            switch (odgovor.ishod()) {
                case POSLANO -> {
                    e.poslano(now);
                    log.info("kp_api_poslano id_poruke={} rn={} dio={}", id, e.getRn(), e.getDio());
                }
                case NEMA_PRETINCA, ODBIJENO, NEISPRAVNO -> {
                    e.konacno(KpPorukaEntity.Status.valueOf(odgovor.ishod().name()), odgovor.greska(), now);
                    log.warn("kp_api_konacno id_poruke={} rn={} ishod={} greska={} — akt je dostavljen e-poštom",
                            id, e.getRn(), odgovor.ishod(), odgovor.greska());
                }
                case NEAUTORIZIRAN -> {
                    // Prijava je konfiguracija, ne poruka: pokušaj se ne broji, poruka čeka da se prijava popravi.
                    e.odgodi(odgovor.greska(), now.plus(properties.backoffBase()), now);
                    log.error("kp_api_prijava_odbijena id_poruke={} rn={} http={} greska={} — provjeriti "
                                    + "STR_KP_API_USERNAME / STR_KP_API_PASSWORD; poruka čeka (pokušaj se ne broji), "
                                    + "sljedeći {}", id, e.getRn(), odgovor.httpStatus(), odgovor.greska(),
                            e.getNextAttemptAt());
                }
                case GRESKA -> {
                    int pokusaja = e.getAttempts() + 1;
                    e.neuspjeh(odgovor.greska(), now.plus(properties.odgoda(pokusaja)), now);
                    if (pokusaja >= properties.maxAttempts()) {
                        e.konacno(KpPorukaEntity.Status.ABANDONED, odgovor.greska(), now);
                        log.error("kp_api_exhausted id_poruke={} rn={} pokusaja={} greska={} — poruka NIJE poslana "
                                + "u korisnički pretinac", id, e.getRn(), pokusaja, odgovor.greska());
                    } else {
                        log.warn("kp_api_neuspjeh id_poruke={} rn={} pokusaj={} sljedeci={} greska={}",
                                id, e.getRn(), pokusaja, e.getNextAttemptAt(), odgovor.greska());
                    }
                }
            }
            repository.save(e);
        });
    }
}
