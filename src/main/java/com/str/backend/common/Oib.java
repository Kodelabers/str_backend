package com.str.backend.common;

/**
 * Provjera OIB-a: 11 znamenki i kontrolna znamenka po ISO 7064, MOD 11,10 (Zakon o OIB-u).
 */
public final class Oib {

    private Oib() {}

    public static boolean isValid(String value) {
        if (value == null || value.length() != 11) {
            return false;
        }
        int a = 10;
        for (int i = 0; i < 10; i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            a = (a + (c - '0')) % 10;
            if (a == 0) {
                a = 10;
            }
            a = (a * 2) % 11;
        }
        char last = value.charAt(10);
        if (last < '0' || last > '9') {
            return false;
        }
        int control = (11 - a) % 10;
        return control == last - '0';
    }
}
