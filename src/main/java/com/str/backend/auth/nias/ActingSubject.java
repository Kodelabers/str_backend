package com.str.backend.auth.nias;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * Pravna osoba u čije ime NIAS korisnik trenutno djeluje, nakon što su e-Ovlaštenja potvrdila
 * da je njezin zakonski zastupnik. Drži se isključivo u server-side sesiji
 * ({@link ActingSubjectService#SESSION_KEY}) — klijent je nikad ne šalje.
 *
 * <p>{@link Serializable} jer Spring Session sprema sesiju u bazu.
 *
 * @param functions        nazivi funkcija iz e-Zastupanja (npr. „Direktor")
 * @param representativeOib OIB NIAS osobe koja je provjerena — subjekt vrijedi samo za nju
 */
public record ActingSubject(
        String legalOib,
        String legalName,
        List<String> functions,
        String representativeOib,
        String representativeFirstName,
        String representativeLastName,
        Instant verifiedAt
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public ActingSubject {
        functions = List.copyOf(functions);
    }
}
