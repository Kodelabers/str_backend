package com.str.backend.registries.eovlastenja;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * {@code AuthorizationUnionPermissionRequest} za provjeru zastupanja tvrtke ({@code IZVOR_REG=1}).
 *
 * <p>I {@code JipsTo} i {@code IdentfiersFor} nose istu tvrtku: e-Ovlaštenja vraćaju
 * {@code Representation} (e-Zastupanja) samo kad se subjekt „u ime" i subjekt „u okviru"
 * poklapaju. Naziv {@code IdentfiersFor} (sic) je doslovno iz FINA primjera i sheme.
 *
 * <p>Vrijednosti se umeću u XML tek nakon provjere formata — ništa što nije znamenka ili
 * alfanumerički identifikator sjednice ne može promijeniti strukturu poruke.
 */
record EOvlastenjaRequest(String id, String xml) {

    private static final Pattern OIB = Pattern.compile("\\d{11}");
    private static final Pattern SESIJA_ID = Pattern.compile("[A-Za-z0-9-]{1,100}");

    static EOvlastenjaRequest of(String sesijaId, String personOib, String legalOib) {
        require(sesijaId, SESIJA_ID, "sesija_id");
        require(personOib, OIB, "OIB osobe");
        require(legalOib, OIB, "OIB tvrtke");
        String id = "_" + UUID.randomUUID().toString().replace("-", "");
        String xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <AuthorizationUnionPermissionRequest xmlns:b="http://eovlastenja.fina.hr/authorizationbase/v2" \
                Id="%s" xmlns="http://eovlastenja.fina.hr/RoAuthUnionApi/v2">
                  <Sesija_Id>%s</Sesija_Id>
                  <PersonOIB>%s</PersonOIB>
                  <JipsTo>
                    <b:IPS>%s</b:IPS>
                    <b:IZVOR_REG>1</b:IZVOR_REG>
                  </JipsTo>
                  <IdentfiersFor>
                    <b:LegalJips>
                      <b:IPS>%s</b:IPS>
                      <b:IZVOR_REG>1</b:IZVOR_REG>
                    </b:LegalJips>
                  </IdentfiersFor>
                </AuthorizationUnionPermissionRequest>
                """.formatted(id, sesijaId, personOib, legalOib, legalOib);
        return new EOvlastenjaRequest(id, xml);
    }

    /**
     * {@code NavigationDataRequest}: popis subjekata za koje osoba ima prava. Bez {@code JipsTo} —
     * osoba djeluje osobnom vjerodajnicom, kao građanin.
     */
    static EOvlastenjaRequest navigation(String sesijaId, String personOib) {
        require(sesijaId, SESIJA_ID, "sesija_id");
        require(personOib, OIB, "OIB osobe");
        String id = "_" + UUID.randomUUID().toString().replace("-", "");
        String xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <NavigationDataRequest Id="%s" xmlns="http://eovlastenja.fina.hr/RoAuthUnionApi/v2">
                  <Sesija_Id>%s</Sesija_Id>
                  <PersonOIB>%s</PersonOIB>
                </NavigationDataRequest>
                """.formatted(id, sesijaId, personOib);
        return new EOvlastenjaRequest(id, xml);
    }

    private static void require(String value, Pattern pattern, String name) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("neispravan " + name + " za e-Ovlaštenja");
        }
    }
}
