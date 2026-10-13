package com.str.backend.address;

/**
 * What a country is chosen for in the non-EU registration flow. The two fields follow
 * different rules (see {@link NonEuCountryPolicy}).
 *
 * <p>Exposed as the {@code namjena} query parameter of {@code GET /api/address/countries};
 * the default is {@link #PREBIVALISTE}, so callers that do not pass it are unaffected.
 */
public enum CountryPurpose {

    /**
     * Country of residence ({@code zemljaPrebivalistaId}): EU/EFTA states are excluded,
     * except EU states without eIDAS ({@link EuEftaMembership#EU_WITHOUT_EIDAS}).
     */
    PREBIVALISTE,

    /**
     * Legal entity's country of seat ({@code drzavaSjedistaId}): every EU/EFTA state is
     * excluded, with no eIDAS exception — the exception is about how a natural person logs
     * in, which says nothing about where a company is registered.
     */
    SJEDISTE
}
