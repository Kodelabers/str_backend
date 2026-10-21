package com.str.backend.kp.client;

import java.time.Instant;
import java.util.List;

/**
 * Stanje poruke s pravnim učinkom u pretincu (servisi 128/129).
 *
 * @param status      0 bez aktivnosti, 1 suglasnost, 2 potvrđen primitak, 3 odbijeno, 4 odustao;
 *                    {@code null} ako ga odgovor ne nosi
 * @param primitak    {@code DatumIVrijemePrimitkaPoruke}
 * @param aktivnost   {@code DatumIVrijemeAktivnosti} — zadnja aktivnost primatelja
 * @param istekRoka   {@code IstekRoka}
 * @param pdfPotvrde  potpisani PDF potvrde primitka, kad je primitak potvrđen
 */
public record KpAktivnost(List<String> sifre, String opis, Integer status, Instant primitak,
                          Instant aktivnost, Instant istekRoka, byte[] pdfPotvrde) {

    public KpAktivnost {
        sifre = List.copyOf(sifre);
    }

    public boolean ok() {
        return sifre.isEmpty();
    }
}
