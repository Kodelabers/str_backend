package com.str.backend.objection;

import com.str.backend.domain.RnStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Red povijesti prigovora na detalju RB-a (službenik). Bez sadržaja datoteke — dokument se
 * dohvaća zasebno preko {@code /{id}/file}.
 *
 * <p>{@code lessorTaxNumber} je strani porezni broj non-EU iznajmljivača (on nema OIB);
 * {@code resolvedBy} je prikaz aktera (ime službenika ili {@code SYSTEM}), ne sirovi zapis iz baze.
 */
public record SuspensionObjectionDto(
        UUID objectionId,
        String registrationNumber,
        String lessorOib,
        String lessorTaxNumber,
        String lessorEmail,
        RnStatus rnStatus,
        String fileName,
        String contentType,
        long fileSize,
        SuspensionObjectionStatus status,
        Instant submittedAt,
        String resolvedBy,
        Instant resolvedAt,
        String resolutionNote
) {

    public static SuspensionObjectionDto of(SuspensionObjectionRepository.ObjectionSummary o, String resolvedBy,
                                            String lessorTaxNumber) {
        return new SuspensionObjectionDto(
                o.getObjectionId(),
                o.getRn(),
                o.getLessorOib(),
                lessorTaxNumber,
                o.getLessorEmail(),
                o.getRnStatus(),
                o.getFileName(),
                o.getContentType(),
                o.getFileSize(),
                o.getStatus(),
                o.getSubmittedAt(),
                resolvedBy,
                o.getResolvedAt(),
                o.getResolutionNote());
    }
}
