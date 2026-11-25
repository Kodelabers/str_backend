package com.str.backend.objection;

import com.str.backend.rn.AktSluzbenika;

/**
 * Što službenik daje uz neuvažavanje očitovanja ili prigovora (UAT 25.11.2026).
 *
 * @param note                bilješka uz odluku ({@code suspension_objection.resolution_note}); ne ispisuje se na aktu
 * @param zupanijskiSluzbenik službenik je županijski ({@code RucniAktService#zupanijskiSluzbenik}) — tada su KLASA i
 *                            URBROJ akta koji neuvažavanje izdaje obavezni
 * @param akt                 ručni akt (T16; {@code null} = eGOP) i prilozi službenika uz akt koji neuvažavanje
 *                            izdaje ({@link AktNeuvazavanja}); obrazloženje se ne koristi
 * @param rjesenjePrilogId    dokument Rješenja — obavezan uz neuvaženi prigovor na mjeru
 *                            ({@link AktNeuvazavanja#NEPRIHVACANJE}), inače mora biti {@code null}
 * @param rjesenjeKlasa       KLASA Rješenja, obavezna uz Rješenje u županijskom postupku (ručni akt)
 * @param rjesenjeUrbroj      URBROJ Rješenja; kao {@code rjesenjeKlasa}
 */
public record Neuvazavanje(String note, boolean zupanijskiSluzbenik, AktSluzbenika akt, Long rjesenjePrilogId,
                           String rjesenjeKlasa, String rjesenjeUrbroj) {

    public Neuvazavanje {
        akt = akt != null ? akt : AktSluzbenika.NEMA;
    }

    /** Samo bilješka — odluka bez akta, ili akt kroz eGOP bez priloga. */
    public static Neuvazavanje biljeska(String note, boolean zupanijskiSluzbenik) {
        return new Neuvazavanje(note, zupanijskiSluzbenik, AktSluzbenika.NEMA, null, null, null);
    }
}
