package com.str.backend.validation.go;

import com.str.backend.core.CoreObjektEntity;
import com.str.backend.domain.Ponuda;
import com.str.backend.iznajmljivac.IznajmljivacEntity;
import com.str.backend.registries.DguClient;
import com.str.backend.sso.SsoEntity;
import com.str.backend.validation.ValidacijskiKontekst;
import com.str.backend.validation.ValidacijskiRezultat;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class Go4SuglasnostSuvlasnikaTest {

    private final DguClient dguClient = mock(DguClient.class);
    private final Go4SuglasnostSuvlasnika step = new Go4SuglasnostSuvlasnika(dguClient);

    @Test
    void skips_whenGo2DidNotFlag() {
        ValidacijskiKontekst ctx = kontekst(false);
        assertInstanceOf(ValidacijskiRezultat.Prosla.class, step.provjeri(ctx));
        verifyNoInteractions(dguClient);
    }

    @Test
    void passes_whenSuglasnostExists_andGo2Flagged() {
        UUID uuid = UUID.randomUUID();
        ValidacijskiKontekst ctx = kontekstFlagged(uuid);
        when(dguClient.postojiValjanaSuglasnost(uuid)).thenReturn(true);
        assertInstanceOf(ValidacijskiRezultat.Prosla.class, step.provjeri(ctx));
    }

    @Test
    void cekaCallback_whenSuglasnostMissing_andGo2Flagged() {
        UUID uuid = UUID.randomUUID();
        ValidacijskiKontekst ctx = kontekstFlagged(uuid);
        when(dguClient.postojiValjanaSuglasnost(uuid)).thenReturn(false);
        assertInstanceOf(ValidacijskiRezultat.CekaCallback.class, step.provjeri(ctx));
    }

    private static ValidacijskiKontekst kontekst(boolean flagged) {
        UUID uuid = UUID.randomUUID();
        return buildCtx(uuid, flagged);
    }

    private static ValidacijskiKontekst kontekstFlagged(UUID uuid) {
        return buildCtx(uuid, true);
    }

    private static ValidacijskiKontekst buildCtx(UUID uuid, boolean flagged) {
        SsoEntity sso = SsoEntity.initiate(uuid, 2, 4, Ponuda.CJELINA, null, null);
        CoreObjektEntity core = mock(CoreObjektEntity.class);
        IznajmljivacEntity iznajmljivac = IznajmljivacEntity.snapshot(
                uuid, "12345678901", "Iva Ivić", "Zagreb");
        ValidacijskiKontekst ctx = new ValidacijskiKontekst(sso, core, iznajmljivac);
        if (flagged) {
            ctx.markiraj();
        }
        return ctx;
    }
}
