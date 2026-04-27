package com.str.backend.validation.go;

import com.str.backend.iznajmljivac.IznajmljivacEntity;
import com.str.backend.sso.SsoEntity;
import com.str.backend.validation.ValidacijskiKontekst;
import com.str.backend.validation.ValidacijskiRezultat;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Go1StatusDomacinaTest {

    private final Go1StatusDomacina step = new Go1StatusDomacina();

    @Test
    void marksDomacinTrue_whenZupanijaMatchesAndNotZgrada() {
        IznajmljivacEntity iz = GoTestFixtures.iznajmljivac("Grad Zagreb", "Zagreb");
        // zgrada=false → qualifies for domacin
        SsoEntity sso = GoTestFixtures.sso("Grad Zagreb", "Zagreb", 2, 4, false, false, true);
        ValidacijskiKontekst ctx = new ValidacijskiKontekst(sso, iz, null);

        ValidacijskiRezultat r = step.provjeri(ctx);

        assertThat(r).isInstanceOf(ValidacijskiRezultat.Prosla.class);
        assertThat(sso.getDomacin()).isTrue();
    }

    @Test
    void marksDomacinFalse_whenZupanijaMatchesButObjectIsZgrada() {
        IznajmljivacEntity iz = GoTestFixtures.iznajmljivac("Grad Zagreb", "Zagreb");
        // Zgrada=true → disqualifies even if zupanija matches (per ZAK-2.1 rule)
        SsoEntity sso = GoTestFixtures.sso("Grad Zagreb", "Zagreb", 2, 4, true, true, true);
        ValidacijskiKontekst ctx = new ValidacijskiKontekst(sso, iz, null);

        step.provjeri(ctx);

        assertThat(sso.getDomacin()).isFalse();
    }

    @Test
    void marksDomacinFalse_whenZupanijaDiffers() {
        IznajmljivacEntity iz = GoTestFixtures.iznajmljivac("Grad Zagreb", "Zagreb");
        SsoEntity sso = GoTestFixtures.sso("Splitsko-dalmatinska", "Split", 2, 4, false, false, true);
        ValidacijskiKontekst ctx = new ValidacijskiKontekst(sso, iz, null);

        step.provjeri(ctx);

        assertThat(sso.getDomacin()).isFalse();
    }

    @Test
    void matchIsCaseInsensitiveOnZupanijaOnly() {
        // Per ZAK-2.1: only zupanija is compared, not mjesto/grad
        IznajmljivacEntity iz = GoTestFixtures.iznajmljivac("splitsko-dalmatinska", "Omiš");
        SsoEntity sso = GoTestFixtures.sso("Splitsko-Dalmatinska", "Split", 2, 4, false, false, true);
        ValidacijskiKontekst ctx = new ValidacijskiKontekst(sso, iz, null);

        step.provjeri(ctx);

        assertThat(sso.getDomacin()).isTrue();
    }

    @Test
    void alwaysReturnsProsla_evenOnMismatch() {
        IznajmljivacEntity iz = GoTestFixtures.iznajmljivac("Grad Zagreb", "Zagreb");
        SsoEntity sso = GoTestFixtures.sso("Istarska", "Pula", 2, 4, true, true, true);
        ValidacijskiKontekst ctx = new ValidacijskiKontekst(sso, iz, null);

        assertThat(step.provjeri(ctx)).isInstanceOf(ValidacijskiRezultat.Prosla.class);
    }
}
