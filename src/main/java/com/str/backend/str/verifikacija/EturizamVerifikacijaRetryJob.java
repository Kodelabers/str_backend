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
 *
 * <p><b>Prvi infrastrukturni neuspjeh prekida seriju</b> (istek vremena, zaključavanje, veza, nedostaje
 * GRANT — {@link EturizamVerifikacijaService.Ishod#NEUSPJEH_INFRASTRUKTURA}): isto bi palo na svakom
 * sljedećem retku, a serija od 50 redaka po 10 s zauzela bi zajedničku nit {@code @Scheduled} poslova
 * (eGOP, mail retry) osam minuta. Ostatak čeka sljedeći prolazak. Neuspjeh jednog retka zbog podataka
 * seriju ne prekida.
 *
 * <p>Cron ({@code str.eturizam.verifikacija.retry.cron}) ima zadanu vrijednost samo u
 * {@code application.properties}: nije dio {@link EturizamVerifikacijaProperties}.
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

    @Scheduled(cron = "${str.eturizam.verifikacija.retry.cron}")
    public void retryDue() {
        List<VerifikacijaOutboxEntity> due = outbox.due();
        if (due.isEmpty()) {
            return;
        }
        log.info("eturizam_verifikacija_retry_start due={}", due.size());
        int obradeno = 0;
        for (VerifikacijaOutboxEntity zapis : due) {
            obradeno++;
            if (service.obradi(EturizamVerifikacijaWriter.Pokusaj.izReda(zapis))
                    == EturizamVerifikacijaService.Ishod.NEUSPJEH_INFRASTRUKTURA) {
                log.warn("eturizam_verifikacija_retry_aborted processed={} remaining={} — infrastrukturni neuspjeh, "
                        + "ostatak serije čeka sljedeći prolazak", obradeno, due.size() - obradeno);
                return;
            }
        }
        log.info("eturizam_verifikacija_retry_done processed={}", obradeno);
    }
}
