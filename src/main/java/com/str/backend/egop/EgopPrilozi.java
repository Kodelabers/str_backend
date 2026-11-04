package com.str.backend.egop;

import com.str.backend.egop.exception.EgopException;

/**
 * Prilaže priloge uz pismeno u eGOP ({@code KreirajPrilogIPridruziDokument}). Implementacija je u
 * paketu {@code attachment} ({@code PrilogEgopService}); ovdje je samo ugovor, da tok
 * urudžbiranja ne ovisi o pohrani priloga.
 *
 * <p>Mora biti idempotentno po prilogu: retry ({@link EgopRetryJob}) zove ponovo, a već priloženi
 * prilog se ne šalje drugi put. Pad baca iznimku, pa pismeno ostaje za ponovni pokušaj.
 */
@FunctionalInterface
public interface EgopPrilozi {

    /** Bez priloga — za konstrukciju u testovima koji ih ne trebaju. */
    EgopPrilozi NONE = (pismeno, korisnik) -> { };

    /**
     * @param pismeno  urudžbirano pismeno (ima {@code jop}); prilozi se biraju po njemu
     * @param korisnik eGOP korisnik pod kojim ide i glavni dokument
     */
    void prilozi(EgopPismenoEntity pismeno, String korisnik) throws EgopException;
}
