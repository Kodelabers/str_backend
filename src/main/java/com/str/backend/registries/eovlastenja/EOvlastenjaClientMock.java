package com.str.backend.registries.eovlastenja;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Zamjena za e-Ovlaštenja dok je {@code app.eovlastenja.enabled=false}: zastupanje postoji samo
 * za parove iz {@code app.eovlastenja.mock}, sve ostalo je „niste zastupnik". Namjerno ne
 * odobrava sve — lokalno se mora vidjeti i odbijeni slučaj.
 */
public class EOvlastenjaClientMock implements EOvlastenjaClient {

    private static final Logger log = LoggerFactory.getLogger(EOvlastenjaClientMock.class);

    private final List<EOvlastenjaProperties.MockRepresentation> representations;

    EOvlastenjaClientMock(List<EOvlastenjaProperties.MockRepresentation> representations) {
        this.representations = List.copyOf(representations);
        log.info("eovlastenja mock aktivan — zastupanja nisu provjerena u e-Ovlaštenjima ({} parova)",
                this.representations.size());
    }

    @Override
    public Zastupanje verifyRepresentation(String sesijaId, String personOib, String legalOib) {
        return representations.stream()
                .filter(r -> r.personOib().equals(personOib) && r.legalOib().equals(legalOib))
                .findFirst()
                .map(r -> new Zastupanje(personOib, null, null, legalOib, r.legalName(),
                        List.of(new Zastupanje.Funkcija("034", r.function() != null ? r.function() : "Direktor", "0"))))
                .orElseThrow(() -> new EOvlastenjaException(EOvlastenjaException.Reason.NOT_REPRESENTATIVE, null,
                        "e-Ovlaštenja ne potvrđuju zastupanje za traženi subjekt"));
    }
}
