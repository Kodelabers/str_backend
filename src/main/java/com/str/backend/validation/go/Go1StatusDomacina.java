package com.str.backend.validation.go;

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
    public int order() { return 1; }

    @Override
    public ValidacijskiRezultat provjeri(ValidacijskiKontekst kontekst) {
        String adresa = kontekst.iznajmljivac().getAdresaPrebivalista();
        String objektZupanija = kontekst.coreObjekt().getZupanija();
        String objektGrad = kontekst.coreObjekt().getGrad();

        String adresaLower = adresa != null ? adresa.toLowerCase() : null;
        boolean istaJls = adresaLower != null
                && (adresaLower.contains(objektZupanija.toLowerCase())
                    || adresaLower.contains(objektGrad.toLowerCase()));
        kontekst.iznajmljivac().markDomacin(istaJls);

        return new ValidacijskiRezultat.Prosla(STEP,
                "is_domacin=" + istaJls);
    }
}
