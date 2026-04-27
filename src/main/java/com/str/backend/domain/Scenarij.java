package com.str.backend.domain;

public enum Scenarij {
    /** STR-1.1: postojeći smještajni objekt — dodjela RB bez novog Zahtjeva. */
    S1_POSTOJECI_OBJEKT,
    /** STR-1.2: novi smještajni objekt — registracija preko vanjskog portala (iznajmljivač). */
    S2_NOVI_OBJEKT_VANJSKI,
    /** STR-1.2 varijanta: novi smještajni objekt — registracija preko internog portala (referent). */
    S3_NOVI_OBJEKT_INTERNI
}
