package com.str.backend.prefill;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Razriješeni prefill payload za popunjavanje forme za zahtjev za registracijskim brojem.")
public record RegistrationNumberPrefillResponse(
        @Schema(description = "OIB najmoprimca", example = "12345678901") String oib,
        @Schema(description = "Ime najmoprimca", example = "Ana") String firstName,
        @Schema(description = "Prezime najmoprimca", example = "Anić") String lastName,
        @Schema(description = "Broj kreveta u objektu", example = "3") Integer maxBedCount,
        @Schema(description = "Maksimalni broj gostiju u objektu", example = "6") Integer maxGuestCount,
        @Schema(description = "Naziv županije razriješen iz adresne šifre", example = "Grad Zagreb") String countyName,
        @Schema(description = "Naziv općine/grada razriješen iz adresne šifre", example = "Zagreb") String municipalityName,
        @Schema(description = "Naziv naselja razriješen iz adresne šifre", example = "Zagreb") String settlementName,
        @Schema(description = "Naziv ulice razriješen iz adresne šifre", example = "Ilica") String streetName,
        @Schema(description = "Kućni broj razriješen iz adresne šifre", example = "1") String streetNumber
) {
}
