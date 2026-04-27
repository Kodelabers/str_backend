package com.str.backend.registracija.dto;

import com.str.backend.domain.Ponuda;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.UUID;

@Data
public class SsoRequest {

    @NotBlank @Size(max = 128)
    private String zupanija;

    @NotBlank @Size(max = 128)
    private String grad;

    @Size(max = 128)
    private String naselje;

    @NotBlank @Size(max = 128)
    private String ulica;

    @NotBlank @Size(max = 16)
    private String kucniBroj;

    @Size(max = 128)
    private String katastarskaOpcina;

    @Size(max = 64)
    private String brojKatastarskeCestice;

    @Positive
    private int maxKreveta;

    @Positive
    private int maxGostiju;

    @NotNull
    private Ponuda ponuda;

    private Boolean boravisteIznajmljivaca;

    @NotNull
    private Boolean zgrada;

    @Size(max = 8)
    private String kat;

    @NotNull
    private Boolean stanovi;

    @NotNull
    private Boolean legalizirano;

    private Boolean suglasnostSuvlasnika;
    private LocalDate datumSuglasnosti;
    private LocalDate datumPovlacenjaSuglasnosti;

    @Size(max = 64)
    private String oznakaSso;

    private Long idVrsteSso;
    private UUID idCoreObjekt;
}
