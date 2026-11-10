package com.str.backend.rn;

import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;

import java.time.LocalDate;
import java.util.function.Function;

/**
 * Dvofazni postupak nad RB-om: prijedlog s rokom za očitovanje, pa mjera istekom roka. Suspenzija i
 * povlačenje (T12) dijele isti tijek — noćni posao ({@link ProposalDeadlineJob}) i odluku službenika o
 * očitovanju nakon roka ({@code RnService#suspendIfDeadlineExpired} / {@code #withdrawIfDeadlineExpired}) —
 * pa razlike stoje samo ovdje.
 */
enum ProposalProcedure {

    SUSPENSION(RnStatus.SUSPENSION_PROPOSED, RnStatus.SUSPENDED, RnTrigger.DEADLINE_EXCEEDED,
            RnEntity::getSuspensionDeadline, "Rok za rješavanje nepravilnosti istekao.", "suspension_deadline"),
    WITHDRAWAL(RnStatus.WITHDRAWAL_PROPOSED, RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL_DEADLINE_EXCEEDED,
            RnEntity::getWithdrawalDeadline, "Rok za očitovanje na prijedlog povlačenja istekao.", "withdrawal_deadline");

    private final RnStatus proposed;
    private final RnStatus target;
    private final RnTrigger trigger;
    private final Function<RnEntity, LocalDate> deadline;
    private final String note;
    private final String logKey;

    ProposalProcedure(RnStatus proposed, RnStatus target, RnTrigger trigger, Function<RnEntity, LocalDate> deadline,
                      String note, String logKey) {
        this.proposed = proposed;
        this.target = target;
        this.trigger = trigger;
        this.deadline = deadline;
        this.note = note;
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

    /** Bilješka uz prijelaz istekom roka — ista za noćni posao i za odluku službenika nakon roka. */
    String note() {
        return note;
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
