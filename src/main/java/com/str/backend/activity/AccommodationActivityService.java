package com.str.backend.activity;

import com.str.backend.activity.dto.SdepIngestRequest;
import com.str.backend.admin.AdminAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * STR §10: SDEP ingestion (3.1) + competent authority query (3.2) + auto-purge (3.3, 18 months).
 */
@Service
public class AccommodationActivityService {

    private static final Logger log = LoggerFactory.getLogger(AccommodationActivityService.class);

    private final AccommodationActivityRepository repository;
    private final AdminAuditService auditService;

    public AccommodationActivityService(AccommodationActivityRepository repository,
                                        AdminAuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Transactional
    public int ingest(SdepIngestRequest req) {
        for (SdepIngestRequest.Entry s : req.getEntries()) {
            AccommodationActivityEntity e = AccommodationActivityEntity.ingest(
                    req.getPlatformId(), s.getRn(), s.getAccommodationId(),
                    s.getPeriodFrom(), s.getPeriodTo(),
                    s.getNumberOfNights(), s.getNumberOfGuests(), s.getGuestCountries());
            repository.save(e);
        }
        log.info("sdep_ingest platforma={} count={}", req.getPlatformId(), req.getEntries().size());
        return req.getEntries().size();
    }

    @Transactional(readOnly = true)
    public List<AccommodationActivityEntity> search(Long platformId, String rn, LocalDate od, LocalDate toDate) {
        return repository.search(platformId, rn, od, toDate);
    }

    @Transactional
    public int purgeExpired() {
        int n = repository.purgeExpired(Instant.now());
        if (n > 0) {
            log.info("sdep_purge removed={}", n);
            // STR-3.3: brisanje podataka o aktivnosti uz revizijski log (čl. 5. st. 5, čl. 9).
            auditService.record(AdminAuditService.SYSTEM, "ACTIVITY_PURGE", "ACTIVITY", null,
                    "removed=" + n);
        }
        return n;
    }
}
