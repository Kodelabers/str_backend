package com.str.backend.address;

import java.util.Set;

/**
 * EU / EFTA membership lookup by ISO 3166-1 alpha-2 code.
 *
 * <p>Used to exclude EU and EFTA member states from the country list served by
 * {@code GET /api/address/countries}, and from the server-side guard in
 * {@code LessorRegistrationService} — both flows are dedicated to registration of
 * foreign nationals from <em>outside</em> the EU/EFTA area.
 *
 * <p>EFTA states (IS, LI, NO, CH) are not EU members, but their nationals enjoy the
 * same freedom of establishment, so they do not belong in the non-EU flow either.
 * Hence one combined set rather than two.
 *
 * <p>Note: HR (Hrvatska) is intentionally included and therefore filtered out — a
 * Croatian national is not a foreign national in this flow.
 *
 * <p>Greece is listed under its ISO code {@code GR}, not the EU's own {@code EL}
 * variant, matching what {@code str.country.iso2_alpha} actually stores.
 */
public final class EuEftaMembership {

    /** ISO 3166-1 alpha-2 codes of the 27 EU member states plus the 4 EFTA states. */
    public static final Set<String> ISO2_CODES = Set.of(
            // EU-27
            "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR",
            "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL",
            "PL", "PT", "RO", "SK", "SI", "ES", "SE",
            // EFTA
            "IS", "LI", "NO", "CH");

    private EuEftaMembership() {
    }

    /**
     * @return {@code true} if the given ISO 3166-1 alpha-2 code belongs to an EU or EFTA
     * member state; {@code false} for {@code null} or blank input (treated as outside
     * the area).
     */
    public static boolean isEuOrEfta(String iso2) {
        if (iso2 == null || iso2.isBlank()) {
            return false;
        }
        return ISO2_CODES.contains(iso2.trim().toUpperCase());
    }
}
