package com.str.backend.egop;

import com.str.backend.document.StrDocumentService;
import com.str.backend.document.StrDocumentType;
import com.str.backend.rn.RnLifecycleLookup;
import com.str.backend.rn.event.RnLifecycleEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;
import java.util.UUID;

/**
 * Urudžbira akte koji nastaju iz promjene statusa registracijskog broja.
 *
 * <p>Redoslijed je namjeran i odgovara pravilu „kreiramo lokalno pa šaljemo eGOP-u":
 *
 * <ol>
 *   <li>renderira se PDF <b>iz stanja u trenutku događaja</b>,</li>
 *   <li>akt se zapiše u {@code egop_pismeno} sa statusom {@code NEW},</li>
 *   <li>tek onda ide SOAP.</li>
 * </ol>
 *
 * <p>Snimanje dokumenta u koraku 1 nije optimizacija nego ispravnost: {@code applyStatus}
 * briše {@code suspensionDeadline} pri izlasku iz {@code SUSPENDED}, a zadnji zapis
 * revizijskog traga se mijenja sa svakim novim prijelazom. Retry koji bi renderirao iznova
 * poslao bi drugi dokument pod istim urudžbenim brojem.
 *
 * <p>Pad u koraku 3 ne gubi ništa — akt je zapisan i {@link EgopRetryJob} ga pokupi.
 *
 * <p>Koraci 1 i 2 idu <b>i za vrste bez šifre u eGOP šifrarniku</b> ({@link EgopAktiBezSifre});
 * preskače se samo korak 3. Akt time postoji u {@code egop_pismeno}, pa se stranci prikazuje u
 * „Moji registracijski brojevi" i može se preuzeti — jednako kao zahtjev i obavijest o dodjeli.
 * Da se preskakalo i zapisivanje, dokument bi postojao samo kao privitak e-pošte.
 */
@Component
public class RnLifecycleFilingListener {

    private static final Logger log = LoggerFactory.getLogger(RnLifecycleFilingListener.class);

    private final RnLifecycleLookup lookup;
    private final StrDocumentService documentService;
    private final EgopFilingStore store;
    private final EgopAktDispatcher dispatcher;
    private final EgopAktiBezSifre bezSifre;

    public RnLifecycleFilingListener(RnLifecycleLookup lookup,
                                     StrDocumentService documentService,
                                     EgopFilingStore store,
                                     EgopAktDispatcher dispatcher,
                                     EgopAktiBezSifre bezSifre) {
        this.lookup = lookup;
        this.documentService = documentService;
        this.store = store;
        this.dispatcher = dispatcher;
        this.bezSifre = bezSifre;
    }

    /**
     * <b>Namjerno bez {@code @Transactional}.</b> Metoda zove eGOP (do ~7 SOAP poziva), pa bi
     * transakcija na njoj držala konekciju otvorenom kroz cijelo to čekanje — problem zbog
     * kojeg je uvedena {@link EgopFilingStore}. Sva čitanja i upisi ovdje idu kroz metode s
     * {@code REQUIRES_NEW} ({@link RnLifecycleLookup}, {@link StrDocumentService},
     * {@link EgopFilingStore}), što ujedno rješava i to da je u {@code AFTER_COMMIT} fazi
     * izvorna transakcija već dovršena.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLifecycleChange(RnLifecycleEvent event) {
        Optional<StrDocumentType> type = StrDocumentType.forTransition(
                event.to(), event.trigger(), event.initiatedByLessor());
        if (type.isEmpty()) {
            return;
        }
        UUID submissionId = lookup.findSubmissionId(event.rn()).orElse(null);
        if (submissionId == null) {
            log.error("egop_akt_skipped reason=missing_submission rn={} vrsta='{}'",
                    event.rn(), type.get().vrstaPismenaNaziv());
            return;
        }

        EgopPismenoEntity akt;
        try {
            byte[] pdf = documentService.render(type.get(), event.rn(), event.reason());
            akt = store.saveAkt(EgopPismenoEntity.forAct(
                    submissionId, event.rn(), actRef(event), type.get().vrstaPismenaNaziv(),
                    smjer(type.get()), pdf));
        } catch (RuntimeException e) {
            // Bez dokumenta nema urudžbe (uredsko poslovanje traži zapisnik uz svaki akt), a
            // status je već promijenjen — pad se mora vidjeti, ne progutati.
            log.error("egop_akt_not_recorded rn={} vrsta='{}': {} — akt nije zapisan pa ga"
                    + " ni retry neće pokupiti; urudžbirati ručno",
                    event.rn(), type.get().vrstaPismenaNaziv(), e.getMessage(), e);
            return;
        }

        if (!bezSifre.urudzbiv(type.get())) {
            // Akt je zapisan i vidljiv stranci; staje samo slanje, jer bi razrješavanje vrste
            // pismena palo i vrtjelo se do iscrpljenja pokušaja. EgopRetryJob ove akte izuzima
            // iz reda dok je slug na popisu — makne li se, urudžbirat će i ovaj zaostatak.
            log.info("egop_akt_not_filed reason=vrsta_bez_sifre akt={} rn={} vrsta='{}'"
                    + " — maknuti '{}' iz str.egop.akti-bez-sifre kad InfoDom potvrdi šifru",
                    akt.getId(), event.rn(), type.get().vrstaPismenaNaziv(), type.get().slug());
            return;
        }

        dispatcher.dispatch(akt.getId());
    }

    /**
     * Identitet akta je {@code log_id} prijelaza — po njemu se ponovljena suspenzija istog
     * RB-a razlikuje od prethodne. Ako ga nema (prijelaz bez zapisa), pada se na RB + vrijeme,
     * što je lošije ali i dalje jedinstveno.
     */
    private static String actRef(RnLifecycleEvent event) {
        return event.logId() != null
                ? event.logId().toString()
                : event.rn() + ":" + event.to() + ":" + System.currentTimeMillis();
    }

    private static EgopPismenoEntity.Smjer smjer(StrDocumentType type) {
        return type.smjer() == StrDocumentType.Smjer.ULAZNO
                ? EgopPismenoEntity.Smjer.ULAZNO
                : EgopPismenoEntity.Smjer.IZLAZNO;
    }
}
