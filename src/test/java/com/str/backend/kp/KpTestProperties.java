package com.str.backend.kp;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** Potpuna i dopuštena KP konfiguracija za testove, uz prijedlog parametara iz docs/KP-INTEGRACIJA.md. */
final class KpTestProperties {

    static final int TIP_FO = 9001;
    static final int TIP_PS = 9101;

    private KpTestProperties() {
    }

    static KpProperties complete() {
        return complete("EMBEDDED", true, false, true, false, 8, false, false, 11);
    }

    static KpProperties complete(String encoding, Boolean pravniUcinak, Boolean suglasnost, Boolean potvrda,
                                 Boolean dsig, Integer rokDani, Boolean neradni, Boolean trajno, Integer godine) {
        Map<String, Integer> fo = new HashMap<>();
        Map<String, Integer> ps = new HashMap<>();
        for (var vrsta : KpProperties.IZLAZNE_VRSTE) {
            fo.put(vrsta.slug(), TIP_FO);
            ps.put(vrsta.slug(), TIP_PS);
        }
        return new KpProperties(true, "https://cistest.apis-it.hr:8451/KorisnickiPretinacServisiTest",
                null, null, "/secrets/kp.p12", "secret", null, null, null,
                "123", "456", "789", "001", encoding, "application/xml", 3,
                pravniUcinak, suglasnost, potvrda, dsig, rokDani, neradni, trajno, godine,
                fo, ps, Map.of(), Duration.ofDays(7));
    }
}
