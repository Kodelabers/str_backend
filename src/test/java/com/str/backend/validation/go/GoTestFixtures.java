package com.str.backend.validation.go;

import com.str.backend.core.CoreObjektEntity;
import com.str.backend.domain.Kanal;
import com.str.backend.domain.Ponuda;
import com.str.backend.iznajmljivac.IznajmljivacEntity;
import com.str.backend.sso.SsoEntity;
import com.str.backend.zahtjev.ZahtjevEntity;

import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class GoTestFixtures {

    private GoTestFixtures() {}

    static IznajmljivacEntity iznajmljivac(String zupanija, String mjesto) {
        return IznajmljivacEntity.create(
                "Marko", "Maric", "Ilica", "1", mjesto, zupanija, "marko@example.com");
    }

    static SsoEntity sso(String zupanija, String grad, int maxKreveta, int maxGostiju,
                         boolean zgrada, boolean stanovi, boolean legalizirano) {
        return SsoEntity.create(
                UUID.randomUUID(), zupanija, grad, "Ulica", "1",
                maxKreveta, maxGostiju, Ponuda.CJELINA,
                zgrada, stanovi, legalizirano);
    }

    static SsoEntity ssoWithSuglasnost(Boolean suglasnost, LocalDate datumSuglasnosti,
                                       LocalDate datumPovlacenja) {
        SsoEntity sso = sso("Zagreb", "Zagreb", 2, 4, true, true, true);
        sso.setSuglasnost(suglasnost, datumSuglasnosti, datumPovlacenja);
        return sso;
    }

    static ZahtjevEntity zahtjev(UUID idIznajmljivaca) {
        return ZahtjevEntity.initiate("UR-2026-000001", Kanal.NIAS, "NOVA", idIznajmljivaca, null);
    }

    static CoreObjektEntity coreObjekt(int maxKreveta, int maxGostiju, boolean legalan) {
        CoreObjektEntity core = mock(CoreObjektEntity.class);
        lenient().when(core.getUuid()).thenReturn(UUID.randomUUID());
        lenient().when(core.getMaxKreveta()).thenReturn(maxKreveta);
        lenient().when(core.getMaxGostiju()).thenReturn(maxGostiju);
        lenient().when(core.isLegalan()).thenReturn(legalan);
        return core;
    }
}
