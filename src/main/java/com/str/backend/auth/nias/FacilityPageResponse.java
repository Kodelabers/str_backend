package com.str.backend.auth.nias;

import java.util.List;

/**
 * Stranica popisa objekata. Paginacija nije kozmetika: testni iznajmljivač na dev-u ima 1530
 * objekata, pa se cijeli popis ne smije vraćati u jednom odgovoru.
 */
public record FacilityPageResponse(List<FacilityResponse> items, int page, int size, long total) {}
