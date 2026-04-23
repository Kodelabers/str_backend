package com.str.backend.rb.dto;

import com.str.backend.domain.RbStatus;
import com.str.backend.rb.RbEntity;

import java.time.LocalDate;
import java.util.UUID;

public record RbResponse(
        String rb,
        UUID idZahtjeva,
        UUID idSso,
        RbStatus status,
        LocalDate datumIzd,
        LocalDate datumOd,
        LocalDate datumDo
) {
    public static RbResponse from(RbEntity e) {
        return new RbResponse(
                e.getRb(),
                e.getIdZahtjeva(),
                e.getIdSso(),
                e.getStatus(),
                e.getDatumIzd(),
                e.getDatumOd(),
                e.getDatumDo()
        );
    }
}
