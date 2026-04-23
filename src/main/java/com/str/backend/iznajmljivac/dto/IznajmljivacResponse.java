package com.str.backend.iznajmljivac.dto;

import com.str.backend.domain.StatusPrijave;
import com.str.backend.iznajmljivac.IznajmljivacEntity;

import java.util.UUID;

public record IznajmljivacResponse(
        UUID idIznajmljivaca,
        String ime,
        String prezime,
        String email,
        String zupanija,
        String mjesto,
        String korisnickoIme,
        StatusPrijave statusPrijave
) {
    public static IznajmljivacResponse from(IznajmljivacEntity e) {
        return new IznajmljivacResponse(
                e.getIdIznajmljivaca(),
                e.getIme(),
                e.getPrezime(),
                e.getEmail(),
                e.getZupanija(),
                e.getMjesto(),
                e.getKorisnickoIme(),
                e.getStatusPrijave()
        );
    }
}
