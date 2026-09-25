package com.str.backend.pdf;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.lessor.LessorEntity;

/**
 * Podaci za PDF zahtjeva. Zamjenjuje raniji potpis od jedanaest pozicijskih parametara, u
 * kojem su četiri uzastopna bila {@code String} pa ih se moglo zamijeniti bez ijedne greške
 * kompajlera; {@code countyName} se pritom uopće nije čitao.
 *
 * @param maxGuests          maksimalan broj gostiju (kreveti + pomoćni kreveti) — obrazac ga
 *                           od stavke 2 prikazuje kao jedan podatak, bez podjele na krevete
 * @param filingNumber       KLASA + URBROJ; {@code null} dok zahtjev nije urudžbiran
 * @param registrationNumber uvijek postoji — PDF se generira nakon dodjele RB-a
 */
public record SubmissionPdfContext(
        String accommodationName,
        String street,
        String streetNumber,
        String postalCode,
        String cityName,
        int maxGuests,
        String typeName,
        LessorEntity lessor,
        String filingNumber,
        String registrationNumber
) {

    public static SubmissionPdfContext of(AccommodationEntity accommodation, LessorEntity lessor,
                                          String typeName, String registrationNumber,
                                          String filingNumber) {
        return new SubmissionPdfContext(
                accommodation.getName(),
                accommodation.getStreet(),
                accommodation.getStreetNumber(),
                accommodation.getPostalCode(),
                accommodation.getCity(),
                accommodation.getMaxGuests(),
                typeName,
                lessor,
                filingNumber,
                registrationNumber);
    }

    /** Isti zahtjev, ali bez urudžbenog broja — kad urudžbiranje nije prošlo. */
    public SubmissionPdfContext withoutFilingNumber() {
        return new SubmissionPdfContext(accommodationName, street, streetNumber, postalCode,
                cityName, maxGuests, typeName, lessor, null, registrationNumber);
    }

    public SubmissionPdfContext withFilingNumber(String value) {
        return new SubmissionPdfContext(accommodationName, street, streetNumber, postalCode,
                cityName, maxGuests, typeName, lessor, value, registrationNumber);
    }
}
