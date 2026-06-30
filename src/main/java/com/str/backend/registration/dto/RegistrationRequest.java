package com.str.backend.registration.dto;

import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record RegistrationRequest(
        @NotBlank @Pattern(regexp = "\\d{11}", message = "OIB mora sadržavati točno 11 znamenki") String oib,
        @NotBlank String name,
        String typeId,
        @NotNull @Min(1) Long countyId,
        @NotBlank String cityId,
        String settlementId,
        @NotBlank String street,
        @NotBlank String streetNumber,
        String houseNumberCode,
        String postalCode,
        @Min(1) int maxBeds,
        @Min(1) int maxGuests,
        @NotNull OfferType offerType,
        @NotNull Offering offering,
        @NotNull Boolean building,
        @Size(max = 8) String floor,
        @NotNull Boolean apartments,
        @NotNull Boolean legalized,
        Boolean lessorResidence,
        Boolean coOwnerConsent,
        LocalDate consentDate,
        LocalDate consentWithdrawalDate,
        Boolean host,
        Boolean confirmDuplicateLocation
) implements AccommodationRequest {

    public static RegistrationRequest withOib(RegistrationRequest orig, String oib) {
        return new RegistrationRequest(oib, orig.name(), orig.typeId(), orig.countyId(),
                orig.cityId(), orig.settlementId(), orig.street(), orig.streetNumber(),
                orig.houseNumberCode(), orig.postalCode(), orig.maxBeds(), orig.maxGuests(),
                orig.offerType(), orig.offering(), orig.building(), orig.floor(),
                orig.apartments(), orig.legalized(), orig.lessorResidence(), orig.coOwnerConsent(),
                orig.consentDate(), orig.consentWithdrawalDate(), orig.host(),
                orig.confirmDuplicateLocation());
    }
}
