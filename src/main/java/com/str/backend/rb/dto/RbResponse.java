package com.str.backend.rb.dto;

import com.str.backend.domain.RbStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;
import java.util.UUID;

@Getter
@AllArgsConstructor
public class RbResponse {

    private String rb;
    private UUID idZahtjeva;
    private UUID idSso;
    private RbStatus status;
    private LocalDate datumIzd;
    private LocalDate datumOd;
    private LocalDate datumDo;
}
