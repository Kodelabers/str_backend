package com.str.backend.validation.go;

import com.str.backend.core.CoreObjektEntity;
import com.str.backend.domain.Ponuda;
import com.str.backend.iznajmljivac.IznajmljivacEntity;
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

class Go1StatusDomacinaTest {

    private final Go1StatusDomacina step = new Go1StatusDomacina();

    @Test
    void marksAsDomacin_whenAdresaContainsGrad() {
        ValidacijskiKontekst ctx = kontekst("Zagreb, Ilica 1", "Zagreb", "Grad Zagreb");
        ValidacijskiRezultat r = step.provjeri(ctx);
        assertInstanceOf(ValidacijskiRezultat.Prosla.class, r);
        assertTrue(ctx.iznajmljivac().isDomacin());
    }

    @Test
    void marksAsNijeDomacin_whenAdresaInDifferentCity() {
        ValidacijskiKontekst ctx = kontekst("Split, Marmontova 5", "Zagreb", "Grad Zagreb");
        ValidacijskiRezultat r = step.provjeri(ctx);
        assertInstanceOf(ValidacijskiRezultat.Prosla.class, r);
        assertFalse(ctx.iznajmljivac().isDomacin());
    }

    @Test
    void matchIsCaseInsensitive() {
        ValidacijskiKontekst ctx = kontekst("splitsko-dalmatinska, omiš 10", "Omiš", "Splitsko-dalmatinska");
        step.provjeri(ctx);
        assertTrue(ctx.iznajmljivac().isDomacin(), "JLS match must be case-insensitive");
    }

    @Test
    void marksAsDomacin_whenAdresaContainsZupanija() {
        ValidacijskiKontekst ctx = kontekst("Splitsko-dalmatinska, Omiš 10", "Omiš", "Splitsko-dalmatinska");
        ValidacijskiRezultat r = step.provjeri(ctx);
        assertInstanceOf(ValidacijskiRezultat.Prosla.class, r);
        assertTrue(ctx.iznajmljivac().isDomacin());
    }

    @Test
    void alwaysReturnsProslaNeverRejects() {
        ValidacijskiKontekst ctx = kontekst(null, "Zagreb", "Grad Zagreb");
        assertInstanceOf(ValidacijskiRezultat.Prosla.class, step.provjeri(ctx));
    }

    private static ValidacijskiKontekst kontekst(String adresaPrebivalista, String grad, String zupanija) {
        UUID uuid = UUID.randomUUID();
        SsoEntity sso = SsoEntity.initiate(uuid, 2, 4, Ponuda.CJELINA, null, null);

        CoreObjektEntity core = mock(CoreObjektEntity.class);
        when(core.getGrad()).thenReturn(grad);
        when(core.getZupanija()).thenReturn(zupanija);

        IznajmljivacEntity iznajmljivac = IznajmljivacEntity.snapshot(
                uuid, "12345678901", "Marko Marić", adresaPrebivalista != null ? adresaPrebivalista : "");
        return new ValidacijskiKontekst(sso, core, iznajmljivac);
    }
}
