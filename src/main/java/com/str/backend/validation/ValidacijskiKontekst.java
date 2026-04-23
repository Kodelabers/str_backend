package com.str.backend.validation;

import com.str.backend.core.CoreObjektEntity;
import com.str.backend.iznajmljivac.IznajmljivacEntity;
import com.str.backend.sso.SsoEntity;
import com.str.backend.zahtjev.ZahtjevEntity;

public final class ValidacijskiKontekst {

    private final ZahtjevEntity zahtjev;
    private final SsoEntity sso;
    private final IznajmljivacEntity iznajmljivac;
    private final CoreObjektEntity coreObjekt;
    private boolean zahtjevaSuglasnost;

    public ValidacijskiKontekst(ZahtjevEntity zahtjev, SsoEntity sso,
                                IznajmljivacEntity iznajmljivac, CoreObjektEntity coreObjekt) {
        this.zahtjev = zahtjev;
        this.sso = sso;
        this.iznajmljivac = iznajmljivac;
        this.coreObjekt = coreObjekt;
    }

    public ZahtjevEntity zahtjev() { return zahtjev; }
    public SsoEntity sso() { return sso; }
    public IznajmljivacEntity iznajmljivac() { return iznajmljivac; }
    public CoreObjektEntity coreObjekt() { return coreObjekt; }
    public boolean zahtjevaSuglasnost() { return zahtjevaSuglasnost; }

    public void markiraj() { this.zahtjevaSuglasnost = true; }
}
