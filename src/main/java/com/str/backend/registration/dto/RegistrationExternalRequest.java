package com.str.backend.registration.dto;

import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record RegistrationExternalRequest(
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
        Boolean confirmDuplicateLocation,
        @Size(max = 64) String facilityId
) implements AccommodationRequest {}
