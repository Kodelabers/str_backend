package com.str.backend.document;

/**
 * Urudžbene oznake akta iz eGOP-a. KLASA je na predmetu, URBROJ na pojedinom pismenu — pa
 * dva akta u istom predmetu dijele KLASU, a razlikuju se po URBROJ-u.
 *
 * <p>Obje vrijednosti mogu nedostajati: akt se smije renderirati i prije urudžbiranja (npr.
 * pregled prije slanja), a s ugašenom eGOP integracijom ih nikad i nema.
 */
public record FilingReference(String klasa, String urBroj) {

    public static final FilingReference NONE = new FilingReference(null, null);

    public boolean isEmpty() {
        return (klasa == null || klasa.isBlank()) && (urBroj == null || urBroj.isBlank());
    }
}
