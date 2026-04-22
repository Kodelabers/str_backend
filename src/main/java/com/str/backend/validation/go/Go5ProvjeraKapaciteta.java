package com.str.backend.validation.go;

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
        int uneseniKreveti = kontekst.sso().getKapacitetKreveta();
        int uneseniGosti = kontekst.sso().getKapacitetGostiju();
        int maxKreveta = kontekst.coreObjekt().getMaxKreveta();
        int maxGostiju = kontekst.coreObjekt().getMaxGostiju();

        if (uneseniKreveti > maxKreveta) {
            return new ValidacijskiRezultat.Odbijena(STEP,
                    "kapacitet_kreveta=" + uneseniKreveti + " prelazi max=" + maxKreveta);
        }
        if (uneseniGosti > maxGostiju) {
            return new ValidacijskiRezultat.Odbijena(STEP,
                    "kapacitet_gostiju=" + uneseniGosti + " prelazi max=" + maxGostiju);
        }
        return new ValidacijskiRezultat.Prosla(STEP,
                "kreveta=" + uneseniKreveti + "/" + maxKreveta + ", gostiju=" + uneseniGosti + "/" + maxGostiju);
    }
}
