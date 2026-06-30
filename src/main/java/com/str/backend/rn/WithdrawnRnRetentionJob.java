package com.str.backend.rn;

import com.str.backend.admin.AdminAuditService;
import com.str.backend.domain.RnStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * STR-1.3 / čl. 5. st. 5: 18 mjeseci nakon opoziva sustav automatski briše informacije i
 * dokumentaciju opozvanih registracijskih brojeva.
 *
 * <p><b>Faza 1 (ova iteracija) — samo detekcija.</b> Pronalazi opozvane RB-ove kojima je
 * {@code valid_to} (dan povlačenja) stariji od retencijskog praga i upisuje revizijski zapis
 * ({@code RETENTION_DUE}); <b>ništa ne briše</b>. Time se opseg potvrđuje bez rizika.
 *
 * <p><b>TODO faza 2 (nakon poslovne/pravne potvrde opsega):</b> stvarno brisanje/anonimizacija
 * osobnih podataka (npr. {@code submission.pdf_content}, dokumenti iznajmljivača) + potvrda o
 * brisanju u KP (BX1). Opseg brisanja je otvoreno pitanje i namjerno NIJE implementiran ovdje.
 * Napomena: detekcija preskače opozvane RB-ove s {@code valid_to = NULL} (moguće u legacy/mock
 * podacima) — prije faze 2 napraviti backfill {@code valid_to} iz revizijskog loga povlačenja.
 */
@Component
public class WithdrawnRnRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(WithdrawnRnRetentionJob.class);

    private final RnRepository rnRepository;
    private final AdminAuditService auditService;
    private final Clock clock;
    private final int retentionMonths;

    public WithdrawnRnRetentionJob(RnRepository rnRepository,
                                   AdminAuditService auditService,
                                   Clock clock,
                                   @Value("${app.rn.retention-months:18}") int retentionMonths) {
        this.rnRepository = rnRepository;
        this.auditService = auditService;
        this.clock = clock;
        this.retentionMonths = retentionMonths;
    }

    @Scheduled(cron = "${app.rn.retention-cron:0 15 4 * * *}")
    @Transactional
    public void detectDueForRetention() {
        LocalDate cutoff = LocalDate.now(clock).minusMonths(retentionMonths);
        List<RnEntity> due = rnRepository.findByStatusAndValidToBefore(RnStatus.WITHDRAWN, cutoff);
        if (due.isEmpty()) {
            return;
        }
        log.info("rn_retention_due count={} cutoff={} (detekcija; brisanje je faza 2)", due.size(), cutoff);
        auditService.record(AdminAuditService.SYSTEM, "RETENTION_DUE", "RN", null,
                "count=" + due.size() + " cutoff=" + cutoff);
    }
}
