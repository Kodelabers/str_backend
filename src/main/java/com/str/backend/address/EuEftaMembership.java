package com.str.backend.address;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * EU / EFTA membership lookup by ISO 3166-1 alpha-2 code, and the single source of truth
 * for the list of EU member states without eIDAS ({@link #EU_WITHOUT_EIDAS}).
 *
 * <p>The non-EU registration flow is for foreign nationals from <em>outside</em> the
 * EU/EFTA area, so EU and EFTA states are kept out of the country list served by
 * {@code GET /api/address/countries} and rejected by the server-side guard in
 * {@code LessorRegistrationService}. Both apply the rule through
 * {@link NonEuCountryPolicy}, which also decides <em>which</em> rule applies to which
 * field (see {@link CountryPurpose}).
 *
 * <p>EFTA states (IS, LI, NO, CH) are not EU members, but their nationals enjoy the
 * same freedom of establishment, so they do not belong in the non-EU flow either. The
 * two groups are kept as separate sets ({@link #EU_ISO2_CODES}, {@link #EFTA_ISO2_CODES})
 * because only EU members can be an eIDAS exception; {@link #ISO2_CODES} is their union
 * and is what membership ({@link #isEuOrEfta}) checks.
 *
 * <p>Exception, <b>country of residence only</b>: EU member states listed in
 * {@link #EU_WITHOUT_EIDAS} are not excluded there, because their nationals cannot log in
 * through the NIAS eIDAS node and the non-EU flow is the only way for them to register.
 * The legal entity's country of seat still excludes every EU/EFTA state. Membership itself
 * ({@link #isEuOrEfta}) is unaffected by the exception.
 *
 * <p>Note: HR (Hrvatska) is intentionally included and therefore filtered out — a
 * Croatian national is not a foreign national in this flow. HR can never be an eIDAS
 * exception (enforced in {@link #excludedFromNonEuFlow(String, Set)} and in
 * {@link #requireValidEuWithoutEidas(Set)}).
 *
 * <p>Greece is listed under its ISO code {@code GR}, not the EU's own {@code EL}
 * variant, matching what {@code str.country.iso2_alpha} actually stores.
 *
 * <p><b>When MINTS delivers the list</b> of EU states without eIDAS: fill in
 * {@link #EU_WITHOUT_EIDAS}; replace the empty-list tripwire test in
 * {@code NonEuCountryPolicyTest} with one asserting the real members; and add a new
 * Liquibase changeset (context {@code local}) for any listed state missing from the local
 * {@code str.country} seed — see the javadoc of {@link #EU_WITHOUT_EIDAS}.
 */
public final class EuEftaMembership {

    /** ISO 3166-1 alpha-2 codes of the 27 EU member states. */
    static final Set<String> EU_ISO2_CODES = Set.of(
            "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR",
            "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL",
            "PL", "PT", "RO", "SK", "SI", "ES", "SE");

    /** ISO 3166-1 alpha-2 codes of the 4 EFTA states. */
    static final Set<String> EFTA_ISO2_CODES = Set.of("IS", "LI", "NO", "CH");

    /** ISO 3166-1 alpha-2 codes of the 27 EU member states plus the 4 EFTA states. */
    public static final Set<String> ISO2_CODES = Stream.concat(
                    EU_ISO2_CODES.stream(), EFTA_ISO2_CODES.stream())
            .collect(Collectors.toUnmodifiableSet());

    /** Croatia — never a foreign country in this flow, so never an eIDAS exception. */
    private static final String CROATIA = "HR";

    /**
     * EU member states whose nationals cannot log in through the NIAS eIDAS node and
     * therefore register through the non-EU flow: they stay in the <b>country of
     * residence</b> list and pass its server-side guard. They remain excluded as a legal
     * entity's <b>country of seat</b> ({@link CountryPurpose#SJEDISTE}).
     *
     * <p>The list is supplied by the Ministry (MINTS) and has not arrived yet, so it is
     * deliberately empty — behaviour is identical to plain EU/EFTA exclusion until then.
     * Entries must be upper-case ISO 3166-1 alpha-2 codes of EU members, never EFTA and
     * never HR; {@link NonEuCountryPolicy} refuses to start otherwise.
     *
     * <p>Filling it in is not enough for the country to show up:
     * <ul>
     *   <li><b>local / mock</b>: the {@code str.country} seed
     *       ({@code 104-str-country-restore-local.xml}) has only 20 countries, so a listed
     *       state that is not among them needs a new, higher-numbered changeset (context
     *       {@code local}) — never an edit of changeset 104;</li>
     *   <li><b>dev / CDU / preprod</b>: {@code str.country} is owned upstream — check that
     *       each listed state exists there with {@code active = true} and the expected
     *       {@code iso2_alpha}, otherwise it is silently missing from the dropdown.</li>
     * </ul>
     */
    public static final Set<String> EU_WITHOUT_EIDAS = Set.of();

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
        return ISO2_CODES.contains(normalize(iso2));
    }

    /**
     * Country-of-residence rule: EU/EFTA members are excluded from the non-EU flow,
     * except EU members in {@code euWithoutEidas}. HR is always excluded, whatever the set
     * says. Called through {@link NonEuCountryPolicy}; the set is a parameter so the rule
     * is testable while {@link #EU_WITHOUT_EIDAS} is empty.
     *
     * @return {@code false} for {@code null} or blank input (treated as outside the area)
     */
    static boolean excludedFromNonEuFlow(String iso2, Set<String> euWithoutEidas) {
        if (!isEuOrEfta(iso2)) {
            return false;
        }
        String code = normalize(iso2);
        return CROATIA.equals(code) || !euWithoutEidas.contains(code);
    }

    /**
     * Fails fast on a malformed exception list: every entry must be an upper-case EU member
     * code other than HR (EFTA states and third countries are not eIDAS exceptions).
     *
     * @return an immutable copy of the list
     * @throws IllegalArgumentException naming the offending entry
     */
    static Set<String> requireValidEuWithoutEidas(Set<String> euWithoutEidas) {
        for (String code : euWithoutEidas) {
            if (code == null || !EU_ISO2_CODES.contains(code) || CROATIA.equals(code)) {
                throw new IllegalArgumentException(
                        "EU_WITHOUT_EIDAS may only contain upper-case EU member codes other than HR: " + code);
            }
        }
        return Set.copyOf(euWithoutEidas);
    }

    private static String normalize(String iso2) {
        return iso2.trim().toUpperCase(Locale.ROOT);
    }
}
