package com.str.backend.validation;

public interface ValidacijskaProvjera {

    String step();

    int order();

    ValidacijskiRezultat provjeri(ValidacijskiKontekst kontekst);
}
