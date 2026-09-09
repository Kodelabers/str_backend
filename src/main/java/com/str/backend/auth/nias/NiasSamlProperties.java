package com.str.backend.auth.nias;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("nias.saml")
public record NiasSamlProperties(
        boolean enabled,
        String entityId,
        String metadataUri,
        String acsUrl,
        String sloUrl,
        String keystorePath,
        String keystorePassword,
        String keyAlias,
        String successRedirectUrl,
        String failureRedirectUrl,
        /**
         * Kamo preusmjeriti korisnika nakon što zaprimimo NIAS LogoutResponse (spec korak 11→12).
         * Bez ovoga Spring vodi na default /login?logout — ruta koja u SPA-u ne postoji.
         */
        String logoutRedirectUrl,

        /**
         * Gdje se čuva odlazni AuthnRequest do povratka s NIAS-a: {@code session} (Springov
         * default) ili {@code database}.
         *
         * <p>{@code session} radi samo ako sesijski cookie preživi NIAS-ov CROSS-SITE POST na naš
         * ACS — što traži {@code SameSite=None; Secure}, dakle HTTPS. Na okolini bez HTTPS-a
         * prijava pada s {@code invalid_in_response_to}; tamo ide {@code database}, koje zahtjev
         * traži po {@code InResponseTo} i cookie mu uopće ne treba. Vidi
         * {@link DatabaseSaml2AuthenticationRequestRepository}.
         */
        String requestStore
) {

    /** Prazno ili nepostavljeno = Springovo zadano ponašanje (sesija). */
    public boolean useDatabaseRequestStore() {
        return "database".equalsIgnoreCase(requestStore);
    }
}
