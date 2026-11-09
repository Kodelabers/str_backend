package com.str.backend.str.verifikacija;

import com.str.backend.egop.EgopRetryPolicy;
import com.str.backend.str.FacilityClaimVerifier;
import com.str.backend.str.StrFacilityRepository.FacilityOwnershipRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Outbox zahtjeva za žurnu verifikaciju ({@code str_rn.eturizam_verifikacija}).
 *
 * <p>{@link #zabiljezi} se zove iz transakcije izdavanja RB-a, a {@link #zabiljeziNoviObjekt} iz
 * transakcije predaje rješenja novog objekta; obje joj se <b>priključuju</b>: redak i RB (odnosno
 * rješenje) nastaju ili ne nastaju zajedno, pa nakon pada između commita i upisa u eTurizam ništa ne
 * ostaje zaboravljeno. Sam upis u {@code str.*} tu se nikad ne radi — tek nakon commita
 * ({@link EturizamVerifikacijaListener}) ili iz {@link EturizamVerifikacijaRetryJob}-a.
 *
 * <p>Bilježenje neuspjeha i čitanje dospjelih redaka imaju vlastitu kratku transakciju
 * ({@code REQUIRES_NEW}), kao {@code MailRetryStore}: neuspjeli pokušaj je svoju transakciju
 * upravo poništio. Bilježenje zaključava redak, pa ima isto vremensko ograničenje kao pokušaj iz
 * joba ({@code str.eturizam.verifikacija.timeout}). Backoff dijeli logiku s urudžbiranjem
 * ({@link EgopRetryPolicy}), s vlastitim postavkama {@code str.eturizam.verifikacija.retry.*}.
 */
@Component
public class VerifikacijaOutbox {

    private static final Logger log = LoggerFactory.getLogger(VerifikacijaOutbox.class);

    /** Oznaka u {@code last_error} retka koji čeka GRANT — po njoj se ERROR logira samo prvi put. */
    static final String BEZ_PRAVA = "bez_prava: ";

    private final VerifikacijaOutboxRepository repository;
    private final Clock clock;
    private final EgopRetryPolicy policy;
    private final EturizamVerifikacijaProperties properties;
    private final EturizamVerifikacijaProperties.Retry retry;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;

    public VerifikacijaOutbox(VerifikacijaOutboxRepository repository,
                              Clock clock,
                              EturizamVerifikacijaProperties properties,
                              PlatformTransactionManager transactionManager,
                              ApplicationEventPublisher events) {
        this.repository = repository;
        this.clock = clock;
        this.properties = properties;
        this.events = events;
        this.retry = properties.retry();
        this.policy = new EgopRetryPolicy(retry.maxAttempts(), retry.backoffBase(), retry.backoffMax());
        this.tx = EturizamVerifikacijaWriter.transakcija(transactionManager, properties.timeout());
    }

    /**
     * Bilježi da eTurizmu treba upisati zahtjev za žurnu verifikaciju — samo kad je RB izdan za
     * objekt iz eTurizma koji u tom trenutku <b>nije verificiran</b> ({@code verified = false}).
     * Novi objekt ({@code facility == null}) i verificiran objekt ne ostavljaju trag. Redak nastaje
     * i dok je integracija ugašena: jeftin je, a pokazuje što je čekalo.
     *
     * <p>Uz redak se pamte naziv i mjesto objekta <b>iz eTurizma</b> (ne iz unosa u STR-u) — za
     * tekst obavijesti. Naziv ide kroz {@link FacilityClaimVerifier#objectName}, koji odbacuje
     * popunjivače i ime vlasnika.
     *
     * <p>{@link Propagation#MANDATORY}: redak mora nastati u transakciji koja izdaje RB, nikad sam.
     *
     * <p>Bez ispravnog {@code documentId} zahtjev se ne može upisati ({@code source_document_id} je
     * FK). {@code findOwnership} dokument spaja obaveznim joinom, pa {@code null} u praksi ne stiže;
     * {@code ≤ 0} štiti od lažnih vrijednosti (npr. Mockito {@code 0L}). Tada retka nema, samo WARN.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void zabiljezi(String rn, UUID submissionId, String facilityId, FacilityOwnershipRow facility) {
        if (facility == null || !Boolean.FALSE.equals(facility.getVerified())) {
            return;
        }
        Long documentId = facility.getDocumentId();
        if (documentId == null || documentId <= 0) {
            log.warn("eturizam_verifikacija_bez_dokumenta rn={} facility={} document={} — neverificiran objekt "
                    + "nema ispravan document_id, zahtjev za žurnu verifikaciju se ne može upisati",
                    rn, facilityId, documentId);
            return;
        }
        String mjesto = FacilityClaimVerifier.knownOrNull(facility.getSettlementName());
        if (mjesto == null) {
            mjesto = FacilityClaimVerifier.knownOrNull(facility.getMunicipalityName());
        }
        Instant now = clock.instant();
        // Prvi pokušaj je inline nakon commita; job redak smije uzeti tek nakon backoff-base, da ga ne preteče.
        repository.save(VerifikacijaOutboxEntity.pending(rn, submissionId, facilityId, documentId,
                FacilityClaimVerifier.objectName(facility), mjesto, now, policy.nextAttemptAt(now, 0)));
        log.info("eturizam_verifikacija_zabiljezeno rn={} facility={} document={}", rn, facilityId, documentId);
    }

    /**
     * Bilježi obavijest o žurnoj verifikaciji za <b>novi objekt</b> uz čiji je RB upravo predano rješenje
     * o kategorizaciji. Objekta u eTurizmu nema, pa ni dokumenta: nakon commita se ne upisuje zahtjev,
     * nego samo obavijesti službenicima nadležne županije ({@link VerifikacijaOutboxEntity.Vrsta#NOVI_OBJEKT_RJESENJE}).
     * Naziv, mjesto i županija su iz zahtjeva za RB (STR); naziv ide kroz {@link FacilityClaimVerifier#knownOrNull}.
     *
     * <p><b>Jedna poslana obavijest po RB-u</b> ({@code rn} je UNIQUE, kao i za objekt iz eTurizma):
     * <ul>
     *   <li>obavijest je već poslana ({@code OBAVIJESTI_UPISANE}) — ponovna predaja nakon odbijenog
     *       rješenja ne šalje novu: službenici za objekt već znaju, a novo rješenje vide na internom
     *       portalu STR-a;</li>
     *   <li>obavijest još nije poslana (redak čeka, preskočen jer je prethodno rješenje u međuvremenu
     *       odbijeno, ili napušten) — redak se vraća na čekanje uz novo rješenje
     *       ({@link VerifikacijaOutboxEntity#ponovoNaCekanje}). Inače bi službenici za novo rješenje
     *       ostali bez obavijesti.</li>
     * </ul>
     * Postojeći redak se čita zaključan: pokušaj koji ga upravo obrađuje (inline ili job) završi prije, pa
     * se odluka donosi nad njegovim ishodom.
     *
     * <p>Nakon commita predaje prvi pokušaj radi {@link EturizamVerifikacijaListener} (događaj
     * {@link RjesenjeNovogObjektaPredano}); redak nastaje i dok je integracija ugašena.
     *
     * <p>{@link Propagation#MANDATORY}: redak mora nastati u transakciji koja sprema rješenje, nikad sam.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void zabiljeziNoviObjekt(String rn, UUID submissionId, UUID decisionId, String zupanija,
                                    String naziv, String mjesto) {
        Instant now = clock.instant();
        VerifikacijaOutboxEntity postojeci = repository.lockByRn(rn).orElse(null);
        if (postojeci != null) {
            if (!postojeci.isNoviObjekt() || postojeci.getStatus() == VerifikacijaOutboxEntity.Status.OBAVIJESTI_UPISANE) {
                log.info("eturizam_verifikacija_novi_vec_zabiljezeno rn={} decision={} status={} — za RB je obavijest "
                        + "već poslana, nova se ne šalje", rn, decisionId, postojeci.getStatus());
                return;
            }
            UUID prethodno = postojeci.getDecisionId();
            postojeci.ponovoNaCekanje(decisionId, now, policy.nextAttemptAt(now, 0));
            repository.save(postojeci);
            events.publishEvent(new RjesenjeNovogObjektaPredano(rn));
            log.info("eturizam_verifikacija_novi_ponovo rn={} decision={} prethodno={} — obavijest još nije poslana, "
                    + "redak čeka uz novo rješenje", rn, decisionId, prethodno);
            return;
        }
        repository.save(VerifikacijaOutboxEntity.pendingNoviObjekt(rn, submissionId, decisionId, zupanija,
                FacilityClaimVerifier.knownOrNull(naziv), FacilityClaimVerifier.knownOrNull(mjesto), now,
                policy.nextAttemptAt(now, 0)));
        events.publishEvent(new RjesenjeNovogObjektaPredano(rn));
        log.info("eturizam_verifikacija_novi_zabiljezeno rn={} decision={} zupanija={}", rn, decisionId, zupanija);
    }

    /**
     * Pokušaj nije uspio — zakazuje sljedeći, a nakon zadnjeg odustaje. Ovo je jedini zapis neuspjeha
     * u logu: WARN (ERROR pri odustajanju) s očišćenim razlogom ({@link VerifikacijaGreska#opis});
     * puni stack trace, koji može nositi vrijednosti retka, samo na DEBUG.
     *
     * <p>Broji se samo ako redak još ima {@code ocekivaniPokusaji} pokušaja: kad dvije instance (ili
     * zakašnjeli inline pokušaj i job) padnu na istom pokušaju, drugi neuspjeh se ne broji ponovno.
     *
     * <p>Nedostaje li GRANT ({@link VerifikacijaGreska.Vrsta#BEZ_PRAVA}), pokušaj se ne broji: redak
     * ostaje {@code PENDING} i odgađa se za {@code retry.backoff-max}; ERROR
     * {@code eturizam_verifikacija_bez_prava} ide samo prvi put za taj redak.
     *
     * @param ocekivaniPokusaji broj pokušaja koji je neuspjeli pokušaj vidio
     */
    public void recordFailure(String rn, int ocekivaniPokusaji, Throwable cause) {
        tx.executeWithoutResult(status -> zabiljeziNeuspjeh(rn, ocekivaniPokusaji, cause));
    }

    private void zabiljeziNeuspjeh(String rn, int ocekivaniPokusaji, Throwable cause) {
        String reason = VerifikacijaGreska.opis(cause);
        log.debug("eturizam_verifikacija_failed_trace rn={}", rn, cause);
        VerifikacijaOutboxEntity e = repository.lockByRn(rn).orElse(null);
        if (e == null || !e.isPending() || e.getAttempts() != ocekivaniPokusaji) {
            log.info("eturizam_verifikacija_failed_ignored rn={} ocekivano={} — neuspjeh je već zabilježio drugi "
                    + "pokušaj ili je redak obrađen: {}", rn, ocekivaniPokusaji, reason);
            return;
        }
        Instant now = clock.instant();
        if (VerifikacijaGreska.vrsta(cause) == VerifikacijaGreska.Vrsta.BEZ_PRAVA) {
            boolean prviPut = e.getLastError() == null || !e.getLastError().startsWith(BEZ_PRAVA);
            e.postpone(now, now.plus(retry.backoffMax()), BEZ_PRAVA + reason);
            if (prviPut) {
                log.error("eturizam_verifikacija_bez_prava rn={} document={} reason={} — nedostaje GRANT na shemi "
                                + "str (DEPLOY-PREPROD.md 2e); redak čeka PENDING bez trošenja pokušaja, sljedeći "
                                + "pokušaj {}", rn, e.getDocumentId(), reason, e.getNextAttemptAt());
            } else {
                log.warn("eturizam_verifikacija_bez_prava rn={} — i dalje bez prava, sljedeći pokušaj {}",
                        rn, e.getNextAttemptAt());
            }
            repository.save(e);
            return;
        }
        e.recordFailure(now, policy.nextAttemptAt(now, e.getAttempts()), reason);
        if (policy.isExhausted(e.getAttempts())) {
            e.abandon(now, "exhausted: " + reason);
            log.error("eturizam_verifikacija_exhausted rn={} document={} facility={} attempts={} reason={} — "
                            + "zahtjev za žurnu verifikaciju NIJE upisan u eTurizam, potrebna je ručna intervencija "
                            + "(povrat u red: DEPLOY-*.md)",
                    rn, e.getDocumentId(), e.getFacilityId(), e.getAttempts(), reason);
        } else {
            log.warn("eturizam_verifikacija_failed rn={} document={} attempt={} next_attempt_at={} reason={} — "
                            + "RB ostaje valjan, pokušaj se ponavlja",
                    rn, e.getDocumentId(), e.getAttempts(), e.getNextAttemptAt(), reason);
        }
        repository.save(e);
    }

    /**
     * Dospjeli {@code PENDING} retci, najstariji rok prvi. Dok su obavijesti ugašene, retci novog
     * objekta se ne uzimaju: bez obavijesti nemaju što upisati, pa bi pokušaj ostavio redak netaknut, a
     * s najstarijim rokom bi zauvijek popunjavali seriju ispred redaka objekata iz eTurizma.
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<VerifikacijaOutboxEntity> due() {
        Set<VerifikacijaOutboxEntity.Vrsta> vrste = properties.obavijesti().enabled()
                ? EnumSet.allOf(VerifikacijaOutboxEntity.Vrsta.class)
                : EnumSet.of(VerifikacijaOutboxEntity.Vrsta.ETURIZAM_OBJEKT);
        return repository.findByStatusAndVrstaInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                VerifikacijaOutboxEntity.Status.PENDING, vrste, clock.instant(), PageRequest.of(0, retry.batchSize()));
    }
}
