package com.str.backend.address;

import java.util.Set;

/**
 * EU membership lookup by ISO 3166-1 alpha-2 code.
 *
 * <p>Used to exclude EU member states from the country list served by
 * {@code GET /api/address/countries}, which is dedicated to registration of
 * foreign nationals from <em>outside</em> the EU.
 *
 * <p>Note: HR (Hrvatska) is intentionally treated as an EU member and therefore
 * filtered out — a Croatian national is not a foreign national in this flow.
 */
public final class EuMembership {

    /** ISO 3166-1 alpha-2 codes of the 27 EU member states. */
    public static final Set<String> ISO2_CODES = Set.of(
            "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR",
            "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL",
            "PL", "PT", "RO", "SK", "SI", "ES", "SE");

    private EuMembership() {
    }

    /**
     * @return {@code true} if the given ISO 3166-1 alpha-2 code belongs to an EU
     * member state; {@code false} for {@code null} or blank input (treated as non-EU).
     */
    public static boolean isEu(String iso2) {
        if (iso2 == null || iso2.isBlank()) {
            return false;
        }
        return ISO2_CODES.contains(iso2.trim().toUpperCase());
    }
}
