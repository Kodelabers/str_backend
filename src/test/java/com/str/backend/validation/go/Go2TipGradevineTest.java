package com.str.backend.validation.go;

import com.str.backend.core.CoreObjektEntity;
import com.str.backend.domain.Ponuda;
import com.str.backend.iznajmljivac.IznajmljivacEntity;
import com.str.backend.registries.MpgiClient;
import com.str.backend.sso.SsoEntity;
import com.str.backend.validation.ValidacijskiKontekst;
import com.str.backend.validation.ValidacijskiRezultat;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Go2TipGradevineTest {

    private final MpgiClient mpgiClient = mock(MpgiClient.class);
    private final Go2TipGradevine step = new Go2TipGradevine(mpgiClient);

    @Test
    void markirajFlagsContext_whenJedinicaExceedThreshold() {
        when(mpgiClient.brojStambenihJedinica("Ilica 10")).thenReturn(10);
        ValidacijskiKontekst ctx = kontekst("Ilica 10");
        ValidacijskiRezultat r = step.provjeri(ctx);
        assertInstanceOf(ValidacijskiRezultat.Prosla.class, r);
        assertTrue(ctx.zahtjevaSuglasnost(), "GO-4 must be required when units > 3");
    }

    @Test
    void doesNotFlagContext_whenJedinicaAtOrBelowThreshold() {
        when(mpgiClient.brojStambenihJedinica("Kuca 1")).thenReturn(2);
        ValidacijskiKontekst ctx = kontekst("Kuca 1");
        ValidacijskiRezultat r = step.provjeri(ctx);
        assertInstanceOf(ValidacijskiRezultat.Prosla.class, r);
        assertFalse(ctx.zahtjevaSuglasnost(), "GO-4 must not be required when units <= 3");
    }

    @Test
    void exactlyAtThreshold_doesNotFlag() {
        when(mpgiClient.brojStambenihJedinica("Adresa")).thenReturn(3);
        ValidacijskiKontekst ctx = kontekst("Adresa");
        step.provjeri(ctx);
        assertFalse(ctx.zahtjevaSuglasnost());
    }

    @Test
    void alwaysReturnsProslaNeverRejects() {
        when(mpgiClient.brojStambenihJedinica("X")).thenReturn(100);
        assertInstanceOf(ValidacijskiRezultat.Prosla.class, step.provjeri(kontekst("X")));
    }

    private static ValidacijskiKontekst kontekst(String adresa) {
        UUID uuid = UUID.randomUUID();
        SsoEntity sso = SsoEntity.initiate(uuid, 2, 4, Ponuda.CJELINA, null, null);
        CoreObjektEntity core = mock(CoreObjektEntity.class);
        when(core.getAdresa()).thenReturn(adresa);
        IznajmljivacEntity iznajmljivac = IznajmljivacEntity.snapshot(
                uuid, "12345678901", "Ana Anić", "Zagreb");
        return new ValidacijskiKontekst(sso, core, iznajmljivac);
    }
}
