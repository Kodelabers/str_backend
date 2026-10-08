package com.str.backend.registration.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Zahtjev NIAS iznajmljivača za RB.
 *
 * <p>Naziv, adresa i maksimalan broj gostiju obavezni su samo za <b>novi</b> objekt
 * ({@link #isNewFacilityComplete()}). Za postojeći objekt ({@code facilityId}) to su podaci iz
 * eTurizma, koji ih ne vodi dosljedno — ulica i kućni broj prazni su za veliku većinu objekata,
 * naziv je često popunjivač, a ponekad nema ni kreveta. Obrazac ih tada prikazuje onemogućene,
 * a što eTurizam ne zna stiže kao {@code null}; RB se izdaje i bez toga. Što stigne, i dalje
 * provjerava {@code FacilityClaimVerifier}.
 */
public record RegistrationRequest(
        @NotBlank @Pattern(regexp = "\\d{11}", message = "OIB mora sadržavati točno 11 znamenki") String oib,
        String name,
        String typeId,
        @Min(1) Long countyId,
        String cityId,
        String settlementId,
        String street,
        String streetNumber,
        @Positive Long kucniBrojId,
        String postalCode,
        @Min(1) Integer maxBeds,
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
        @NotBlank @Email @Size(max = 255) String kontaktEmail,
        @NotBlank @Size(max = 32) String kontaktMobitel,
        @Size(max = 32) String kontaktTelefon,
        @Size(max = 128) String kontaktOsoba,
        @Size(max = 64) String kcBroj,
        /** Adresa / MBS podnositelja s obrasca — samo kad ih registar nema; {@code null} inače. */
        @Valid PodnositeljUnos podnositelj
) implements AccommodationRequest {

    /** Zahtjev bez podataka o podnositelju s obrasca — registar ih je vratio. */
    public RegistrationRequest(String oib, String name, String typeId, Long countyId, String cityId,
                               String settlementId, String street, String streetNumber, Long kucniBrojId,
                               String postalCode, Integer maxBeds, OfferType offerType, Offering offering,
                               Boolean building, String floor, Boolean apartments, Boolean legalized,
                               Boolean lessorResidence, Boolean coOwnerConsent, LocalDate consentDate,
                               LocalDate consentWithdrawalDate, Boolean host, Boolean confirmDuplicateLocation,
                               String facilityId, String kontaktEmail, String kontaktMobitel,
                               String kontaktTelefon, String kontaktOsoba, String kcBroj) {
        this(oib, name, typeId, countyId, cityId, settlementId, street, streetNumber, kucniBrojId,
                postalCode, maxBeds, offerType, offering, building, floor, apartments, legalized,
                lessorResidence, coOwnerConsent, consentDate, consentWithdrawalDate, host,
                confirmDuplicateLocation, facilityId, kontaktEmail, kontaktMobitel, kontaktTelefon,
                kontaktOsoba, kcBroj, null);
    }

    public static RegistrationRequest withOib(RegistrationRequest orig, String oib) {
        return new RegistrationRequest(oib, orig.name(), orig.typeId(), orig.countyId(),
                orig.cityId(), orig.settlementId(), orig.street(), orig.streetNumber(),
                orig.kucniBrojId(), orig.postalCode(), orig.maxBeds(),
                orig.offerType(), orig.offering(), orig.building(), orig.floor(),
                orig.apartments(), orig.legalized(), orig.lessorResidence(), orig.coOwnerConsent(),
                orig.consentDate(), orig.consentWithdrawalDate(), orig.host(),
                orig.confirmDuplicateLocation(), orig.facilityId(),
                orig.kontaktEmail(), orig.kontaktMobitel(), orig.kontaktTelefon(),
                orig.kontaktOsoba(), orig.kcBroj(), orig.podnositelj());
    }

    /**
     * Novi objekt nema izvor podataka osim obrasca, pa mora donijeti naziv, adresu i kapacitet.
     * {@code @JsonIgnore}: pravilo validacije, ne podatak — ne smije ispasti kao svojstvo u JSON-u.
     */
    @JsonIgnore
    @AssertTrue(message = "error.registration.newFacility.incomplete")
    public boolean isNewFacilityComplete() {
        if (notBlank(facilityId)) {
            return true;
        }
        return notBlank(name)
                && countyId != null
                && notBlank(cityId)
                && notBlank(street)
                && notBlank(streetNumber)
                && maxBeds != null;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
