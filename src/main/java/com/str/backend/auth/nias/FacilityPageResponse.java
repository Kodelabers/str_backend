package com.str.backend.auth.nias;

import java.util.List;

/**
 * Stranica popisa objekata. Paginacija nije kozmetika: testni iznajmljivač na CDU ima 3.741
 * jedinicu, pa se cijeli popis ne smije vraćati u jednom odgovoru.
 *
 * <p>{@code size} i {@code total} broje <b>objekte</b>; {@code items} su jedinice tih objekata,
 * pa ih na stranici može biti više od {@code size}. {@code totalUnits} je ukupan broj jedinica.
 */
public record FacilityPageResponse(List<FacilityResponse> items, int page, int size, long total,
                                   long totalUnits) {}
