package com.str.backend.str.verifikacija;

import com.str.backend.registration.event.RnIssuedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Prvi pokušaj upisa zahtjeva za žurnu verifikaciju, odmah nakon commita izdavanja RB-a.
 *
 * <p>Zaseban slušatelj, neovisan o {@code RnIssuedListener}-u (write-back RB-a i eGOP): ništa ne
 * baca, pa ne može preskočiti ni njih ni odgovor zahtjeva. Za RB bez outbox retka (nov ili
 * verificiran objekt) to je jedan {@code SELECT}. Što ovdje ne prođe, ponavlja
 * {@link EturizamVerifikacijaRetryJob}.
 */
@Component
public class EturizamVerifikacijaListener {

    private static final Logger log = LoggerFactory.getLogger(EturizamVerifikacijaListener.class);

    private final EturizamVerifikacijaService service;

    public EturizamVerifikacijaListener(EturizamVerifikacijaService service) {
        this.service = service;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRnIssued(RnIssuedEvent event) {
        try {
            service.obradi(EturizamVerifikacijaWriter.Pokusaj.inline(event.rn()));
        } catch (RuntimeException e) {
            // Servis ne baca; ovo je samo osigurač da upis u tuđi registar ne dira ostatak toka.
            log.error("eturizam_verifikacija_listener_failed rn={} — RB ostaje valjan", event.rn(), e);
        }
    }
}
