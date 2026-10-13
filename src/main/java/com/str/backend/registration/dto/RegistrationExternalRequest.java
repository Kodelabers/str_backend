package com.str.backend.registration.dto;

import com.str.backend.domain.OfferType;
import com.str.backend.domain.RegistrationNumber;
import com.str.backend.domain.Offering;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Zahtjev prijavljenog non-EU iznajmljivača.
 *
 * <p>Kontakt je obavezan za svakog iznajmljivača, bez obzira na način prijave. Ovdje se to svodi
 * na <b>mobitel</b>, jer se e-mail i mobitel na ovom putu ponašaju različito:
 *
 * <ul>
 *   <li><b>E-mail</b> je zajamčen — samoregistracija ga traži ({@code @NotBlank @Email} na
 *       {@code LessorRegistrationRequest}), pa {@code lessor} redak nikad nije bez njega.
 *       Ovdje se zato ne traži ponovno, a i ne bi se mogao spremiti: stupac je
 *       {@code updatable = false} jer je identitet računa.</li>
 *   <li><b>Mobitel</b> NIJE zajamčen — na samoregistraciji je {@code telefon} bio neobavezan,
 *       pa je non-EU iznajmljivač mogao ostati bez ijednog broja. Zato je ovdje
 *       {@code @NotBlank}: to je jedino mjesto na kojem se ta rupa zatvara.</li>
 * </ul>
 *
 * <p>Ostala kontakt polja su neobavezna i, kad nisu poslana, zatečene se vrijednosti ne brišu.
 *
 * <p>{@code prethodniRb}: ponovni zahtjev za novi objekt nakon povlačenja (T15), kao u
 * {@link RegistrationRequest}; predpopuna je {@code GET /api/lessor/registrations/{rn}/ponovni-zahtjev}.
 */
public record RegistrationExternalRequest(
        @NotBlank String name,
        String typeId,
        @NotNull @Min(1) Long countyId,
        @NotBlank String cityId,
        String settlementId,
        @NotBlank String street,
        @NotBlank String streetNumber,
        @Positive Long kucniBrojId,
        String postalCode,
        @NotNull @Min(1) Integer maxBeds,
        @NotNull OfferType offerType,
        @NotNull Offering offering,
        @NotNull Boolean building,
        // Kat je cijeli broj: 0 = prizemlje, negativan = ispod razine tla (-9..99).
        @NotBlank @Pattern(regexp = "-[1-9]|\\d{1,2}", message = "Kat mora biti cijeli broj od -9 do 99") String floor,
        @NotNull Boolean apartments,
        @NotNull Boolean legalized,
        Boolean lessorResidence,
        Boolean coOwnerConsent,
        LocalDate consentDate,
        LocalDate consentWithdrawalDate,
        Boolean host,
        Boolean confirmDuplicateLocation,
        @Size(max = 64) String facilityId,
        @Email @Size(max = 255) String kontaktEmail,
        @NotBlank @Size(max = 32) String kontaktMobitel,
        @Size(max = 32) String kontaktTelefon,
        @Size(max = 128) String kontaktOsoba,
        @Size(max = 64) String kcBroj,
        /** Povučeni RB istog novog objekta (ponovni zahtjev nakon opoziva); {@code null} inače. */
        @Pattern(regexp = RegistrationNumber.REGEXP, message = "error.reissue.previousRn.format") String prethodniRb
) implements AccommodationRequest {

    /** Zahtjev bez ponovnog zahtjeva nakon opoziva. */
    public RegistrationExternalRequest(String name, String typeId, Long countyId, String cityId,
                                       String settlementId, String street, String streetNumber,
                                       Long kucniBrojId, String postalCode, Integer maxBeds,
                                       OfferType offerType, Offering offering, Boolean building, String floor,
                                       Boolean apartments, Boolean legalized, Boolean lessorResidence,
                                       Boolean coOwnerConsent, LocalDate consentDate,
                                       LocalDate consentWithdrawalDate, Boolean host,
                                       Boolean confirmDuplicateLocation, String facilityId,
                                       String kontaktEmail, String kontaktMobitel, String kontaktTelefon,
                                       String kontaktOsoba, String kcBroj) {
        this(name, typeId, countyId, cityId, settlementId, street, streetNumber, kucniBrojId, postalCode,
                maxBeds, offerType, offering, building, floor, apartments, legalized, lessorResidence,
                coOwnerConsent, consentDate, consentWithdrawalDate, host, confirmDuplicateLocation,
                facilityId, kontaktEmail, kontaktMobitel, kontaktTelefon, kontaktOsoba, kcBroj, null);
    }
}
