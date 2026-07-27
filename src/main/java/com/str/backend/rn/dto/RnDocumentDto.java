package com.str.backend.rn.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Jedan dokument dostupan za registracijski broj, za prikaz „Moji registracijski brojevi".
 *
 * @param id     id pohranjenog akta ({@code egop_pismeno}); {@code null} za zahtjev/dodjelu koji
 *               se ne serviraju po id-u
 * @param slug   strojni identifikator vrste ({@code zahtjev}, {@code dodjela}, {@code suspenzija}…);
 *               {@code null} ako naziv ne odgovara poznatoj vrsti
 * @param naziv  čitljiv naziv dokumenta
 * @param smjer  {@code ULAZNO} (podnesak stranke) ili {@code IZLAZNO} (akt tijela)
 * @param izdano datum izdavanja
 * @param href   relativni URL za preuzimanje PDF-a
 */
public record RnDocumentDto(
        UUID id,
        String slug,
        String naziv,
        String smjer,
        LocalDate izdano,
        String href
) {}
