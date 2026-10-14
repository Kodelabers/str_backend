package com.str.backend.auth.nias;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

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
        String requestStore,

        /**
         * Od koje starosti {@code AuthnInstant} (u odnosu na {@code IssueInstant} asercije) prijavu
         * smatramo preuzetom iz postojeće NIAS SSO sjednice — npr. osoba se prijavila u tuStart i
         * gumbom prešla k nama. Tada traka ne otvara sama odabir subjekta: osoba ga je već prošla
         * na tuStartu. Nepostavljeno ili prazno = {@value #DEFAULT_SSO_REUSE_THRESHOLD_SECONDS} s; broj
         * bez jedinice su sekunde (ne Springove milisekunde); {@code 0} ili negativno isključuje
         * prepoznavanje, pa svaka prijava otvara odabir kao prije. Vidi {@code NiasSamlConfig#reusedNiasSession}.
         */
        @DurationUnit(ChronoUnit.SECONDS)
        Duration ssoReuseThreshold
) {

    static final long DEFAULT_SSO_REUSE_THRESHOLD_SECONDS = 60;

    public NiasSamlProperties {
        ssoReuseThreshold = ssoReuseThreshold == null
                ? Duration.ofSeconds(DEFAULT_SSO_REUSE_THRESHOLD_SECONDS) : ssoReuseThreshold;
    }

    /** Prazno ili nepostavljeno = Springovo zadano ponašanje (sesija). */
    public boolean useDatabaseRequestStore() {
        return "database".equalsIgnoreCase(requestStore);
    }
}
