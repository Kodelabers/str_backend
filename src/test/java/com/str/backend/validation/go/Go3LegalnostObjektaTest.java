package com.str.backend.validation.go;

import com.str.backend.core.CoreObjektEntity;
import com.str.backend.iznajmljivac.IznajmljivacEntity;
import com.str.backend.sso.SsoEntity;
import com.str.backend.validation.ValidacijskiKontekst;
import com.str.backend.validation.ValidacijskiRezultat;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Go3LegalnostObjektaTest {

    private final Go3LegalnostObjekta step = new Go3LegalnostObjekta();

    @Test
    void passes_whenSsoLegaliziranoAndNoCore() {
        SsoEntity sso = GoTestFixtures.sso("Grad Zagreb", "Zagreb", 2, 4, true, true, true);
        assertThat(step.provjeri(ctx(sso, null))).isInstanceOf(ValidacijskiRezultat.Prosla.class);
    }

    @Test
    void passes_whenSsoLegaliziranoAndCoreLegalan() {
        SsoEntity sso = GoTestFixtures.sso("Grad Zagreb", "Zagreb", 2, 4, true, true, true);
        CoreObjektEntity core = GoTestFixtures.coreObjekt(4, 8, true);
        assertThat(step.provjeri(ctx(sso, core))).isInstanceOf(ValidacijskiRezultat.Prosla.class);
    }

    @Test
    void rejects_whenSsoNotLegalizirano() {
        SsoEntity sso = GoTestFixtures.sso("Grad Zagreb", "Zagreb", 2, 4, true, true, false);
        assertThat(step.provjeri(ctx(sso, null))).isInstanceOf(ValidacijskiRezultat.Odbijena.class);
    }

    @Test
    void rejects_whenCoreNijeLegalan() {
        SsoEntity sso = GoTestFixtures.sso("Grad Zagreb", "Zagreb", 2, 4, true, true, true);
        CoreObjektEntity core = GoTestFixtures.coreObjekt(4, 8, false);
        assertThat(step.provjeri(ctx(sso, core))).isInstanceOf(ValidacijskiRezultat.Odbijena.class);
    }

    private ValidacijskiKontekst ctx(SsoEntity sso, CoreObjektEntity core) {
        IznajmljivacEntity iz = GoTestFixtures.iznajmljivac("Grad Zagreb", "Zagreb");
        return new ValidacijskiKontekst(sso, iz, core);
    }
}
