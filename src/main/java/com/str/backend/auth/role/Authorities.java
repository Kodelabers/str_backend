package com.str.backend.auth.role;

import org.springframework.security.core.Authentication;

/** Pomoćne provjere ovlasti nad {@link Authentication}. */
public final class Authorities {

    private Authorities() {}

    /** True ako je {@code authentication} prijavljen i nosi zadani authority (npr. {@code ROLE_INTERNAL}). */
    public static boolean hasAuthority(Authentication authentication, String authority) {
        return authentication != null
                && authentication.getAuthorities() != null
                && authentication.getAuthorities().stream()
                        .anyMatch(a -> authority.equals(a.getAuthority()));
    }

    /** True ako je korisnik interni službenik. */
    public static boolean isInternal(Authentication authentication) {
        return hasAuthority(authentication, StrRoles.ROLE_INTERNAL);
    }
}
