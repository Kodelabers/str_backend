package com.str.backend.validation.go;

import com.str.backend.sso.SsoEntity;
import com.str.backend.validation.ValidacijskaProvjera;
import com.str.backend.validation.ValidacijskiKontekst;
import com.str.backend.validation.ValidacijskiRezultat;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

@Component
public class Go4SuglasnostSuvlasnika implements ValidacijskaProvjera {

    private static final String STEP = "GO-4";

    private final Clock clock;

    public Go4SuglasnostSuvlasnika(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String step() { return STEP; }

    @Override
    public int order() { return 4; }

    @Override
    public java.util.Set<String> dependsOn() { return java.util.Set.of("GO-2"); }

    @Override
    public ValidacijskiRezultat provjeri(ValidacijskiKontekst kontekst) {
        if (!kontekst.zahtjevaSuglasnost()) {
            return new ValidacijskiRezultat.Prosla(STEP, "not required (GO-2 did not flag)");
        }
        SsoEntity sso = kontekst.sso();
        Boolean suglasnost = sso.getSuglasnostSuvlasnika();
        if (suglasnost == null || !suglasnost) {
            return new ValidacijskiRezultat.Odbijena(STEP, "nedostaje suglasnost suvlasnika");
        }
        LocalDate danas = LocalDate.now(clock);
        LocalDate datumPovlacenja = sso.getDatumPovlacenjaSuglasnosti();
        if (datumPovlacenja != null && !datumPovlacenja.isAfter(danas)) {
            return new ValidacijskiRezultat.Odbijena(STEP,
                    "suglasnost povucena dana " + datumPovlacenja);
        }
        return new ValidacijskiRezultat.Prosla(STEP, "suglasnost valjana");
    }
}
