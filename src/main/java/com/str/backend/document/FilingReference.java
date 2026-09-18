package com.str.backend.document;

/**
 * Urudžbene oznake akta iz eGOP-a. KLASA je na predmetu, URBROJ na pojedinom pismenu — pa
 * dva akta u istom predmetu dijele KLASU, a razlikuju se po URBROJ-u.
 *
 * <p>{@code jop} je eGOP-ov interni identifikator pismena. Na aktu se ispisuje kao
 * „P/&lt;jop&gt;" u desnom kutu zaglavlja, kako to ima uredski predložak ministarstva.
 *
 * <p>Sve vrijednosti mogu nedostajati: akt se smije renderirati i prije urudžbiranja (npr.
 * pregled prije slanja), a s ugašenom eGOP integracijom ih nikad i nema.
 */
public record FilingReference(String klasa, String urBroj, Integer jop) {

    public static final FilingReference NONE = new FilingReference(null, null, null);

    /** Oznake bez JOP-a — pismeno još ne postoji ili se JOP za taj akt ne ispisuje. */
    public FilingReference(String klasa, String urBroj) {
        this(klasa, urBroj, null);
    }

    public boolean isEmpty() {
        return (klasa == null || klasa.isBlank())
                && (urBroj == null || urBroj.isBlank())
                && jop == null;
    }
}
