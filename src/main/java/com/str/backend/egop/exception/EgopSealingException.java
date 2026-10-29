package com.str.backend.egop.exception;

import com.str.backend.eseal.EsealException;

/**
 * Izlazni akt nije pečatiran, pa se ne prilaže u eGOP i ne otprema u KP — nepečaćeni akt tijela
 * ne smije stranci. {@link EgopException} je da ga eGOP tok obradi kao svaki drugi pad koraka:
 * poruka ide u {@code sync_error}, a ponavlja ga retry job.
 */
public class EgopSealingException extends EgopException {

    public EgopSealingException(EsealException cause) {
        super(cause.getMessage(), cause);
    }

    public EgopSealingException(String message) {
        super(message);
    }
}
