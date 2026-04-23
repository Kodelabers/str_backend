package com.str.backend.zahtjev.dto;

import com.str.backend.domain.Ponuda;
import com.str.backend.sso.SsoEntity;

import java.util.UUID;

public record SsoResponse(
        UUID idSso,
        UUID idZahtjeva,
        String oznakaSso,
        String zupanija,
        String grad,
        String ulica,
        String kucniBroj,
        int maxKreveta,
        int maxGostiju,
        Ponuda ponuda,
        boolean zgrada,
        boolean stanovi,
        boolean legalizirano,
        Boolean suglasnostSuvlasnika,
        Boolean domacin
) {
    public static SsoResponse from(SsoEntity s) {
        return new SsoResponse(
                s.getIdSso(),
                s.getIdZahtjeva(),
                s.getOznakaSso(),
                s.getZupanija(),
                s.getGrad(),
                s.getUlica(),
                s.getKucniBroj(),
                s.getMaxKreveta(),
                s.getMaxGostiju(),
                s.getPonuda(),
                s.isZgrada(),
                s.isStanovi(),
                s.isLegalizirano(),
                s.getSuglasnostSuvlasnika(),
                s.getDomacin()
        );
    }
}
