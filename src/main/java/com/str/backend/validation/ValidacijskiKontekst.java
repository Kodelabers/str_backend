package com.str.backend.validation;

import com.str.backend.core.CoreObjektEntity;
import com.str.backend.iznajmljivac.IznajmljivacEntity;
import com.str.backend.sso.SsoEntity;

public final class ValidacijskiKontekst {

    private final SsoEntity sso;
    private final CoreObjektEntity coreObjekt;
    private final IznajmljivacEntity iznajmljivac;
    private boolean zahtjevaSuglasnost;

    public ValidacijskiKontekst(SsoEntity sso, CoreObjektEntity coreObjekt, IznajmljivacEntity iznajmljivac) {
        this.sso = sso;
        this.coreObjekt = coreObjekt;
        this.iznajmljivac = iznajmljivac;
    }

    public SsoEntity sso() { return sso; }
    public CoreObjektEntity coreObjekt() { return coreObjekt; }
    public IznajmljivacEntity iznajmljivac() { return iznajmljivac; }
    public boolean zahtjevaSuglasnost() { return zahtjevaSuglasnost; }

    public void markiraj() { this.zahtjevaSuglasnost = true; }
}
