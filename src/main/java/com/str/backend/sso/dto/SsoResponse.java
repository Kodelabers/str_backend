package com.str.backend.sso.dto;

import com.str.backend.domain.Ponuda;
import com.str.backend.domain.Status;
import com.str.backend.sso.SsoEntity;

import java.util.UUID;

public record SsoResponse(
        UUID uuidSso,
        String registracijskiBroj,
        int kapacitetKreveta,
        int kapacitetGostiju,
        Ponuda ponuda,
        String kat,
        String brojStana,
        Status status
) {
    public static SsoResponse from(SsoEntity e) {
        return new SsoResponse(
                e.getUuidSso(),
                e.getRegistracijskiBroj(),
                e.getKapacitetKreveta(),
                e.getKapacitetGostiju(),
                e.getPonuda(),
                e.getKat(),
                e.getBrojStana(),
                e.getStatus()
        );
    }
}
