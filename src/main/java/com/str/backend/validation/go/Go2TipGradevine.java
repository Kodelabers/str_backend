package com.str.backend.validation.go;

import com.str.backend.registries.MpgiClient;
import com.str.backend.validation.ValidacijskaProvjera;
import com.str.backend.validation.ValidacijskiKontekst;
import com.str.backend.validation.ValidacijskiRezultat;
import org.springframework.stereotype.Component;

@Component
public class Go2TipGradevine implements ValidacijskaProvjera {

    private static final String STEP = "GO-2";
    private static final int PRAG_JEDINICA = 3;

    private final MpgiClient mpgiClient;

    public Go2TipGradevine(MpgiClient mpgiClient) {
        this.mpgiClient = mpgiClient;
    }

    @Override
    public String step() { return STEP; }

    @Override
    public int order() { return 2; }

    @Override
    public ValidacijskiRezultat provjeri(ValidacijskiKontekst kontekst) {
        int jedinice = mpgiClient.brojStambenihJedinica(kontekst.coreObjekt().getAdresa());
        if (jedinice > PRAG_JEDINICA) {
            kontekst.markiraj();
            return new ValidacijskiRezultat.Prosla(STEP,
                    "stan u zgradi (" + jedinice + " jedinica) - GO-4 obvezan");
        }
        return new ValidacijskiRezultat.Prosla(STEP,
                jedinice + " jedinica - GO-4 nije obvezan");
    }
}
