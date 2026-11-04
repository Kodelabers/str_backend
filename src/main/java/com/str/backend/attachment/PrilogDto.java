package com.str.backend.attachment;

/**
 * Prilog u popisu dokumenata RB-a, ispod pismena uz koje je vezan.
 *
 * @param href relativni URL za preuzimanje ({@code GET /api/rn/{rn}/attachments/{id}})
 */
public record PrilogDto(Long id, String naziv, long velicina, String contentType, String href) {

    public static PrilogDto of(String rn, PrilogRepository.PrilogSazetak s) {
        return new PrilogDto(s.getId(), s.getNazivDatoteke(), s.getVelicina(), s.getContentType(),
                "/api/rn/" + rn + "/attachments/" + s.getId());
    }
}
