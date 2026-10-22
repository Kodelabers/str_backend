package com.str.backend.rn.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.str.backend.categorization.CategorizationDecisionStatus;
import com.str.backend.domain.RnStatus;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Red registra RB-ova.
 *
 * <p>{@code categorizationRequired} i {@code categorizationStatus} su stupac „Rješenje o
 * kategorizaciji", po istom pravilu kao „Moji registracijski brojevi"
 * ({@code lessor.LessorRnSummaryDto}). {@code facilityVerified} je stupac „Verificiran": je li
 * objekt uz RB danas verificiran u eTurizmu; RB novog objekta je {@code false}, a {@code null}
 * kad eTurizam nije dostupan. Dopunjuje se nakon upita ({@code rn.RnRegistrySearch}), a
 * {@code facilityId} služi samo tom izračunu i ne ide u odgovor.
 */
public record RnSummaryDto(
        String rn,
        RnStatus status,
        LocalDate issueDate,
        LocalDate validFrom,
        LocalDate validTo,
        /** Rok za očitovanje; popunjen samo u statusu SUSPENSION_PROPOSED. */
        LocalDate suspensionDeadline,
        UUID accommodationId,
        String county,
        String city,
        String street,
        String streetNumber,
        String accommodationName,
        String accommodationTypeName,
        UUID lessorId,
        String lessorFirstName,
        String lessorLastName,
        String lessorLegalEntityName,
        @JsonIgnore String facilityId,
        boolean categorizationRequired,
        CategorizationDecisionStatus categorizationStatus,
        Boolean facilityVerified
) {

    /** Registar — {@code facilityVerified} se dopunjava nakon upita ({@link #withFacilityVerified}). */
    public RnSummaryDto(String rn, RnStatus status, LocalDate issueDate, LocalDate validFrom,
                        LocalDate validTo, LocalDate suspensionDeadline, UUID accommodationId,
                        String county, String city, String street, String streetNumber,
                        String accommodationName, String accommodationTypeName, UUID lessorId,
                        String lessorFirstName, String lessorLastName, String lessorLegalEntityName,
                        String facilityId, boolean categorizationRequired,
                        CategorizationDecisionStatus categorizationStatus) {
        this(rn, status, issueDate, validFrom, validTo, suspensionDeadline, accommodationId, county,
                city, street, streetNumber, accommodationName, accommodationTypeName, lessorId,
                lessorFirstName, lessorLastName, lessorLegalEntityName, facilityId,
                categorizationRequired, categorizationStatus, null);
    }

    public RnSummaryDto withFacilityVerified(Boolean verified) {
        return new RnSummaryDto(rn, status, issueDate, validFrom, validTo, suspensionDeadline,
                accommodationId, county, city, street, streetNumber, accommodationName,
                accommodationTypeName, lessorId, lessorFirstName, lessorLastName,
                lessorLegalEntityName, facilityId, categorizationRequired, categorizationStatus,
                verified);
    }
}
