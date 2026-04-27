package com.str.backend.aktivnosti.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@AllArgsConstructor
public class AktivnostResponse {

    private UUID idAktivnosti;
    private Long idPlatforme;
    private String rb;
    private UUID idSso;
    private LocalDate razdobljeOd;
    private LocalDate razdobljeDo;
    private int brojNocenja;
    private int brojGostiju;
    private String drzavaGostiju;
    private Instant receivedAt;
}
