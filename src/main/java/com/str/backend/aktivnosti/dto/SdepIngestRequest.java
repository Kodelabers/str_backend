package com.str.backend.aktivnosti.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
public class SdepIngestRequest {

    @NotNull
    private Long idPlatforme;

    @NotEmpty
    @Valid
    private List<Stavka> stavke;

    @Data
    public static class Stavka {

        @NotNull
        private String rb;

        private UUID idSso;

        @NotNull
        private LocalDate razdobljeOd;

        @NotNull
        private LocalDate razdobljeDo;

        private int brojNocenja;
        private int brojGostiju;
        private String drzavaGostiju;
    }
}
