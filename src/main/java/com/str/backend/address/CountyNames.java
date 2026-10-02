package com.str.backend.address;

import java.util.Locale;

/**
 * Usporedivi oblik naziva županije.
 *
 * <p>Isti podatak stiže iz tri izvora koji ga ne pišu jednako: adresni registar na dev/CDU kaže
 * „Splitsko-dalmatinska županija", lokalni seed „Splitsko-dalmatinska", a eTurizam ponekad
 * velikim slovima. Bez ovoga bi postojeći objekt dobio lažnu razliku adrese (400) ili kod
 * županije 00 u registracijskom broju.
 */
public final class CountyNames {

    private CountyNames() {
    }

    /** Mala slova, sažete bjeline, bez završnog „ županija"; {@code null} za prazno. */
    public static String key(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return name.trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT)
                .replaceFirst(" županija$", "");
    }
}
