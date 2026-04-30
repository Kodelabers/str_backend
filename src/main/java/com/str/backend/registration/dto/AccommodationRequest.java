package com.str.backend.registration.dto;

import com.str.backend.domain.OfferType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.UUID;

@Data
public class AccommodationRequest {

    @NotBlank @Size(max = 128)
    private String county;

    @NotBlank @Size(max = 128)
    private String city;

    @Size(max = 128)
    private String settlement;

    @NotBlank @Size(max = 128)
    private String street;

    @NotBlank @Size(max = 16)
    private String streetNumber;

    @Size(max = 128)
    private String cadastralMunicipality;

    @Size(max = 64)
    private String cadastralParcelNumber;

    @Positive
    private int maxBeds;

    @Positive
    private int maxGuests;

    @NotNull
    private OfferType offerType;

    private Boolean lessorResidence;

    @NotNull
    private Boolean building;

    @Size(max = 8)
    private String floor;

    @NotNull
    private Boolean apartments;

    @NotNull
    private Boolean legalized;

    private Boolean coOwnerConsent;
    private LocalDate consentDate;
    private LocalDate consentWithdrawalDate;

    @Size(max = 64)
    private String accommodationCode;

    private Long accommodationTypeId;
    private UUID coreObjectId;
}
