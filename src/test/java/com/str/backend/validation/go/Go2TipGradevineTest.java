package com.str.backend.validation.go;

import com.str.backend.iznajmljivac.IznajmljivacEntity;
import com.str.backend.registries.MpgiClient;
import com.str.backend.sso.SsoEntity;
import com.str.backend.validation.ValidacijskiKontekst;
import com.str.backend.validation.ValidacijskiRezultat;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Go2TipGradevineTest {

    private final MpgiClient mpgiClient = mock(MpgiClient.class);
    private final Go2TipGradevine step = new Go2TipGradevine(mpgiClient);

    @Test
    void flagsContext_whenJedinicaExceedThreshold() {
        SsoEntity sso = GoTestFixtures.sso("Grad Zagreb", "Zagreb", 2, 4, true, true, true);
        ValidacijskiKontekst ctx = ctx(sso);
        when(mpgiClient.brojStambenihJedinica("Ulica 1, Zagreb")).thenReturn(10);

        ValidacijskiRezultat r = step.provjeri(ctx);

        assertThat(r).isInstanceOf(ValidacijskiRezultat.Prosla.class);
        assertThat(ctx.zahtjevaSuglasnost()).isTrue();
    }

    @Test
    void doesNotFlag_whenJedinicaAtOrBelowThreshold() {
        SsoEntity sso = GoTestFixtures.sso("Grad Zagreb", "Zagreb", 2, 4, true, true, true);
        ValidacijskiKontekst ctx = ctx(sso);
        when(mpgiClient.brojStambenihJedinica(anyString())).thenReturn(3);

        step.provjeri(ctx);

        assertThat(ctx.zahtjevaSuglasnost()).isFalse();
    }

    @Test
    void skipsMpgi_whenNotZgrada() {
        SsoEntity sso = GoTestFixtures.sso("Grad Zagreb", "Zagreb", 2, 4, false, false, true);
        ValidacijskiKontekst ctx = ctx(sso);

        ValidacijskiRezultat r = step.provjeri(ctx);

        assertThat(r).isInstanceOf(ValidacijskiRezultat.Prosla.class);
        assertThat(ctx.zahtjevaSuglasnost()).isFalse();
        verify(mpgiClient, never()).brojStambenihJedinica(anyString());
    }

    @Test
    void skipsMpgi_whenZgradaButNotStanovi() {
        SsoEntity sso = GoTestFixtures.sso("Grad Zagreb", "Zagreb", 2, 4, true, false, true);
        ValidacijskiKontekst ctx = ctx(sso);

        step.provjeri(ctx);

        verify(mpgiClient, never()).brojStambenihJedinica(anyString());
    }

    private ValidacijskiKontekst ctx(SsoEntity sso) {
        IznajmljivacEntity iz = GoTestFixtures.iznajmljivac("Grad Zagreb", "Zagreb");
        return new ValidacijskiKontekst(sso, iz, null);
    }
}
