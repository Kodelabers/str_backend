package com.str.backend.egop;

import java.time.Instant;

/**
 * Prilaže priloge uz pismeno u eGOP ({@code KreirajPrilogIPridruziDokument}). Implementacija je u
 * paketu {@code attachment} ({@code PrilogEgopService}); ovdje je samo ugovor, da tok
 * urudžbiranja ne ovisi o pohrani priloga.
 *
 * <p><b>Akt ne čeka na priloge.</b> Tok urudžbe zove {@link #prilozi} tek nakon glavnog dokumenta
 * (pečat, {@code SYNCED}), a prije otpreme u korisnički pretinac — pa otprema nosi priloge koji su
 * do tada priloženi. Pad priloga ne baca iznimku i ne mijenja status pismena: bilježi se na samom
 * prilogu, a ponavlja ga {@link EgopRetryJob} preko {@link #ponovi}.
 *
 * <p>Mora biti idempotentno po prilogu: već priloženi prilog se ne šalje drugi put.
 */
public interface EgopPrilozi {

    /**
     * Prvi (inline) pokušaj prilaganja. Ne baca iznimke.
     *
     * @param pismeno  urudžbirano pismeno (ima {@code jop}); prilozi se biraju po njemu
     * @param korisnik eGOP korisnik pod kojim ide i glavni dokument
     */
    void prilozi(EgopPismenoEntity pismeno, String korisnik);

    /** Ponovni pokušaj za priloge urudžbiranih pismena kojima je došao red. Ne baca iznimke. */
    void ponovi(Instant sada);
}
