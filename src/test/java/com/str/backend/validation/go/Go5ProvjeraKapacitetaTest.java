package com.str.backend.validation.go;

import com.str.backend.core.CoreObjektEntity;
import com.str.backend.iznajmljivac.IznajmljivacEntity;
import com.str.backend.sso.SsoEntity;
import com.str.backend.validation.ValidacijskiKontekst;
import com.str.backend.validation.ValidacijskiRezultat;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Go5ProvjeraKapacitetaTest {

    private final Go5ProvjeraKapaciteta step = new Go5ProvjeraKapaciteta();

    @Test
    void rejectsWhenBedsExceedMax() {
        assertThat(step.provjeri(ctx(6, 10, 4, 10)))
                .isInstanceOf(ValidacijskiRezultat.Odbijena.class);
    }

    @Test
    void rejectsWhenGuestsExceedMax() {
        assertThat(step.provjeri(ctx(4, 12, 4, 10)))
                .isInstanceOf(ValidacijskiRezultat.Odbijena.class);
    }

    @Test
    void acceptsWhenWithinLimits() {
        assertThat(step.provjeri(ctx(4, 8, 4, 10)))
                .isInstanceOf(ValidacijskiRezultat.Prosla.class);
    }

    @Test
    void passes_whenCoreNull() {
        SsoEntity sso = GoTestFixtures.sso("Grad Zagreb", "Zagreb", 100, 200, true, true, true);
        IznajmljivacEntity iz = GoTestFixtures.iznajmljivac("Grad Zagreb", "Zagreb");
        ValidacijskiKontekst k = new ValidacijskiKontekst(sso, iz, null);
        assertThat(step.provjeri(k)).isInstanceOf(ValidacijskiRezultat.Prosla.class);
    }

    private ValidacijskiKontekst ctx(int kreveti, int gosti, int maxKreveta, int maxGostiju) {
        SsoEntity sso = GoTestFixtures.sso("Grad Zagreb", "Zagreb", kreveti, gosti, true, true, true);
        CoreObjektEntity core = GoTestFixtures.coreObjekt(maxKreveta, maxGostiju, true);
        IznajmljivacEntity iz = GoTestFixtures.iznajmljivac("Grad Zagreb", "Zagreb");
        return new ValidacijskiKontekst(sso, iz, core);
    }
}
