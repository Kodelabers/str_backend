package com.str.backend.categorization;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Red na internom pregledu skeniranih rješenja za nadležno tijelo. Nosi metapodatke i status,
 * ali <b>ne</b> sadržaj datoteke — sken se dohvaća zasebno preko {@code /{id}/file}.
 *
 * <p>{@code sourceDecisionId} je zadan kad je rješenje preneseno s povučenog RB-a na novi RB istog
 * objekta (ponovni zahtjev nakon opoziva, T15); izvorni zapis ostaje uz povučeni RB.
 *
 * <p>{@code lessorTaxNumber} je strani porezni broj non-EU iznajmljivača koji je predao rješenje
 * (on nema OIB, pa je {@code lessorOib} tada {@code null}); za NIAS predaju je {@code null}.
 */
public record CategorizationDecisionAdminDto(
        UUID decisionId,
        String lessorOib,
        String lessorTaxNumber,
        String registrationNumber,
        String objectName,
        String accommodationTypeCode,
        String addressText,
        String decisionNumber,
        LocalDate decisionDate,
        Integer maxBeds,
        String note,
        String fileName,
        String contentType,
        long fileSize,
        CategorizationDecisionStatus status,
        String facilityId,
        Instant uploadedAt,
        String verifiedBy,
        Instant verifiedAt,
        UUID sourceDecisionId,
        String registeredBy,
        Instant registeredAt,
        boolean registrationConfirmable
) {

    /**
     * {@code verifiedBy} i {@code registeredBy} su prikaz aktera (ime službenika), ne sirovi zapis iz baze.
     * „Upisano" (T19) je {@code facilityId != null}; {@code registeredBy/At} kažu tko je i kad potvrdio upis, a
     * {@code registrationConfirmable} nudi li se „Potvrda upisa objekta" (pravilo je u servisu).
     */
    public static CategorizationDecisionAdminDto of(CategorizationDecisionEntity e, String verifiedBy,
                                                    String registeredBy, String lessorTaxNumber,
                                                    boolean registrationConfirmable) {
        return new CategorizationDecisionAdminDto(
                e.getDecisionId(),
                e.getLessorOib(),
                lessorTaxNumber,
                e.getRn(),
                e.getObjectName(),
                e.getAccommodationTypeCode(),
                e.getAddressText(),
                e.getDecisionNumber(),
                e.getDecisionDate(),
                e.getMaxBeds(),
                e.getNote(),
                e.getFileName(),
                e.getContentType(),
                e.getFileSize(),
                e.getStatus(),
                e.getFacilityId(),
                e.getUploadedAt(),
                verifiedBy,
                e.getVerifiedAt(),
                e.getSourceDecisionId(),
                registeredBy,
                e.getRegisteredAt(),
                registrationConfirmable);
    }
}
