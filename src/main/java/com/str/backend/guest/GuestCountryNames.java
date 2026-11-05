package com.str.backend.guest;

import com.str.backend.address.CountryEntity;
import com.str.backend.address.CountryRepository;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Naziv države gosta za prikaz i filtar ({@code guest.country}) iz ISO 3166-1 alpha-3 koda koji šalje
 * SDEP ({@code guest.country_code}). Naziv se uzima iz {@code str.country} (preko alpha-2, jedinog
 * koda u toj tablici), kao i ostali nazivi država u STR-u. Kad je tamo nema, hrvatski naziv daje JDK.
 * {@code N/A} (platforma ne zna državu) je „Nepoznata država".
 */
@Component
public class GuestCountryNames {

    public static final String UNKNOWN_CODE = "N/A";
    public static final String UNKNOWN_NAME = "Nepoznata država";

    private static final Locale CROATIAN = Locale.forLanguageTag("hr");
    private static final Map<String, String> ALPHA2_BY_ALPHA3 = alpha2ByAlpha3();

    private final CountryRepository countries;
    private final Map<String, String> names = new ConcurrentHashMap<>();

    public GuestCountryNames(CountryRepository countries) {
        this.countries = countries;
    }

    public String nameOf(String alpha3) {
        if (alpha3 == null || UNKNOWN_CODE.equals(alpha3)) {
            return UNKNOWN_NAME;
        }
        return names.computeIfAbsent(alpha3, this::lookup);
    }

    private String lookup(String alpha3) {
        String alpha2 = ALPHA2_BY_ALPHA3.get(alpha3);
        if (alpha2 == null) {
            return alpha3; // nepoznat kod: bolje prikazati kod nego ga izgubiti
        }
        return countries.findFirstByIso2AlphaIgnoreCaseOrderByIdAsc(alpha2)
                .map(CountryEntity::getName)
                .filter(name -> !name.isBlank())
                .orElseGet(() -> Locale.of("", alpha2).getDisplayCountry(CROATIAN));
    }

    private static Map<String, String> alpha2ByAlpha3() {
        Map<String, String> map = new ConcurrentHashMap<>();
        for (String alpha2 : Locale.getISOCountries()) {
            try {
                map.put(Locale.of("", alpha2).getISO3Country(), alpha2);
            } catch (MissingResourceException ignored) {
                // JDK nema alpha-3 za ovaj kod
            }
        }
        return map;
    }
}
