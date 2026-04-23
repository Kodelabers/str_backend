package com.str.backend.iznajmljivac.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateIznajmljivacRequest(
        @NotBlank @Size(max = 128) String ime,
        @NotBlank @Size(max = 128) String prezime,
        @NotBlank @Size(max = 128) String ulica,
        @NotBlank @Size(max = 16) String kucniBroj,
        @NotBlank @Size(max = 128) String mjesto,
        @NotBlank @Size(max = 128) String zupanija,
        @NotBlank @Email @Size(max = 255) String email,
        @Pattern(regexp = "\\d{11}", message = "OIB must be 11 digits") String oibZastupnika,
        @Size(max = 255) String nazivPravneOsobe,
        @Size(max = 255) String imeKontakta,
        @Size(max = 32) String brojTelefona,
        @Size(max = 32) String brojMobitela,
        @Size(max = 128) String korisnickoIme
) {}
