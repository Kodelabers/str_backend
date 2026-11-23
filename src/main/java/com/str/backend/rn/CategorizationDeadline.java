package com.str.backend.rn;

import com.str.backend.categorization.CategorizationDecisionStatus;
import com.str.backend.common.Zagreb;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Rok za prilaganje rješenja o kategorizaciji uz RB novog objekta (MINTS 17.11., točka 1, „Bez objekta i bez
 * rješenja"): datum izdavanja RB-a plus {@code str.categorization.decision-deadline-days} dana. Rok je samo
 * kontrola za službenika — nema noćnog posla ni automatske suspenzije; o suspenziji ({@code NO_DECISION})
 * službenik odlučuje ručno. Trajanje roka još potvrđuje MINTS, pa je postavka.
 *
 * <p>Danas je po hrvatskom vremenu ({@link Zagreb}), iz istog {@code Clock}-a kao ostali rokovi.
 */
@Component
public class CategorizationDeadline {

    private final int days;
    private final Clock clock;

    public CategorizationDeadline(@Value("${str.categorization.decision-deadline-days:30}") int days, Clock clock) {
        if (days < 0) {
            throw new IllegalArgumentException("str.categorization.decision-deadline-days must not be negative");
        }
        this.days = days;
        this.clock = clock;
    }

    /**
     * Rok uz RB kojem rješenje nedostaje: rješenje je potrebno (novi objekt, broj nije opozvan ni povučen),
     * a nije predano ili je odbijeno. Inače {@code null} — predano rješenje čeka pregled, a prihvaćeno je stiglo.
     */
    public LocalDate deadlineFor(LocalDate issueDate, boolean categorizationRequired,
                                 CategorizationDecisionStatus categorizationStatus) {
        if (issueDate == null || !categorizationRequired) {
            return null;
        }
        if (categorizationStatus != null && categorizationStatus != CategorizationDecisionStatus.REJECTED) {
            return null;
        }
        return issueDate.plusDays(days);
    }

    /**
     * Granica filtra „Samo istekao rok": rok je istekao kad je {@code issueDate + days} prije danas, tj. kad je
     * RB izdan prije {@code danas − days}. Na sam dan roka rok još nije istekao.
     */
    public LocalDate overdueIssuedBefore() {
        return Zagreb.today(clock).minusDays(days);
    }
}
