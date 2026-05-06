package com.str.backend.registration.dto;

import com.str.backend.domain.County;
import com.str.backend.domain.OfferType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class RegistrationRequest {

    @NotBlank
    @Pattern(regexp = "\\d{11}", message = "OIB mora sadržavati točno 11 znamenki")
    private String oib;

    @NotBlank private String name;
    private String typeId;

    @NotNull private County county;
    @NotBlank private String cityId;
    private String settlementId;

    @NotBlank private String street;
    @NotBlank private String streetNumber;
    private String postalCode;

    @Min(1) private int maxBeds;
    @Min(1) private int maxGuests;

    @NotNull private OfferType offerType;
}
