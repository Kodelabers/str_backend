package com.str.backend.lessor;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
 *
 * <p>{@code facilityVerified} je stupac „Verificiran": je li objekt uz RB danas verificiran u
 * eTurizmu ({@code str.RnFacilityVerification}); RB novog objekta je {@code false}, a
 * {@code null} kad eTurizam nije dostupan. Računa ga samo NIAS popis — non-EU iznajmljivač nema
 * eTurizam objekata, pa je ondje {@code null}.
 * {@code facilityId} služi samo tom izračunu i ne ide u odgovor.
 *
 * <p>{@code ponovniZahtjevDo} je zadnji dan do kojeg se za povučeni RB novog objekta smije podnijeti
 * ponovni zahtjev s preuzetim podacima (T15, {@code ReissueService}); {@code null} kad to nije
 * moguće — RB nije povučen, objekt je iz eTurizma, rok je istekao ili je RB već iskorišten.
 * Računa se na oba popisa (NIAS i non-EU) nakon upita ({@link #withPonovniZahtjevDo}).
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
        CategorizationDecisionStatus categorizationStatus,
        @JsonIgnore String facilityId,
        Boolean facilityVerified,
        LocalDate ponovniZahtjevDo
) {

    /** NIAS popis — {@code facilityVerified} se dopunjava nakon upita ({@link #withFacilityVerified}). */
    public LessorRnSummaryDto(String rn, RnStatus status, LocalDate issueDate,
                              String accommodationName, String street, String streetNumber,
                              String city, String accommodationTypeName,
                              boolean categorizationRequired,
                              CategorizationDecisionStatus categorizationStatus,
                              String facilityId) {
        this(rn, status, issueDate, accommodationName, street, streetNumber, city,
                accommodationTypeName, categorizationRequired, categorizationStatus, facilityId, null, null);
    }

    /** Za upite koji stanje rješenja ne računaju (non-EU popis). */
    public LessorRnSummaryDto(String rn, RnStatus status, LocalDate issueDate,
                              String accommodationName, String street, String streetNumber,
                              String city, String accommodationTypeName) {
        this(rn, status, issueDate, accommodationName, street, streetNumber, city,
                accommodationTypeName, false, null, null, null, null);
    }

    public LessorRnSummaryDto withFacilityVerified(boolean verified) {
        return new LessorRnSummaryDto(rn, status, issueDate, accommodationName, street, streetNumber,
                city, accommodationTypeName, categorizationRequired, categorizationStatus, facilityId,
                verified, ponovniZahtjevDo);
    }

    public LessorRnSummaryDto withPonovniZahtjevDo(LocalDate until) {
        return new LessorRnSummaryDto(rn, status, issueDate, accommodationName, street, streetNumber,
                city, accommodationTypeName, categorizationRequired, categorizationStatus, facilityId,
                facilityVerified, until);
    }
}
