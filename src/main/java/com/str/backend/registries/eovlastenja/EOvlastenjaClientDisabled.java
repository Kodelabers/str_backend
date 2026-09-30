package com.str.backend.registries.eovlastenja;

import com.str.backend.exception.ExternalRegistryException;

import java.util.List;

/**
 * e-Ovlaštenja isključena na okolini s NIAS-om ({@code app.eovlastenja.enabled=false}, bez mock
 * parova): svaki zahtjev je 503, a ne „niste zastupnik" — to nitko nije provjerio.
 */
final class EOvlastenjaClientDisabled implements EOvlastenjaClient {

    @Override
    public Zastupanje verifyRepresentation(String sesijaId, String personOib, String legalOib) {
        throw unavailable();
    }

    @Override
    public List<ZastupanaTvrtka> representedCompanies(String sesijaId, String personOib) {
        throw unavailable();
    }

    private static ExternalRegistryException unavailable() {
        return new ExternalRegistryException(EOvlastenjaResponseParser.REGISTRY,
                "e-Ovlaštenja nisu uključena na ovoj okolini (app.eovlastenja.enabled=false)");
    }
}
