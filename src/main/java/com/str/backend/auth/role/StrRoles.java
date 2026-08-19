package com.str.backend.auth.role;

/**
 * Kanonske uloge STR aplikacije.
 *
 * <ul>
 *   <li>{@code INTERNAL} — interni službenik (str.application_user.internal = true); puna prava.</li>
 *   <li>{@code USER} — obični građanin (NIAS) ili ne-EU iznajmljivač (username/password);
 *       vlastiti RB-ovi, zahtjev za novi RB, provjera valjanosti, STR dashboard.</li>
 * </ul>
 *
 * <p>INSPEKTOR namjerno još ne postoji — uloga nije definirana u {@code str} shemi.
 * Kad se doda, uvede se treća konstanta i „read-only" pravila u {@code SecurityConfig}.
 */
public final class StrRoles {

    private StrRoles() {}

    /** Naziv uloge bez {@code ROLE_} prefiksa — za {@code hasRole(...)}. */
    public static final String INTERNAL = "INTERNAL";
    public static final String USER = "USER";

    /** Puni authority naziv s {@code ROLE_} prefiksom — za {@code GrantedAuthority}. */
    public static final String ROLE_INTERNAL = "ROLE_INTERNAL";
    public static final String ROLE_USER = "ROLE_USER";
}
