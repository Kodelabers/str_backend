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

    /** Ishod obrade za pozivatelja; retry job po {@link #NEUSPJEH_INFRASTRUKTURA} prekida seriju. */
    public enum Ishod {
        /** Obrađeno, preskočeno ili integracija ugašena — ništa za ponoviti odmah. */
        GOTOVO,
        /** Pao je ovaj redak (podaci); ostali retci mogu proći. */
        NEUSPJEH,
        /** Istek vremena, zaključavanje, veza ili nedostaje GRANT — isto bi palo i na sljedećem retku. */
        NEUSPJEH_INFRASTRUKTURA
    }

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

    public Ishod obradi(Pokusaj pokusaj) {
        if (!properties.enabled()) {
            return Ishod.GOTOVO;
        }
        try {
            try {
                writer.upisi(pokusaj);
            } catch (DuplicateKeyException e) {
                if (!isDocumentConflict(e)) {
                    // Drugi sukob ključa (npr. PK, sekvenca identity stupca iza podataka) ponovni pokušaj
                    // ne rješava — obični neuspjeh.
                    throw e;
                }
                // Transakcija je poništena; eTurizam je zahtjev za dokument upisao između našeg SELECT-a i
                // INSERT-a. Novi pokušaj ga zatekne (ZAHTJEV_POSTOJAO).
                log.info("eturizam_verifikacija_utrka rn={} — zahtjev za dokument upisan istodobno, ponavljam jednom",
                        pokusaj.rn());
                writer.upisi(pokusaj);
            }
            return Ishod.GOTOVO;
        } catch (RuntimeException e) {
            // Namjerno se guta: RB je već izdan i commitan, a ovo je upis u tuđi registar.
            try {
                outbox.recordFailure(pokusaj.rn(), pokusaj.ocekivaniPokusaji(), e);
            } catch (RuntimeException recordError) {
                log.error("eturizam_verifikacija_record_failed rn={} — neuspjeh pokušaja ({}) nije zabilježen, redak "
                                + "ostaje PENDING s istim rokom: {}", pokusaj.rn(), VerifikacijaGreska.opis(e),
                        VerifikacijaGreska.opis(recordError));
                return Ishod.NEUSPJEH_INFRASTRUKTURA;
            }
            return VerifikacijaGreska.vrsta(e) == VerifikacijaGreska.Vrsta.PODACI
                    ? Ishod.NEUSPJEH : Ishod.NEUSPJEH_INFRASTRUKTURA;
        }
    }

    static boolean isDocumentConflict(DuplicateKeyException e) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains(UQ_DOKUMENT);
    }
}
