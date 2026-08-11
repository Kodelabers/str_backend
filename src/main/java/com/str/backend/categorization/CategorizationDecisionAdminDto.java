package com.str.backend.categorization;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Red na internom pregledu skeniranih rješenja za nadležno tijelo. Nosi metapodatke i status,
 * ali <b>ne</b> sadržaj datoteke — sken se dohvaća zasebno preko {@code /{id}/file}.
 */
public record CategorizationDecisionAdminDto(
        UUID decisionId,
        String lessorOib,
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
        Instant verifiedAt
) {

    public static CategorizationDecisionAdminDto of(CategorizationDecisionEntity e) {
        return new CategorizationDecisionAdminDto(
                e.getDecisionId(),
                e.getLessorOib(),
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
                e.getVerifiedBy(),
                e.getVerifiedAt());
    }
}
