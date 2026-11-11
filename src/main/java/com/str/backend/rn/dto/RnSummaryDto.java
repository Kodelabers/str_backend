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
 * ({@code lessor.LessorRnSummaryDto}); {@code categorizationRegistered} dodaje je li objekt
 * prihvaćenog rješenja upisan u TuRegistar (T19) — samo na registru, iznajmljivač to ne vidi.
 * {@code facilityVerified} je stupac „Verificiran": je li objekt uz RB danas verificiran u eTurizmu; RB novog objekta je {@code false}, a {@code null}
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
        boolean categorizationRegistered,
        Boolean facilityVerified,
        /** Rok za očitovanje na prijedlog povlačenja; popunjen samo u statusu WITHDRAWAL_PROPOSED (T12). */
        LocalDate withdrawalDeadline,
        /**
         * Prijedlog povlačenja pokrenut iz suspenzije (T12): broj javno ostaje suspendiran. {@code true}
         * samo u statusu WITHDRAWAL_PROPOSED.
         */
        boolean prijedlogIzSuspenzije
) {

    /** Registar — {@code facilityVerified} se dopunjava nakon upita ({@link #withFacilityVerified}). */
    public RnSummaryDto(String rn, RnStatus status, LocalDate issueDate, LocalDate validFrom,
                        LocalDate validTo, LocalDate suspensionDeadline, UUID accommodationId,
                        String county, String city, String street, String streetNumber,
                        String accommodationName, String accommodationTypeName, UUID lessorId,
                        String lessorFirstName, String lessorLastName, String lessorLegalEntityName,
                        String facilityId, boolean categorizationRequired,
                        CategorizationDecisionStatus categorizationStatus, boolean categorizationRegistered,
                        LocalDate withdrawalDeadline, boolean prijedlogIzSuspenzije) {
        this(rn, status, issueDate, validFrom, validTo, suspensionDeadline, accommodationId, county,
                city, street, streetNumber, accommodationName, accommodationTypeName, lessorId,
                lessorFirstName, lessorLastName, lessorLegalEntityName, facilityId,
                categorizationRequired, categorizationStatus, categorizationRegistered, null, withdrawalDeadline,
                prijedlogIzSuspenzije);
    }

    /**
     * Rok za očitovanje trenutnog postupka (stupac izvoza registra): u prijedlogu povlačenja rok
     * povlačenja, inače rok suspenzije. Izvan prijedloga oba su prazna.
     */
    public LocalDate rokZaOcitovanje() {
        return status == RnStatus.WITHDRAWAL_PROPOSED ? withdrawalDeadline : suspensionDeadline;
    }

    public RnSummaryDto withFacilityVerified(Boolean verified) {
        return new RnSummaryDto(rn, status, issueDate, validFrom, validTo, suspensionDeadline,
                accommodationId, county, city, street, streetNumber, accommodationName,
                accommodationTypeName, lessorId, lessorFirstName, lessorLastName,
                lessorLegalEntityName, facilityId, categorizationRequired, categorizationStatus,
                categorizationRegistered, verified, withdrawalDeadline, prijedlogIzSuspenzije);
    }
}
