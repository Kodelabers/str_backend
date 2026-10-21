package com.str.backend.kp;

import com.str.backend.kp.client.KpAktivnost;
import com.str.backend.kp.client.KpClient;
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
import java.util.Set;
import java.util.UUID;

/**
 * Zapisuje što se dogodilo s porukama s pravnim učinkom u korisničkom pretincu (servis 129 za
 * FO, 128 za PS): je li primatelj zaprimio, potvrdio ili odbio primitak i kada, te potpisani PDF
 * potvrde primitka. Podaci se samo zapisuju na {@link KpOtpremaEntity}; na API-ju se ne izlažu
 * (odluka od 19. 10. 2026.).
 *
 * <p>Poruka se provjerava dok status ne postane konačan (2 potvrđen, 3 odbijen primitak) ili
 * dok ne istekne {@code str.kp.status.max-age} od slanja. Poruke bez pravnog učinka se ne pitaju —
 * KP za njih aktivnost ne vraća (A005/C005).
 */
@Component
// Više imena uz isti havingValue = sva moraju biti "true". Izraz (@ConditionalOnExpression) bi
// uz praznu env varijablu dao neispravan SpEL i aplikacija ne bi krenula.
@ConditionalOnProperty(name = {"hr.infodom.str.integration.egop.enabled", "str.kp.enabled"}, havingValue = "true")
public class KpStatusJob {

    private static final Logger log = LoggerFactory.getLogger(KpStatusJob.class);

    private static final List<Integer> KONACNI = List.of(
            KpOtpremaEntity.KP_POTVRDEN_PRIMITAK, KpOtpremaEntity.KP_ODBIJENO);

    /** Poruka nije označena kao poruka s pravnim učinkom — aktivnost se nikad neće moći dohvatiti. */
    private static final Set<String> NIJE_PSPU = Set.of("A005", "C005");

    private final KpOtpremaRepository repository;
    private final KpOtpremaStore store;
    private final KpClient client;
    private final Duration maxAge;
    private final int batchSize;

    public KpStatusJob(KpOtpremaRepository repository,
                       KpOtpremaStore store,
                       KpClient client,
                       @Value("${str.kp.status.max-age:P30D}") Duration maxAge,
                       @Value("${str.egop.retry.batch-size:50}") int batchSize) {
        this.repository = repository;
        this.store = store;
        this.client = client;
        this.maxAge = maxAge;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${str.kp.status.cron:0 15 * * * *}")
    public void refreshStatuses() {
        Instant now = Instant.now();
        List<UUID> candidates = repository.findStatusCandidates(KpOtpremaEntity.Status.SENT, KONACNI,
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
        log.info("kp_status_done processed={} failed={}", candidates.size(), failed);
    }

    /** @return {@code false} ako status nije dohvaćen; sljedeći prolaz pokušava ponovo */
    boolean refresh(UUID otpremaId, Instant now) {
        try {
            KpOtpremaEntity otprema = store.find(otpremaId).orElse(null);
            if (otprema == null) {
                return false;
            }
            KpAktivnost aktivnost = client.aktivnost(otprema.primatelj(), otprema.getIdPoruke().toString());
            if (!aktivnost.ok()) {
                // Pomakni vrijeme provjere da poruka ne blokira ostale u sljedećem prolazu.
                store.markChecked(otpremaId, now);
                if (aktivnost.sifre().stream().anyMatch(NIJE_PSPU::contains)) {
                    log.warn("kp_status_not_pspu otprema={} sifre={} — KP poruku ne vodi kao poruku s pravnim"
                            + " učinkom; provjeriti registraciju e-poruke", otpremaId, aktivnost.sifre());
                } else {
                    // A004/C003 (poruka nije nađena) znaju kasniti za slanjem — nastavlja se do max-age.
                    log.warn("kp_status_rejected otprema={} sifre={}: {}", otpremaId, aktivnost.sifre(),
                            aktivnost.opis());
                }
                return false;
            }
            store.applyAktivnost(otpremaId, aktivnost, now);
            if (aktivnost.status() != null && !Objects.equals(otprema.getKpStatusAktivnosti(), aktivnost.status())) {
                log.info("kp_status_changed otprema={} primatelj_tip={} status={}->{} potvrda_pdf={}",
                        otpremaId, otprema.getPrimateljTip(), otprema.getKpStatusAktivnosti(), aktivnost.status(),
                        aktivnost.pdfPotvrde() != null);
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("kp_status_failed otprema={}: {}", otpremaId, e.getMessage());
            return false;
        }
    }
}
