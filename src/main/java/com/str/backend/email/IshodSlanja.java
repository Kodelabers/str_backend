package com.str.backend.email;

/**
 * Ishod predaje poruke SMTP poslužitelju. {@link #PREVELIKA} je zaseban jer ga ponovni pokušaj ne
 * popravlja: poruka iste veličine bila bi odbijena svaki put, sve do odustajanja — pozivatelj zato
 * odmah šalje manju varijantu (prilozi navedeni umjesto u privitku).
 */
public enum IshodSlanja {

    POSLANA,

    /** Prolazna ili nepoznata greška — ide u red ponovnog slanja. */
    NEUSPJELA,

    /** Poslužitelj je odbio poruku zbog veličine (SMTP 552 / 5.3.4, „message too large"). */
    PREVELIKA
}
