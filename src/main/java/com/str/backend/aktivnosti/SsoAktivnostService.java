package com.str.backend.aktivnosti;

import com.str.backend.aktivnosti.dto.SdepIngestRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * STR §10: SDEP ingestion (3.1) + nadležno tijelo query (3.2) + auto-purge (3.3, 18 mj).
 */
@Service
public class SsoAktivnostService {

    private static final Logger log = LoggerFactory.getLogger(SsoAktivnostService.class);

    private final SsoAktivnostRepository repository;

    public SsoAktivnostService(SsoAktivnostRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public int ingest(SdepIngestRequest req) {
        for (SdepIngestRequest.Stavka s : req.getStavke()) {
            SsoAktivnostEntity e = SsoAktivnostEntity.ingest(
                    req.getIdPlatforme(), s.getRb(), s.getIdSso(),
                    s.getRazdobljeOd(), s.getRazdobljeDo(),
                    s.getBrojNocenja(), s.getBrojGostiju(), s.getDrzavaGostiju());
            repository.save(e);
        }
        log.info("sdep_ingest platforma={} count={}", req.getIdPlatforme(), req.getStavke().size());
        return req.getStavke().size();
    }

    @Transactional(readOnly = true)
    public List<SsoAktivnostEntity> pretrazi(Long idPlatforme, String rb, LocalDate od, LocalDate doDate) {
        return repository.search(idPlatforme, rb, od, doDate);
    }

    @Transactional
    public int purgeExpired() {
        int n = repository.purgeExpired(Instant.now());
        if (n > 0) log.info("sdep_purge removed={}", n);
        return n;
    }
}
