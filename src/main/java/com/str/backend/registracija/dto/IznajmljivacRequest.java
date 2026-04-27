package com.str.backend.registracija.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class IznajmljivacRequest {

    // --- Osnovi podaci ---
    @NotBlank @Size(max = 128)
    private String ime;

    @NotBlank @Size(max = 128)
    private String prezime;

    @NotBlank @Email
    private String email;

    // --- Adresa iznajmljivača ---
    @NotBlank @Size(max = 128)
    private String ulica;

    @NotBlank @Size(max = 16)
    private String kucniBroj;

    @NotBlank @Size(max = 128)
    private String mjesto;

    @NotBlank @Size(max = 128)
    private String zupanija;

    // --- Kontakt ---
    private String imeKontakta;

    @Size(max = 32)
    private String brojTelefona;

    @Size(max = 32)
    private String brojMobitela;

    @Size(max = 1024)
    private String napomenaKontakta;

    // --- Pravna osoba ---
    @Size(max = 11)
    private String oibZastupnika;

    @Size(max = 255)
    private String nazivPravneOsobe;

    private String zastupnikPravneOsobe;

    @Email
    private String emailZastupnika;

    @Size(max = 32)
    private String telefonZastupnika;

    @Size(max = 255)
    private String adresaZastupnika;

    // --- Interni ---
    @Size(max = 64)
    private String idSluzbeneOsobe;
}
