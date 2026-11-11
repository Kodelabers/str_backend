package com.str.backend.kp.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Šalje poruke u KP koje nisu otišle odmah nakon radnje: nastale u noćnom poslu, odbijene iz pool-a,
 * neuspjele (502, timeout, veza) ili odgođene zbog odbijene prijave. Svaka poruka ide kroz
 * {@link KpPorukaService#posalji}, koji sam bilježi ishod i odustaje nakon {@code str.kp-api.max-attempts}.
 *
 * <p>Diže se samo uz {@code str.kp-api.enabled=true}. Cron ({@code str.kp-api.retry.cron}) ima zadanu vrijednost
 * samo u {@code application.properties}. Slanje ide u pool ({@link KpApiExecutor}), ne na niti posla, jer
 * scheduler dijele svi poslovi aplikacije.
 */
@Component
@ConditionalOnProperty(name = "str.kp-api.enabled", havingValue = "true")
class KpApiRetryJob {

    private static final Logger log = LoggerFactory.getLogger(KpApiRetryJob.class);

    private final KpPorukaService service;

    KpApiRetryJob(KpPorukaService service) {
        this.service = service;
    }

    @Scheduled(cron = "${str.kp-api.retry.cron}")
    void posaljiDospjele() {
        try {
            int predano = service.posaljiDospjele();
            if (predano > 0) {
                log.info("kp_api_retry_done predano={}", predano);
            }
        } catch (RuntimeException e) {
            log.error("kp_api_retry_failed — serija nije obrađena, ponavlja se u sljedećem prolazu", e);
        }
    }
}
