package com.str.backend.sdep;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Integracijski API za SDEP pod {@code /api/sdep/v1/**}: bez sesije, bez CSRF-a i bez captche,
 * samo s kratkotrajnim tokenom koji SDEP potpisuje svojim privatnim ključem (RS256). STR ga
 * provjerava javnim ključem SDEP-a ({@code str.sdep.public-key}), pa između sustava nema
 * zajedničke tajne ni Keycloaka.
 *
 * <p>Token mora imati {@code iss=sdep}, {@code aud=turegistar}, {@code iat} i {@code exp}, i trajati
 * najviše 5 minuta. {@code jti} je {@code X-Request-ID} poziva, isti u logovima oba sustava.
 *
 * <p>Lanac je ispred NIAS lanca ({@code @Order(1)}) i glavnog lanca ({@code @Order(2)}), koji
 * ostaju nepromijenjeni. Token SDEP-a ne otvara nijednu drugu putanju.
 */
@Configuration(proxyBeanMethods = false)
public class SdepSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SdepSecurityConfig.class);

    static final String PATHS = "/api/sdep/v1/**";
    static final String ISSUER = "sdep";
    static final String AUDIENCE = "turegistar";
    static final Duration MAX_LIFETIME = Duration.ofMinutes(5);
    static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    @Bean
    @Order(0)
    public SecurityFilterChain sdepSecurityFilterChain(HttpSecurity http, JwtDecoder sdepJwtDecoder) throws Exception {
        return http
                .securityMatcher(PATHS)
                // Nema sesije ni kolačića, pa nema ni CSRF-a: preglednik ne može poslati token
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(jwt -> jwt.decoder(sdepJwtDecoder)))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .build();
    }

    /**
     * Bez postavljenog ključa STR se pokreće kao i dosad, a {@code /api/sdep/v1} svaki poziv odbija
     * s 401: okoline koje još nisu spojene na SDEP ne trebaju ništa mijenjati.
     */
    @Bean
    public JwtDecoder sdepJwtDecoder(@Value("${str.sdep.public-key:}") String key, ResourceLoader resources) {
        if (key.isBlank()) {
            log.warn("sdep_public_key_missing — str.sdep.public-key nije postavljen, /api/sdep/v1 odbija sve pozive");
            return token -> {
                throw new BadJwtException("Javni ključ SDEP-a nije postavljen (str.sdep.public-key)");
            };
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(readPublicKey(key, resources)).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(CLOCK_SKEW),
                // iss nije URL, pa Jwt#getIssuer (URL) ne smije ni u validator ni u kod
                new JwtClaimValidator<String>("iss", ISSUER::equals),
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(AUDIENCE)),
                shortLived()));
        log.info("sdep_public_key_loaded izvor={}", isLocation(key) ? key : "varijabla");
        return decoder;
    }

    /**
     * Ključ je sam u varijabli (base64 u jednom retku, s PEM zaglavljem ili bez njega), ili je to
     * lokacija datoteke ({@code file:}, {@code classpath:}), npr. u testovima.
     */
    static RSAPublicKey readPublicKey(String key, ResourceLoader resources) {
        if (!isLocation(key)) {
            String base64 = key.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s", "");
            try {
                return (RSAPublicKey) KeyFactory.getInstance("RSA")
                        .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                throw new IllegalStateException("SDEP_PUBLIC_KEY nije ispravan RSA javni ključ", e);
            }
        }
        try (InputStream in = resources.getResource(key).getInputStream()) {
            return RsaKeyConverters.x509().convert(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Ne mogu pročitati javni ključ SDEP-a: " + key, e);
        }
    }

    private static boolean isLocation(String key) {
        return key.startsWith("file:") || key.startsWith("classpath:");
    }

    /** Ispravan potpis nije dovoljan: token mora biti i kratkotrajan, da ukradeni brzo istekne. */
    private static OAuth2TokenValidator<Jwt> shortLived() {
        return jwt -> jwt.getIssuedAt() != null && jwt.getExpiresAt() != null
                && Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).compareTo(MAX_LIFETIME) <= 0
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "Token mora imati iat i exp i trajati najviše 5 minuta", null));
    }
}
