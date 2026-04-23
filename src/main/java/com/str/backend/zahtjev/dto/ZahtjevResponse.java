package com.str.backend.zahtjev.dto;

import com.str.backend.domain.Kanal;
import com.str.backend.domain.ZahtjevStatus;
import com.str.backend.zahtjev.ZahtjevEntity;

import java.util.List;
import java.util.UUID;

public record ZahtjevResponse(
        UUID idZahtjeva,
        String urZahtjeva,
        Kanal kanal,
        String oznakaVrste,
        UUID idIznajmljivaca,
        Long idNadleznogTijela,
        ZahtjevStatus status,
        String linkDokumenta,
        List<SsoResponse> sso
) {
    public static ZahtjevResponse from(ZahtjevEntity z, List<SsoResponse> sso) {
        return new ZahtjevResponse(
                z.getIdZahtjeva(),
                z.getUrZahtjeva(),
                z.getKanal(),
                z.getOznakaVrste(),
                z.getIdIznajmljivaca(),
                z.getIdNadleznogTijela(),
                z.getStatus(),
                z.getLinkDokumenta(),
                sso
        );
    }
}
