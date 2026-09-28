package com.str.backend.lessor;

/** Odakle je podatak o subjektu — frontend po tome označava izvor polja na formi. */
public enum SubjectDataSource {
    /** SAML assertion — mjerodavan za OIB, ime i prezime. */
    NIAS,
    /** OIB sustav Porezne uprave, preko {@code str-internal-api/pretraga-registra/oib}. */
    OIB_REGISTAR,
    /** eTurizam {@code str.subject*} — privremeni izvor dok OIB sustav nije dostupan. */
    STR_SUBJEKT
}
