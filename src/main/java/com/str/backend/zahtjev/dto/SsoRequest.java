package com.str.backend.zahtjev.dto;

import com.str.backend.domain.Ponuda;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

public record SsoRequest(
        @NotBlank @Size(max = 128) String zupanija,
        @NotBlank @Size(max = 128) String grad,
        @Size(max = 128) String naselje,
        @NotBlank @Size(max = 128) String ulica,
        @NotBlank @Size(max = 16) String kucniBroj,
        @Size(max = 128) String katastarskaOpcina,
        @Size(max = 64) String brojKatastarskeCestice,
        @Positive int maxKreveta,
        @Positive int maxGostiju,
        @NotNull Ponuda ponuda,
        Boolean boravisteIznajmljivaca,
        @NotNull Boolean zgrada,
        @Size(max = 8) String kat,
        @NotNull Boolean stanovi,
        @NotNull Boolean legalizirano,
        Boolean suglasnostSuvlasnika,
        LocalDate datumSuglasnosti,
        LocalDate datumPovlacenjaSuglasnosti,
        @Size(max = 64) String oznakaSso,
        Long idVrsteSso,
        UUID idCoreObjekt
) {}
