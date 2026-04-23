package com.str.backend.validation.go;

import com.str.backend.core.CoreObjektEntity;
import com.str.backend.sso.SsoEntity;
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
        SsoEntity sso = kontekst.sso();
        if (!sso.isLegalizirano()) {
            return new ValidacijskiRezultat.Odbijena(STEP, "SSO nije legalizirano");
        }
        CoreObjektEntity core = kontekst.coreObjekt();
        if (core != null && !core.isLegalan()) {
            return new ValidacijskiRezultat.Odbijena(STEP,
                    "core rjesenje pokazuje da objekt nije legalan");
        }
        return new ValidacijskiRezultat.Prosla(STEP, "legalizirano");
    }
}
