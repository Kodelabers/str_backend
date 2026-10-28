package com.str.backend.objection;

import java.time.Instant;
import java.util.UUID;

/** Odgovor na predaju prigovora — potvrda s RB-om na koji se prigovor odnosi. */
public record SuspensionObjectionResponse(UUID objectionId,
                                          String registrationNumber,
                                          SuspensionObjectionStatus status,
                                          String fileName,
                                          long fileSize,
                                          Instant submittedAt) {

    public static SuspensionObjectionResponse of(SuspensionObjectionEntity e) {
        return new SuspensionObjectionResponse(e.getObjectionId(), e.getRn(), e.getStatus(),
                e.getFileName(), e.getFileSize(), e.getSubmittedAt());
    }
}
