package com.str.backend.attachment;

/**
 * Prilog u popisu dokumenata RB-a, ispod pismena uz koje je vezan.
 *
 * @param href   relativni URL za preuzimanje ({@code GET /api/rn/{rn}/attachments/{id}})
 * @param uloga  posebna uloga priloga ({@code RJESENJE}, UAT 25.11.); {@code null} = običan prilog
 * @param klasa  KLASA dokumenta s ulogom (Rješenje u županijskom postupku); inače {@code null}
 * @param urBroj URBROJ dokumenta s ulogom; inače {@code null}
 */
public record PrilogDto(Long id, String naziv, long velicina, String contentType, String href,
                        PismenoPrilogEntity.Uloga uloga, String klasa, String urBroj) {

    /** Prilog bez uloge. */
    public PrilogDto(Long id, String naziv, long velicina, String contentType, String href) {
        this(id, naziv, velicina, contentType, href, null, null, null);
    }

    public static PrilogDto of(String rn, PrilogRepository.PrilogSazetak s) {
        return new PrilogDto(s.getId(), s.getNazivDatoteke(), s.getVelicina(), s.getContentType(),
                "/api/rn/" + rn + "/attachments/" + s.getId(), s.getUloga(), s.getKlasa(), s.getUrBroj());
    }
}
