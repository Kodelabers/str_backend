package com.str.backend.rn.dto;

import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;

/**
 * Ostatak onoga što je iznajmljivač unio u obrazac (koraci „Detalji smještajnog objekta",
 * „Namjena smještajnog objekta" i „Podaci o iznajmljivaču"), samo za prikaz na
 * {@code GET /api/rn/{rn}/detail}.
 *
 * <p>Namjerno nije na {@link RnDetailDto}: taj DTO puni i kontekst akata i mailova, a adresa
 * prebivališta tamo ne treba.
 *
 * <p>Adresa ({@code lessorStreet} … {@code lessorCounty}) je prebivalište ili sjedište — oba se
 * spremaju u iste stupce {@code str_rn.lessor}; koje je od njih kaže
 * {@link RnDetailResponse#lessorAddressKind()}. Kontakt je za NIAS pravnu osobu onaj zastupnika
 * koji je podnio zahtjev. Za non-EU iznajmljivača kontakt i adresa nisu snapshot zahtjeva nego
 * trenutno stanje računa (isti redak za sve njegove RB-ove).
 */
public record RnDetailExtras(
        Offering offering,
        OfferType offerType,

        String lessorContactName,
        String lessorPhone,
        String lessorMobile,

        String lessorStreet,
        String lessorStreetNumber,
        String lessorPlace,
        String lessorCounty,
        String lessorCountryOfResidenceName
) {}
