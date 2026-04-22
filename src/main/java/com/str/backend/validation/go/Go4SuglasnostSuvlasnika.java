package com.str.backend.validation.go;

import com.str.backend.registries.DguClient;
import com.str.backend.validation.ValidacijskaProvjera;
import com.str.backend.validation.ValidacijskiKontekst;
import com.str.backend.validation.ValidacijskiRezultat;
import org.springframework.stereotype.Component;

@Component
public class Go4SuglasnostSuvlasnika implements ValidacijskaProvjera {

    private static final String STEP = "GO-4";

    private final DguClient dguClient;

    public Go4SuglasnostSuvlasnika(DguClient dguClient) {
        this.dguClient = dguClient;
    }

    @Override
    public String step() { return STEP; }

    @Override
    public int order() { return 4; }

    @Override
    public ValidacijskiRezultat provjeri(ValidacijskiKontekst kontekst) {
        if (!kontekst.zahtjevaSuglasnost()) {
            return new ValidacijskiRezultat.Prosla(STEP, "not required (GO-2 did not flag)");
        }
        boolean valjanaSuglasnost = dguClient.postojiValjanaSuglasnost(kontekst.sso().getUuidSso());
        if (valjanaSuglasnost) {
            return new ValidacijskiRezultat.Prosla(STEP, "suglasnost potvrdjena");
        }
        return new ValidacijskiRezultat.CekaCallback(STEP, "nedostaje digitalna suglasnost suvlasnika");
    }
}
