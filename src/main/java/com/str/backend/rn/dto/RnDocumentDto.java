package com.str.backend.rn.dto;

import com.str.backend.attachment.PrilogDto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Jedan dokument dostupan za registracijski broj, za prikaz „Moji registracijski brojevi".
 *
 * @param id      id pohranjenog akta ({@code egop_pismeno}); {@code null} za zahtjev/dodjelu koji
 *                se ne serviraju po id-u
 * @param slug    strojni identifikator vrste ({@code zahtjev}, {@code dodjela}, {@code suspenzija}…);
 *                {@code null} ako naziv ne odgovara poznatoj vrsti
 * @param naziv   čitljiv naziv dokumenta
 * @param smjer   {@code ULAZNO} (podnesak stranke) ili {@code IZLAZNO} (akt tijela)
 * @param izdano  datum izdavanja
 * @param href    relativni URL za preuzimanje PDF-a
 * @param prilozi prilozi uz to pismeno (T17); prazno kad ih nema
 */
public record RnDocumentDto(
        UUID id,
        String slug,
        String naziv,
        String smjer,
        LocalDate izdano,
        String href,
        List<PrilogDto> prilozi
) {

    public RnDocumentDto {
        prilozi = prilozi == null ? List.of() : List.copyOf(prilozi);
    }

    /** Dokument bez priloga. */
    public RnDocumentDto(UUID id, String slug, String naziv, String smjer, LocalDate izdano, String href) {
        this(id, slug, naziv, smjer, izdano, href, List.of());
    }
}
