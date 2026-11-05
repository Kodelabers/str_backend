package com.str.backend.str.verifikacija;

import com.str.backend.str.verifikacija.EturizamVerifikacijaWriter.Pokusaj;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Obrađuje outbox redak RB-a: upis zahtjeva za žurnu verifikaciju (T8) i obavijesti službenicima u
 * eTurizam. Zovu ga {@link EturizamVerifikacijaListener} (odmah nakon izdavanja RB-a,
 * {@link Pokusaj#inline}) i {@link EturizamVerifikacijaRetryJob} ({@link Pokusaj#izReda}).
 *
 * <p><strong>Nikad ne baca.</strong> RB je izdan i valjan neovisno o upisu u tuđi registar, kao kod
 * write-backa RB-a i eGOP dostave. Neuspjeh se bilježi na outbox retku ({@link VerifikacijaOutbox}
 * ga i logira, jednom) i ponavlja s backoffom.
 *
 * <p>Dok je {@code str.eturizam.verifikacija.enabled=false} ne radi ništa: redak ostaje
 * {@code PENDING} i ne troši pokušaje.
 */
@Service
public class EturizamVerifikacijaService {

    private static final Logger log = LoggerFactory.getLogger(EturizamVerifikacijaService.class);

    /** UNIQUE na dokumentu u eTurizmu — jedini sukob ključa koji znači „netko je upisao zahtjev prije nas". */
    static final String UQ_DOKUMENT = "uq_verification_request_source_document";

    private final EturizamVerifikacijaWriter writer;
    private final VerifikacijaOutbox outbox;
    private final EturizamVerifikacijaProperties properties;

    public EturizamVerifikacijaService(EturizamVerifikacijaWriter writer,
                                       VerifikacijaOutbox outbox,
                                       EturizamVerifikacijaProperties properties) {
        this.writer = writer;
        this.outbox = outbox;
        this.properties = properties;
    }

    public void obradi(Pokusaj pokusaj) {
        if (!properties.enabled()) {
            return;
        }
        try {
            try {
                writer.upisi(pokusaj);
            } catch (DuplicateKeyException e) {
                // Transakcija je poništena. Kad je sukob na UNIQUE dokumenta, eTurizam je zahtjev upisao
                // između našeg SELECT-a i INSERT-a: novi pokušaj ga zatekne (ZAHTJEV_POSTOJAO). Drugi sukob
                // (npr. PK, sekvenca identity stupca iza podataka) ponovni pokušaj neće riješiti, pa padne
                // i bilježi se kao običan neuspjeh.
                log.info("eturizam_verifikacija_duplicate_key rn={} ({}) — ponavljam jednom", pokusaj.rn(),
                        isDocumentConflict(e) ? "zahtjev za dokument upisan istodobno" : "sukob ključa");
                writer.upisi(pokusaj);
            }
        } catch (RuntimeException e) {
            // Namjerno se guta: RB je već izdan i commitan, a ovo je upis u tuđi registar.
            try {
                outbox.recordFailure(pokusaj.rn(), pokusaj.ocekivaniPokusaji(), e);
            } catch (RuntimeException recordError) {
                recordError.addSuppressed(e);
                log.error("eturizam_verifikacija_record_failed rn={} — neuspjeh pokušaja nije zabilježen, redak "
                        + "ostaje PENDING s istim rokom", pokusaj.rn(), recordError);
            }
        }
    }

    static boolean isDocumentConflict(DuplicateKeyException e) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains(UQ_DOKUMENT);
    }
}
