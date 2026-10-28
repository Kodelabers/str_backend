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
 * <p><b>Opseg statusa:</b> {@link #STATUSES} — {@code REVOKED} (opoziv iznajmljivača, na koji se
 * čl. 5. st. 5 odnosi) <b>i</b> {@code WITHDRAWN} (povlačenje po službenoj dužnosti). Do uvođenja
 * {@code REVOKED} oba su završavala u {@code WITHDRAWN}, pa je posao uvijek obuhvaćao i službena
 * povlačenja; ona ostaju u opsegu da se ponašanje za postojeće podatke ne promijeni.
 * TODO (potvrditi s klijentom): briše li se nakon 18 mjeseci i povučeni RB ili samo opozvani —
 * ako samo opozvani, {@code WITHDRAWN} maknuti iz {@link #STATUSES}.
 *
 * <p><b>Faza 1 (ova iteracija) — samo detekcija.</b> Pronalazi opozvane i povučene RB-ove kojima
 * je {@code valid_to} (dan opoziva/povlačenja) stariji od retencijskog praga i upisuje revizijski
 * zapis ({@code RETENTION_DUE}); <b>ništa ne briše</b>. Time se opseg potvrđuje bez rizika.
 *
 * <p><b>TODO faza 2 (nakon poslovne/pravne potvrde opsega):</b> stvarno brisanje/anonimizacija
 * osobnih podataka (npr. {@code submission.pdf_content}, dokumenti iznajmljivača, kontakt na snapshotu
 * iznajmljivača — {@code lessor.email/mobile_number/phone_number/contact_name}) + potvrda o
 * brisanju u KP (BX1). Opseg brisanja je otvoreno pitanje i namjerno NIJE implementiran ovdje.
 * Napomena: detekcija preskače opozvane RB-ove s {@code valid_to = NULL} (moguće u legacy/mock
 * podacima) — prije faze 2 napraviti backfill {@code valid_to} iz revizijskog loga povlačenja.
 * Faza 2 ne smije obrisati RB koji je još upisan u {@code str.facility.registration_number}: popis
 * objekata i write-back povučeni broj prepoznaju po retku ovdje, pa bi takav broj opet blokirao
 * novi zahtjev za objekt. Prije brisanja ga ukloniti iz eTurizma
 * ({@code FacilityRegistrationNumberWriteBack#clear}).
 *
 * <p><b>Faza 2 i ponovni zahtjev nakon opoziva (T15, {@code ReissueService}):</b> rješenje o
 * kategorizaciji povučenog RB-a može biti preneseno na novi RB istog objekta. Kopija ima
 * <b>vlastitu</b> datoteku i metapodatke, pa se izvorni zapis (i njegova datoteka) <b>smije</b>
 * obrisati po isteku retencije kao i ostala dokumentacija povučenog RB-a (čl. 5. st. 5).
 * {@code categorization_decision.source_decision_id} kopije tada ostaje viseći — namjerno, bez FK-a;
 * isto vrijedi za {@code accommodation.previous_rn} novog smještaja. Ponovni zahtjev i retencija
 * dijele {@code app.rn.retention-months}, pa u trenutku brisanja ponovni zahtjev ionako više nije moguć.
 */
@Component
public class WithdrawnRnRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(WithdrawnRnRetentionJob.class);

    /** Konačni statusi čiji podaci podliježu retenciji — v. opis razreda. */
    static final List<RnStatus> STATUSES = List.of(RnStatus.REVOKED, RnStatus.WITHDRAWN);

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
        List<RnEntity> due = rnRepository.findByStatusInAndValidToBefore(STATUSES, cutoff);
        if (due.isEmpty()) {
            return;
        }
        log.info("rn_retention_due count={} cutoff={} (detekcija; brisanje je faza 2)", due.size(), cutoff);
        auditService.record(AdminAuditService.SYSTEM, "RETENTION_DUE", "RN", null,
                "count=" + due.size() + " cutoff=" + cutoff);
    }
}
