package com.str.backend.auth;

import com.str.backend.auth.role.InternalUserResolver;
import com.str.backend.auth.role.MockRoleAuthenticationFilter;
import com.str.backend.auth.role.StrRoles;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    /**
     * Glavni lanac (@Order 2). Na dev/cdu je NIAS lanac (@Order 1) ispred za svoje rute
     * ({@code /api/generateRegistrationNumber(/**)}, {@code /api/drafts/**}, {@code /api/nias/**},
     * saml). Sve ostalo — uključujući SVE osjetljive/write endpointe — ide ovdje, na svim profilima.
     * Na local/mock (NIAS SAML isključen) ovaj lanac hvata i NIAS rute, pa pravila moraju biti
     * konzistentna s @Order(1) lancem.
     *
     * <p>Model uloga: {@code ROLE_INTERNAL} (puni pristup) i {@code ROLE_USER} (vlastiti RB-ovi,
     * zahtjev za novi RB, provjera valjanosti, STR dashboard agregat). Owner-scoping (USER vidi samo
     * svoje) provodi se u servisima (RN → submission → lessor po lessorId/OIB), ne na URL razini.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            InternalUserResolver roleResolver,
            @Value("${nias.saml.enabled:false}") boolean niasSamlEnabled,
            @Value("${nias.mock.fixed-oib:}") String mockFixedOib) throws Exception {

        // Local/mock: ubaci mock ulogu iz nias.mock.fixed-oib prije autorizacije.
        // OBRANA U DUBINU: mock auth je auth-bypass primitiv — smije se aktivirati ISKLJUČIVO kad
        // NIAS SAML NIJE aktivan (local/mock). Time se onemogućuje da slučajno postavljen
        // nias.mock.fixed-oib na okruženju sa stvarnim NIAS-om (dev/cdu/prod) postane bypass.
        if (!niasSamlEnabled && mockFixedOib != null && !mockFixedOib.isBlank()) {
            http.addFilterBefore(new MockRoleAuthenticationFilter(roleResolver, mockFixedOib),
                    AuthorizationFilter.class);
        }

        http
                // CSRF disabled — mock app uses CORS + SameSite cookies for protection.
                // Re-enable with CookieCsrfTokenRepository when frontend wires X-XSRF-TOKEN.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        // --- Javno (bez prijave) ---
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        // Javna provjera valjanosti RB-a (advertise-safe) — čl. 4. st. 5. STR Uredbe.
                        .requestMatchers(HttpMethod.GET, "/api/verify/**").permitAll()
                        // Ne-EU self-service registracija računa + provjera zauzetosti emaila.
                        .requestMatchers(HttpMethod.POST, "/api/registerLessor").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/lessor/email-check").permitAll()
                        // Referentni šifrarnici (adrese, tipovi, platforme) — nisu osjetljivi.
                        .requestMatchers(HttpMethod.GET, "/api/address/**", "/api/lookups/**").permitAll()
                        // SAML / infrastruktura.
                        .requestMatchers("/error", "/login", "/saml2/**",
                                "/login/saml2/**", "/logout/saml2/**").permitAll()
                        // Springdoc / Swagger UI — javna API dokumentacija (kao i dosad).
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()

                        // --- INTERNAL only (osjetljivo čitanje + sve write akcije) ---
                        // IZNIMKA prije općeg /api/rn/** pravila: akti po ZUP-u su owner-scoped, ne
                        // INTERNAL-only — stranka ima pravo na vlastite akte. Vlasništvo provjerava
                        // RnController#requireAccess (INTERNAL prolazi, USER samo svoje → 404).
                        .requestMatchers("/api/rn/*/documents/**").authenticated()
                        // Suspend/reactivate/withdraw, javni registar+detalj, admin, aktivnosti.
                        .requestMatchers("/api/rn/**").hasRole(StrRoles.INTERNAL)
                        .requestMatchers("/api/admin/**").hasRole(StrRoles.INTERNAL)
                        .requestMatchers("/api/activity/**").hasRole(StrRoles.INTERNAL)
                        // STR dashboard agregat je dozvoljen USER-u; izvozi i SDIP aktivnosti nisu.
                        .requestMatchers(HttpMethod.GET, "/api/statistics/str")
                                .hasAnyRole(StrRoles.USER, StrRoles.INTERNAL)
                        .requestMatchers("/api/statistics/**").hasRole(StrRoles.INTERNAL)

                        // --- Prijavljeni (USER ili INTERNAL); owner-scoping u servisu ---
                        .requestMatchers("/api/auth/me", "/api/auth/logout").authenticated()
                        .requestMatchers("/api/generateRegistrationNumber",
                                "/api/generateRegistrationNumberExternal",
                                "/api/generateRegistrationNumber/**").authenticated()
                        .requestMatchers("/api/nias/**").authenticated()
                        .requestMatchers("/api/lessor/profile",
                                "/api/lessor/registrations",
                                "/api/lessor/registrations/**").authenticated()
                        .requestMatchers("/api/drafts/**").authenticated()
                        .requestMatchers("/api/registration-number-prefill/**").authenticated()

                        // --- Default deny ---
                        .anyRequest().authenticated())
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable());
        return http.build();
    }
}
