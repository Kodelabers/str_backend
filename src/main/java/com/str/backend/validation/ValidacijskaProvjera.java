package com.str.backend.validation;

import java.util.Set;

public interface ValidacijskaProvjera {

    String step();

    int order();

    /**
     * Steps this check must run after. Used by {@link ParallelValidacijskiOrkestrator}
     * to schedule waves: wave N contains checks whose dependencies completed in wave &lt; N.
     * Default: no dependencies (independent / first wave).
     */
    default Set<String> dependsOn() { return Set.of(); }

    ValidacijskiRezultat provjeri(ValidacijskiKontekst kontekst);
}
