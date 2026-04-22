package com.str.backend.validation.go;

import com.str.backend.core.CoreObjektEntity;
import com.str.backend.domain.Ponuda;
import com.str.backend.iznajmljivac.IznajmljivacEntity;
import com.str.backend.sso.SsoEntity;
import com.str.backend.validation.ValidacijskiKontekst;
import com.str.backend.validation.ValidacijskiRezultat;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class Go5ProvjeraKapacitetaTest {

    private final Go5ProvjeraKapaciteta step = new Go5ProvjeraKapaciteta();

    @Test
    void rejectsWhenBedsExceedMax() {
        ValidacijskiKontekst kontekst = kontekst(6, 10, 4, 10);
        ValidacijskiRezultat r = step.provjeri(kontekst);
        assertInstanceOf(ValidacijskiRezultat.Odbijena.class, r);
    }

    @Test
    void rejectsWhenGuestsExceedMax() {
        ValidacijskiKontekst kontekst = kontekst(4, 12, 4, 10);
        ValidacijskiRezultat r = step.provjeri(kontekst);
        assertInstanceOf(ValidacijskiRezultat.Odbijena.class, r);
    }

    @Test
    void acceptsWhenWithinLimits() {
        ValidacijskiKontekst kontekst = kontekst(4, 8, 4, 10);
        ValidacijskiRezultat r = step.provjeri(kontekst);
        assertInstanceOf(ValidacijskiRezultat.Prosla.class, r);
    }

    private static ValidacijskiKontekst kontekst(int kreveti, int gosti, int maxKreveta, int maxGostiju) {
        UUID uuid = UUID.randomUUID();
        SsoEntity sso = SsoEntity.initiate(uuid, kreveti, gosti, Ponuda.CJELINA, null, null);

        CoreObjektEntity core = Mockito.mock(CoreObjektEntity.class);
        Mockito.when(core.getMaxKreveta()).thenReturn(maxKreveta);
        Mockito.when(core.getMaxGostiju()).thenReturn(maxGostiju);

        IznajmljivacEntity iznajmljivac = IznajmljivacEntity.snapshot(
                uuid, "12345678901", "Ivan Horvat", "Zagreb, Ilica 1");
        return new ValidacijskiKontekst(sso, core, iznajmljivac);
    }
}
