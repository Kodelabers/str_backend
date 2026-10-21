package com.str.backend.kp.client;

import com.str.backend.kp.KpPrimatelj;

import java.time.Instant;
import java.util.UUID;

/**
 * Jedna poruka u korisnički pretinac s aktom u privitku. Parametri pravnog učinka nisu ovdje —
 * oni su svojstvo registrirane e-poruke i dolaze iz {@code KpProperties}.
 *
 * @param idPoruke                        naš identifikator poruke; isti kroz sve pokušaje, pa je
 *                                        ponovno slanje idempotentno (K009/Z012)
 * @param tipPoruke                       {@code TipPoruke} (FO) / {@code IdPorukeURegistru} (PS)
 * @param sadrzaj                         tekst poruke, UTF-8 (Base64 radi klijent)
 * @param klasa                           {@code KlasaBrDok}; {@code null} ako ga nema
 * @param urBroj                          {@code UrBroj}; {@code null} ako ga nema
 * @param privitakNaziv                   ime datoteke, već svedeno na znakove koje shema dopušta
 * @param jedinstveniIdentifikatorPismena JIP — obavezan na privitku poruke s pravnim učinkom
 */
public record KpPoruka(
        KpPrimatelj primatelj,
        UUID idPoruke,
        int tipPoruke,
        String naslov,
        String sadrzaj,
        String klasa,
        String urBroj,
        String privitakNaziv,
        byte[] privitakPdf,
        String jedinstveniIdentifikatorPismena,
        Instant poslanoAt
) {
}
