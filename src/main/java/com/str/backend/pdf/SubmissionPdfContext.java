package com.str.backend.pdf;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.document.FilingReference;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.str.FacilityRooms.Rooms;

/**
 * Podaci za PDF zahtjeva. Zamjenjuje raniji potpis od jedanaest pozicijskih parametara, u
 * kojem su četiri uzastopna bila {@code String} pa ih se moglo zamijeniti bez ijedne greške
 * kompajlera; {@code countyName} se pritom uopće nije čitao.
 *
 * @param maxGuests          maksimalan broj gostiju (kreveti + pomoćni kreveti) — obrazac ga
 *                           od stavke 2 prikazuje kao jedan podatak, bez podjele na krevete;
 *                           {@code null} za postojeći eTurizam objekt kojem ga eTurizam ne zna
 * @param category           tražena kategorija; za postojeći objekt kategorija preuzeta iz
 *                           eTurizma, {@code null} kad je ne znamo
 * @param rooms              sobe po vrsti iz eTurizma; {@code null} za novi objekt i kad ih
 *                           eTurizam ne zna (v. {@code FacilityRooms})
 * @param filing             KLASA i URBROJ ulaznog pismena; {@code null} dok zahtjev nije urudžbiran
 * @param registrationNumber uvijek postoji — PDF se generira nakon dodjele RB-a
 */
public record SubmissionPdfContext(
        String accommodationName,
        String street,
        String streetNumber,
        String postalCode,
        String cityName,
        Integer maxGuests,
        String typeName,
        String category,
        Rooms rooms,
        LessorEntity lessor,
        FilingReference filing,
        String registrationNumber
) {

    public static SubmissionPdfContext of(AccommodationEntity accommodation, LessorEntity lessor,
                                          String typeName, Rooms rooms, String registrationNumber,
                                          FilingReference filing) {
        return new SubmissionPdfContext(
                accommodation.getName(),
                accommodation.getStreet(),
                accommodation.getStreetNumber(),
                accommodation.getPostalCode(),
                accommodation.getCity(),
                accommodation.getMaxGuests(),
                typeName,
                category(accommodation),
                rooms,
                lessor,
                filing,
                registrationNumber);
    }

    /** Tražena kategorija ima prednost; postojeći objekt nosi kategoriju preuzetu iz eTurizma. */
    private static String category(AccommodationEntity accommodation) {
        String requested = accommodation.getRequestedCategory();
        return requested != null && !requested.isBlank() ? requested : accommodation.getCategory();
    }

    /** Isti zahtjev, ali bez urudžbenih oznaka — kad urudžbiranje nije prošlo. */
    public SubmissionPdfContext withoutFiling() {
        return withFiling(null);
    }

    public SubmissionPdfContext withFiling(FilingReference value) {
        return new SubmissionPdfContext(accommodationName, street, streetNumber, postalCode,
                cityName, maxGuests, typeName, category, rooms, lessor, value, registrationNumber);
    }
}
