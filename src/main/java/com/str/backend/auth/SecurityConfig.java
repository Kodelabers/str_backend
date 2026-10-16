package com.str.backend.auth;

import com.str.backend.auth.internal.InternalAccessService;
import com.str.backend.auth.internal.InternalRoleAuthorizationManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.Set;

import static org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    /**
     * Mutirajuće radnje pod sesijom koje traže {@link RequiredAjaxHeaderFilter#HEADER}: životni
     * ciklus RB-a i obrada zahtjeva službenika, aktivnosti platformi i opoziv iznajmljivača.
     */
    static final RequestMatcher CSRF_PROTECTED = new AndRequestMatcher(
            request -> !SAFE_METHODS.contains(request.getMethod()),
            new OrRequestMatcher(
                    antMatcher("/api/rn/**"),
                    antMatcher("/api/admin/**"),
                    antMatcher("/api/activity/**"),
                    antMatcher("/api/lessor/registrations/**")));

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

    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   InternalAccessService internalAccess) throws Exception {
        InternalRoleAuthorizationManager rnManagement =
                new InternalRoleAuthorizationManager(internalAccess, InternalAccessService.RN_MANAGEMENT);
        http
                // CSRF token je isključen (frontend i backend su na različitim originima, pa XSRF
                // cookie frontend ne može pročitati). Mutirajuće radnje umjesto toga štiti
                // RequiredAjaxHeaderFilter — SameSite to ne može, jer je na CDU/cdupreprod/mock None.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        // Detalj i akti RB-a nose osobne podatke stranke (akt po čl. 98. st. 2 ZUP-a
                        // i OIB). Vidi ih službenik s rolom i vlasnik; vlasništvo se provjerava u
                        // RnController#requireAccess. Rola se priznaje i bez prijave, jer lokalni
                        // mock NIAS-a (nias.mock.fixed-oib) nema autentikacije. Prijava se provjerava
                        // prva: prijavljeni korisnik tako ne plaća upit role u shemu str.
                        .requestMatchers("/api/rn/*/detail", "/api/rn/*/documents/**")
                        .access(AuthorizationManagers.anyOf(AuthenticatedAuthorizationManager.authenticated(), rnManagement))
                        .requestMatchers("/api/auth/me", "/api/auth/logout",
                                "/api/generateRegistrationNumberExternal",
                                "/api/lessor/profile", "/api/lessor/registrations",
                                "/api/lessor/registrations/**").authenticated()
                        // Back-office: popis i životni ciklus RB-ova, izvozi, statistike i obrada
                        // non-EU registracija i rješenja o kategorizaciji te aktivnosti platformi
                        // (pretraga, ingest, purge — ingest dok SDEP nema strojnu autentikaciju).
                        // Detalj i akti gore ostaju dostupni iznajmljivaču za vlastite RB-ove, pa
                        // moraju biti prije ovoga.
                        .requestMatchers("/api/rn/**", "/api/statistics/**", "/api/admin/**", "/api/activity/**")
                        .access(rnManagement)
                        .anyRequest().permitAll())
                // Iza autorizacije: neprijavljen i dalje dobiva 401, a bez prava 403 — zaglavlje se
                // provjerava tek zahtjevu koji bi inače prošao.
                .addFilterAfter(new RequiredAjaxHeaderFilter(CSRF_PROTECTED), AuthorizationFilter.class)
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable());
        return http.build();
    }
}
