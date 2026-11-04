package com.str.backend.attachment;

/**
 * Odgovor na učitavanje priloga. {@code id} radnja kasnije šalje u {@code prilogIds}.
 *
 * @param contentType MIME tip određen iz sadržaja, ne onaj koji je poslao preglednik
 */
public record PrilogResponse(Long id, String naziv, long velicina, String contentType) {

    static PrilogResponse of(PismenoPrilogEntity e) {
        return new PrilogResponse(e.getId(), e.getNazivDatoteke(), e.getVelicina(), e.getContentType());
    }
}
