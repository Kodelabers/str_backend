package com.str.backend.validation.go;

import com.str.backend.core.CoreObjektEntity;
import com.str.backend.sso.SsoEntity;
import com.str.backend.validation.ValidacijskaProvjera;
import com.str.backend.validation.ValidacijskiKontekst;
import com.str.backend.validation.ValidacijskiRezultat;
import org.springframework.stereotype.Component;

@Component
public class Go5ProvjeraKapaciteta implements ValidacijskaProvjera {

    private static final String STEP = "GO-5";

    @Override
    public String step() { return STEP; }

    @Override
    public int order() { return 5; }

    @Override
    public ValidacijskiRezultat provjeri(ValidacijskiKontekst kontekst) {
        SsoEntity sso = kontekst.sso();
        CoreObjektEntity core = kontekst.coreObjekt();
        if (core == null) {
            return new ValidacijskiRezultat.Prosla(STEP,
                    "nema core rjesenja - prihvacamo prijavljeni kapacitet");
        }
        if (sso.getMaxKreveta() > core.getMaxKreveta()) {
            return new ValidacijskiRezultat.Odbijena(STEP,
                    "max_kreveta=" + sso.getMaxKreveta() + " prelazi rjesenje=" + core.getMaxKreveta());
        }
        if (sso.getMaxGostiju() > core.getMaxGostiju()) {
            return new ValidacijskiRezultat.Odbijena(STEP,
                    "max_gostiju=" + sso.getMaxGostiju() + " prelazi rjesenje=" + core.getMaxGostiju());
        }
        return new ValidacijskiRezultat.Prosla(STEP,
                "kreveti=" + sso.getMaxKreveta() + "/" + core.getMaxKreveta()
                        + ", gosti=" + sso.getMaxGostiju() + "/" + core.getMaxGostiju());
    }
}
