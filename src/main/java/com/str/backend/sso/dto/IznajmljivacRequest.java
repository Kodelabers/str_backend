package com.str.backend.sso.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record IznajmljivacRequest(
        @Pattern(regexp = "\\d{11}", message = "OIB must be 11 digits") String oib,
        @NotBlank @Size(max = 255) String nazivPrezime,
        @NotBlank @Size(max = 500) String adresaPrebivalista
) {}
