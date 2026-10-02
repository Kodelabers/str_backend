package com.str.backend.draft.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param facilityId eTurizam objekt za koji je nacrt (isti kao {@code formValues.facilityId} u
 *                   payloadu); prazno za novi objekt. Šalje se u čistom obliku jer je payload
 *                   šifriran, a po njemu backend drži jedan nacrt po objektu.
 */
public record DraftRequest(
        @NotBlank @Size(max = 255) String title,
        @NotNull String payload,
        @Size(max = 64) String facilityId
) {

    /** Prazan string iz forme znači „nema objekta". */
    public String normalizedFacilityId() {
        if (facilityId == null) {
            return null;
        }
        String t = facilityId.trim();
        return t.isEmpty() ? null : t;
    }
}
