package com.str.backend.email;

/**
 * Što poruka kaže o dostavi akta — klauzula na dnu poruke ({@code klauzula.dostava}).
 */
public enum Dostava {

    /** Akt se dostavlja u korisnički pretinac; poruka je samo obavijest. */
    PRETINAC,

    /** Non-EU iznajmljivač nema pretinac — e-pošta je kanal dostave i od nje teku rokovi. */
    EPOSTA,

    /**
     * Akt ne ide u pretinac i nije akt koji se stranci dostavlja: opoziv je u eGOP-u ulazno
     * pismeno (radnju je pokrenula stranka), pa ga eGOP ne otprema u KP i nema rokova.
     */
    BEZ_KLAUZULE
}
