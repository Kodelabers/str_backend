package com.str.backend.rn.dto;

import com.str.backend.categorization.CategorizationDecisionEntity;
import com.str.backend.categorization.CategorizationDecisionStatus;

import java.util.UUID;

/**
 * Mjerodavno rješenje o kategorizaciji uz RB, za detalj RB-a (T19): službenik vidi status, je li
 * objekt upisan u TuRegistar i poveznicu na pregled rješenja. Mjerodavno je kao u registru
 * ({@code RnRepository.CATEGORIZATION_STATUS}): prihvaćeno ili predano, a inače zadnje odbijeno.
 *
 * @param registered „Upisano" — uz prihvaćeno rješenje zapisana je jedinica TuRegistra
 */
public record RnCategorizationInfo(
        UUID decisionId,
        CategorizationDecisionStatus status,
        boolean registered
) {

    public static RnCategorizationInfo of(CategorizationDecisionEntity e) {
        return new RnCategorizationInfo(e.getDecisionId(), e.getStatus(), e.isRegistered());
    }
}
