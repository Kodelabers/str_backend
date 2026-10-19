package com.str.backend.egop;

import com.str.backend.egop.exception.EgopException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Zapisuje što se dogodilo s porukama otpremljenima u korisnički pretinac
 * ({@code DohvatiKPStatusPorukeSaVremenima}): je li primatelj zaprimio, potvrdio ili odbio
 * primitak i kada. Podaci se samo zapisuju na {@link EgopKpOtpremaEntity}; na API-ju se ne
 * izlažu (odluka od 19. 10. 2026.).
 *
 * <p>Poruka se provjerava dok status ne postane konačan (2 potvrđen, 3 odbijen primitak) ili
 * dok ne istekne {@code str.egop.kp.status.max-age} od otpreme.
 *
 * <p>Aktivan samo uz uključen eGOP <b>i</b> KP — s mockom bi samo upisivao izmišljena vremena.
 */
@Component
// Više imena uz isti havingValue = sva moraju biti "true". Izraz (@ConditionalOnExpression) bi
// uz praznu env varijablu dao neispravan SpEL i aplikacija ne bi krenula.
@ConditionalOnProperty(name = {"hr.infodom.str.integration.egop.enabled", "str.egop.kp.enabled"},
        havingValue = "true")
public class EgopKpStatusJob {

    private static final Logger log = LoggerFactory.getLogger(EgopKpStatusJob.class);

    private static final List<Integer> KONACNI = List.of(
            EgopKpOtpremaEntity.KP_POTVRDEN_PRIMITAK, EgopKpOtpremaEntity.KP_ODBIJENO);

    private final EgopKpOtpremaRepository repository;
    private final EgopKpOtpremaStore store;
    private final EgopFilingStore filingStore;
    private final EgopClient egopClient;
    private final String appUsername;
    private final Duration maxAge;
    private final int batchSize;

    public EgopKpStatusJob(EgopKpOtpremaRepository repository,
                           EgopKpOtpremaStore store,
                           EgopFilingStore filingStore,
                           EgopClient egopClient,
                           EgopProperties properties,
                           @Value("${str.egop.kp.status.max-age:P30D}") Duration maxAge,
                           @Value("${str.egop.retry.batch-size:50}") int batchSize) {
        this.repository = repository;
        this.store = store;
        this.filingStore = filingStore;
        this.egopClient = egopClient;
        this.appUsername = properties.qualifiedAppUsername();
        this.maxAge = maxAge;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${str.egop.kp.status.cron:0 15 * * * *}")
    public void refreshStatuses() {
        Instant now = Instant.now();
        List<UUID> candidates = repository.findStatusCandidates(EgopKpOtpremaEntity.Status.SENT, KONACNI,
                now.minus(maxAge), PageRequest.of(0, batchSize));
        if (candidates.isEmpty()) {
            return;
        }
        int failed = 0;
        for (UUID otpremaId : candidates) {
            if (!refresh(otpremaId, now)) {
                failed++;
            }
        }
        log.info("egop_kp_status_done processed={} failed={}", candidates.size(), failed);
    }

    /** @return {@code false} ako status nije dohvaćen; sljedeći prolaz pokušava ponovo */
    boolean refresh(UUID otpremaId, Instant now) {
        try {
            EgopKpOtpremaEntity otprema = store.find(otpremaId).orElse(null);
            EgopPismenoEntity pismeno = otprema == null ? null
                    : filingStore.findAkt(otprema.getPismenoId()).orElse(null);
            if (pismeno == null || pismeno.getJop() == null) {
                log.error("egop_kp_status_skipped reason=missing_pismeno otprema={}", otpremaId);
                return false;
            }
            KpStatusPoruke kp = egopClient.dohvatiKpStatusPoruke(appUsername, pismeno.getJop(),
                    otprema.getPrimateljOib());
            store.applyKpStatus(otpremaId, kp, now);
            if (!Objects.equals(otprema.getKpStatusPoruke(), kp.statusPoruke())) {
                log.info("egop_kp_status_changed otprema={} rn={} vrsta='{}' status={}->{}",
                        otpremaId, pismeno.getRn(), pismeno.getVrstaPismenaNaziv(),
                        otprema.getKpStatusPoruke(), kp.statusPoruke());
            }
            return true;
        } catch (EgopException | RuntimeException e) {
            log.warn("egop_kp_status_failed otprema={}: {}", otpremaId, e.getMessage());
            return false;
        }
    }
}
