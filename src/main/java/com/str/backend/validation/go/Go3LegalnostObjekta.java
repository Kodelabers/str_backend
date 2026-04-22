package com.str.backend.validation.go;

import com.str.backend.validation.ValidacijskaProvjera;
import com.str.backend.validation.ValidacijskiKontekst;
import com.str.backend.validation.ValidacijskiRezultat;
import org.springframework.stereotype.Component;

@Component
public class Go3LegalnostObjekta implements ValidacijskaProvjera {

    private static final String STEP = "GO-3";

    @Override
    public String step() { return STEP; }

    @Override
    public int order() { return 3; }

    @Override
    public ValidacijskiRezultat provjeri(ValidacijskiKontekst kontekst) {
        if (!kontekst.coreObjekt().isLegalan()) {
            return new ValidacijskiRezultat.Odbijena(STEP,
                    "Objekt nije legalan prema core rjesenju");
        }
        return new ValidacijskiRezultat.Prosla(STEP, "legalan");
    }
}
