package com.str.backend.rn;

import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;

import java.time.LocalDate;
import java.util.function.Function;

/**
 * Dvofazni postupak nad RB-om: prijedlog s rokom za očitovanje, pa mjera istekom roka. Suspenzija i
 * povlačenje (T12) dijele isti tijek — noćni posao ({@link ProposalDeadlineJob}) i neuvaženo očitovanje
 * ({@code RnService#suspendAfterRejectedObjection} / {@code #withdrawAfterRejectedObjection}), koje mjeru
 * izriče odmah (UAT 17.11.2026) — pa razlike stoje samo ovdje.
 */
enum ProposalProcedure {

    SUSPENSION(RnStatus.SUSPENSION_PROPOSED, RnStatus.SUSPENDED, RnTrigger.DEADLINE_EXCEEDED,
            RnEntity::getSuspensionDeadline, "Rok za rješavanje nepravilnosti istekao.",
            "Očitovanje na prijedlog suspenzije nije uvaženo.", "suspension_deadline"),
    WITHDRAWAL(RnStatus.WITHDRAWAL_PROPOSED, RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL_DEADLINE_EXCEEDED,
            RnEntity::getWithdrawalDeadline, "Rok za očitovanje na prijedlog povlačenja istekao.",
            "Očitovanje na prijedlog povlačenja nije uvaženo.", "withdrawal_deadline");

    private final RnStatus proposed;
    private final RnStatus target;
    private final RnTrigger trigger;
    private final Function<RnEntity, LocalDate> deadline;
    private final String note;
    private final String rejectionNote;
    private final String logKey;

    ProposalProcedure(RnStatus proposed, RnStatus target, RnTrigger trigger, Function<RnEntity, LocalDate> deadline,
                      String note, String rejectionNote, String logKey) {
        this.proposed = proposed;
        this.target = target;
        this.trigger = trigger;
        this.deadline = deadline;
        this.note = note;
        this.rejectionNote = rejectionNote;
        this.logKey = logKey;
    }

    RnStatus proposed() {
        return proposed;
    }

    RnStatus target() {
        return target;
    }

    RnTrigger trigger() {
        return trigger;
    }

    /** Bilješka uz prijelaz istekom roka (noćni posao). */
    String note() {
        return note;
    }

    /** Bilješka uz isti prijelaz kad mjeru izriče službenik neuvažavanjem očitovanja. */
    String rejectionNote() {
        return rejectionNote;
    }

    String logKey() {
        return logKey;
    }

    LocalDate deadline(RnEntity rn) {
        return deadline.apply(rn);
    }

    /** RB je još u ovom prijedlogu, a rok mu je strogo prije {@code today} (rok koji ističe danas još teče). */
    boolean isExpired(RnEntity rn, LocalDate today) {
        LocalDate rok = deadline(rn);
        return rn.getStatus() == proposed && rok != null && rok.isBefore(today);
    }
}
