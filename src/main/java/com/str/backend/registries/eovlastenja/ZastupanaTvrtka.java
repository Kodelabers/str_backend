package com.str.backend.registries.eovlastenja;

import java.io.Serializable;

/**
 * Subjekt (tvrtka, obrt, OPG) koji osoba zastupa prema popisu iz {@code GetNavigationData}. Popis je nepotpisan i služi
 * samo za prikaz: odabir se uvijek potvrđuje potpisanim {@link EOvlastenjaClient#verifyRepresentation}.
 *
 * <p>{@link Serializable} jer se popis kratko čuva u sesiji (Spring Session JDBC).
 */
public record ZastupanaTvrtka(String oib, String naziv, String izvorReg) implements Serializable {

    /** Tvrtka iz sudskog registra. */
    public ZastupanaTvrtka(String oib, String naziv) {
        this(oib, naziv, LegalForm.TVRTKA.izvorReg());
    }

    /** Pravni oblik subjekta; popis spremljen u sesiju prije obrta i OPG-a nema registar — tada je tvrtka. */
    public LegalForm legalForm() {
        return LegalForm.fromIzvorReg(izvorReg).orElse(LegalForm.TVRTKA);
    }
}
