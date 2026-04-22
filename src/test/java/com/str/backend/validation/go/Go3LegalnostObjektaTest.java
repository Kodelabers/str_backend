package com.str.backend.validation.go;

import com.str.backend.core.CoreObjektEntity;
import com.str.backend.domain.Ponuda;
import com.str.backend.iznajmljivac.IznajmljivacEntity;
import com.str.backend.sso.SsoEntity;
import com.str.backend.validation.ValidacijskiKontekst;
import com.str.backend.validation.ValidacijskiRezultat;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Go3LegalnostObjektaTest {

    private final Go3LegalnostObjekta step = new Go3LegalnostObjekta();

    @Test
    void passes_whenObjektIsLegalan() {
        ValidacijskiKontekst ctx = kontekst(true);
        assertInstanceOf(ValidacijskiRezultat.Prosla.class, step.provjeri(ctx));
    }

    @Test
    void rejects_whenObjektIsNijeLegalan() {
        ValidacijskiKontekst ctx = kontekst(false);
        assertInstanceOf(ValidacijskiRezultat.Odbijena.class, step.provjeri(ctx));
    }

    private static ValidacijskiKontekst kontekst(boolean legalan) {
        UUID uuid = UUID.randomUUID();
        SsoEntity sso = SsoEntity.initiate(uuid, 2, 4, Ponuda.CJELINA, null, null);
        CoreObjektEntity core = mock(CoreObjektEntity.class);
        when(core.isLegalan()).thenReturn(legalan);
        IznajmljivacEntity iznajmljivac = IznajmljivacEntity.snapshot(
                uuid, "12345678901", "Pero Perić", "Zagreb");
        return new ValidacijskiKontekst(sso, core, iznajmljivac);
    }
}
