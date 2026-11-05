package com.str.backend.str.verifikacija;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Ponavlja upis zahtjeva za žurnu verifikaciju koji nije prošao odmah nakon izdavanja RB-a
 * ({@code str_rn.eturizam_verifikacija}, {@code PENDING} i dospio). Svaki redak ide kroz isti put
 * kao prvi pokušaj ({@link EturizamVerifikacijaService}), koji sam bilježi ishod i odustaje nakon
 * {@code str.eturizam.verifikacija.retry.max-attempts}.
 *
 * <p>Diže se samo uz {@code str.eturizam.verifikacija.enabled=true}. Kad se integracija uključi,
 * job obradi i retke nastale dok je bila ugašena.
 *
 * <p><b>Bez transakcije</b> preko serije: svaki redak ima svoju, vremenski ograničenu
 * ({@link EturizamVerifikacijaWriter}), pa zaključan redak ne zaustavlja ostale {@code @Scheduled} poslove
 * dulje od {@code str.eturizam.verifikacija.timeout}. Pokušaj nosi broj pokušaja iz ovog čitanja: redak
 * koji je u međuvremenu obradio drugi pokušaj (druga instanca, zakašnjeli inline) se preskače.
 */
@Component
@ConditionalOnProperty(name = "str.eturizam.verifikacija.enabled", havingValue = "true")
public class EturizamVerifikacijaRetryJob {

    private static final Logger log = LoggerFactory.getLogger(EturizamVerifikacijaRetryJob.class);

    private final VerifikacijaOutbox outbox;
    private final EturizamVerifikacijaService service;

    public EturizamVerifikacijaRetryJob(VerifikacijaOutbox outbox, EturizamVerifikacijaService service) {
        this.outbox = outbox;
        this.service = service;
    }

    @Scheduled(cron = "${str.eturizam.verifikacija.retry.cron:0 */5 * * * *}")
    public void retryDue() {
        List<VerifikacijaOutboxEntity> due = outbox.due();
        if (due.isEmpty()) {
            return;
        }
        log.info("eturizam_verifikacija_retry_start due={}", due.size());
        for (VerifikacijaOutboxEntity zapis : due) {
            service.obradi(EturizamVerifikacijaWriter.Pokusaj.izReda(zapis));
        }
        log.info("eturizam_verifikacija_retry_done processed={}", due.size());
    }
}
