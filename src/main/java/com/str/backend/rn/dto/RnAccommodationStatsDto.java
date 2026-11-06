package com.str.backend.rn.dto;

/**
 * STR-3.3 (T4): JLS (općina/grad) i najveći broj kreveta objekta pod registracijskim brojem, za
 * mjesečnu dostavu DZS-u/Eurostatu preko SDEP-a. Namjerno bez podataka o iznajmljivaču.
 */
public record RnAccommodationStatsDto(String rn, String municipality, Integer maxBeds) {
}
