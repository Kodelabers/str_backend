package com.str.backend.lessor;

import com.str.backend.categorization.CategorizationDecisionStatus;
import com.str.backend.domain.RnStatus;

import java.time.LocalDate;

/**
 * Red „Mojih registracijskih brojeva".
 *
 * <p>{@code categorizationRequired} je {@code true} za RB novog objekta (smještaj bez eTurizam
 * {@code facilityId}) koji nije povučen — samo uz takav RB rješenje o kategorizaciji ima smisla,
 * isto pravilo kao {@code CategorizationDecisionService.UPLOAD_ALLOWED_RN_STATUSES}.
 * {@code categorizationStatus} je status mjerodavnog rješenja uz RB ili {@code null} kad ga
 * nema — postoji i uz RB neverificiranog eTurizam objekta kad je rješenje (neobavezno) predano. Non-EU popis ta polja ne računa (ondje nema NIAS predaje rješenja), pa su {@code false}
 * i {@code null}.
 */
public record LessorRnSummaryDto(
        String rn,
        RnStatus status,
        LocalDate issueDate,
        String accommodationName,
        String street,
        String streetNumber,
        String city,
        String accommodationTypeName,
        boolean categorizationRequired,
        CategorizationDecisionStatus categorizationStatus
) {

    /** Za upite koji stanje rješenja ne računaju (non-EU popis). */
    public LessorRnSummaryDto(String rn, RnStatus status, LocalDate issueDate,
                              String accommodationName, String street, String streetNumber,
                              String city, String accommodationTypeName) {
        this(rn, status, issueDate, accommodationName, street, streetNumber, city,
                accommodationTypeName, false, null);
    }
}
