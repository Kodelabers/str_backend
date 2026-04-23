package com.str.backend.validation.go;

import com.str.backend.iznajmljivac.IznajmljivacEntity;
import com.str.backend.sso.SsoEntity;
import com.str.backend.validation.ValidacijskaProvjera;
import com.str.backend.validation.ValidacijskiKontekst;
import com.str.backend.validation.ValidacijskiRezultat;
import org.springframework.stereotype.Component;

@Component
public class Go1StatusDomacina implements ValidacijskaProvjera {

    private static final String STEP = "GO-1";

    @Override
    public String step() { return STEP; }

    @Override
    public int order() { return 2; }

    @Override
    public ValidacijskiRezultat provjeri(ValidacijskiKontekst kontekst) {
        IznajmljivacEntity iz = kontekst.iznajmljivac();
        SsoEntity sso = kontekst.sso();

        boolean zupanijaMatches = equalsIgnoreCaseNullSafe(iz.getZupanija(), sso.getZupanija());
        boolean domacin = zupanijaMatches && !sso.isZgrada();

        sso.markDomacin(domacin);

        return new ValidacijskiRezultat.Prosla(STEP,
                "domacin=" + domacin + " (zupanija=" + zupanijaMatches + ", zgrada=" + sso.isZgrada() + ")");
    }

    private static boolean equalsIgnoreCaseNullSafe(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }
}
