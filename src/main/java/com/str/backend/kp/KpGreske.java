package com.str.backend.kp;

import java.util.Collection;
import java.util.Set;

/**
 * Što šifra odbijanja znači za otpremu (šifrarnici tehničke spec. KP 3.6: M — omotnica SZP-a,
 * K — slanje FO, Z — slanje PS).
 *
 * <p>Nepoznata šifra je <b>prolazna</b>: bolje nekoliko suvišnih pokušaja s backoffom nego akt
 * koji zbog šifre koju ne poznajemo nikad ne ode. Ponavljanje je sigurno jer svaki pokušaj nosi
 * isti {@code IdPoruke}, pa drugi primitak iste poruke APIS odbija kao duplikat.
 */
public final class KpGreske {

    public enum Ishod {
        /** Poruka s našim {@code IdPoruke} već je zaprimljena — prijašnji pokušaj je prošao. */
        DUPLIKAT,
        /** Primatelj nema aktivan pretinac — dostava ide e-poštom. */
        NEMA_PRETINCA,
        /** Ponoviti s backoffom. */
        PROLAZNA,
        /** Konfiguracija, registracija ili podaci — ponavljanje ne pomaže, treba čovjek. */
        TRAJNA
    }

    private static final Set<String> DUPLIKAT = Set.of("K009", "Z012");
    private static final Set<String> NEMA_PRETINCA = Set.of("K006", "Z005");

    /**
     * Usluga nedostupna, sistemska pogreška ili pun pretinac (K011 — stranka ga može isprazniti).
     */
    private static final Set<String> PROLAZNE = Set.of("M008", "M009", "M010", "K011", "K999", "Z003");

    private static final Set<String> TRAJNE = Set.of(
            // omotnica: ovlaštenje, shema, registar pošiljatelja i usluga
            "M001", "M002", "M003", "M004", "M005", "M006", "M007", "M011", "M012",
            // FO
            "K001", "K002", "K003", "K004", "K005", "K007", "K010", "K012", "K013", "K015",
            "K016", "K017", "K018", "K019", "K020", "K021", "K022", "K023", "K024", "K025",
            "K026", "K027", "K028", "K029",
            // PS
            "Z001", "Z002", "Z004", "Z006", "Z007", "Z008", "Z009", "Z010", "Z011", "Z013",
            "Z014", "Z015", "Z016", "Z017", "Z018", "Z019", "Z020", "Z021", "Z022", "Z023");

    private KpGreske() {
    }

    public static Ishod classify(String sifra) {
        String s = sifra == null ? "" : sifra.strip().toUpperCase(java.util.Locale.ROOT);
        if (DUPLIKAT.contains(s)) {
            return Ishod.DUPLIKAT;
        }
        if (NEMA_PRETINCA.contains(s)) {
            return Ishod.NEMA_PRETINCA;
        }
        if (TRAJNE.contains(s)) {
            return Ishod.TRAJNA;
        }
        return Ishod.PROLAZNA;
    }

    /**
     * Ishod odgovora s više šifri. Trajna greška nadjačava sve: poruka koja je uz to i duplikat
     * ili ide primatelju bez pretinca svejedno je neispravna. Inače odlučuje najkonkretnija.
     */
    public static Ishod classify(Collection<String> sifre) {
        if (sifre.isEmpty()) {
            return Ishod.PROLAZNA;
        }
        Set<Ishod> ishodi = sifre.stream().map(KpGreske::classify).collect(java.util.stream.Collectors.toSet());
        for (Ishod i : new Ishod[] {Ishod.TRAJNA, Ishod.NEMA_PRETINCA, Ishod.DUPLIKAT}) {
            if (ishodi.contains(i)) {
                return i;
            }
        }
        return Ishod.PROLAZNA;
    }
}
