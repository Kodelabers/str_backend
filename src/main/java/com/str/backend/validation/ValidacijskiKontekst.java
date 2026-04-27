package com.str.backend.validation;

import com.str.backend.core.CoreObjektEntity;
import com.str.backend.iznajmljivac.IznajmljivacEntity;
import com.str.backend.sso.SsoEntity;

public final class ValidacijskiKontekst {

    private final SsoEntity sso;
    private final IznajmljivacEntity iznajmljivac;
    private final CoreObjektEntity coreObjekt;
    private boolean zahtjevaSuglasnost;

    public ValidacijskiKontekst(SsoEntity sso, IznajmljivacEntity iznajmljivac, CoreObjektEntity coreObjekt) {
        this.sso = sso;
        this.iznajmljivac = iznajmljivac;
        this.coreObjekt = coreObjekt;
    }

    public SsoEntity sso() { return sso; }
    public IznajmljivacEntity iznajmljivac() { return iznajmljivac; }
    public CoreObjektEntity coreObjekt() { return coreObjekt; }
    public boolean zahtjevaSuglasnost() { return zahtjevaSuglasnost; }

    public void markiraj() { this.zahtjevaSuglasnost = true; }
}
