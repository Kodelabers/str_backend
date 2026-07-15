package com.str.backend.auth.nias;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.web.authentication.OpenSaml4AuthenticationRequestResolver;
import org.springframework.security.saml2.provider.service.web.authentication.logout.Saml2LogoutRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.net.URI;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "nias.saml.enabled", havingValue = "true")
@EnableConfigurationProperties(NiasSamlProperties.class)
public class NiasSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain niasSecurityFilterChain(
            HttpSecurity http,
            OpenSaml4AuthenticationRequestResolver authenticationRequestResolver,
            AuthenticationSuccessHandler authenticationSuccessHandler,
            AuthenticationFailureHandler niasAuthenticationFailureHandler,
            RelyingPartyRegistrationRepository registrations,
            Saml2LogoutRequestResolver niasLogoutRequestResolver,
            NiasSamlProperties props) throws Exception {

        String logoutPath = URI.create(props.sloUrl()).getPath();

        http
                .securityMatcher(
                        "/api/generateRegistrationNumber",
                        "/api/generateRegistrationNumber/**",
                        "/api/drafts/**",
                        "/api/nias/**",
                        "/saml2/**",
                        "/login/saml2/**",
                        "/logout/saml2/**")
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error", "/login", "/saml2/**", "/login/saml2/**", "/logout/saml2/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .saml2Login(saml -> saml
                        .relyingPartyRegistrationRepository(registrations)
                        .authenticationRequestResolver(authenticationRequestResolver)
                        .successHandler(authenticationSuccessHandler)
                        .failureHandler(niasAuthenticationFailureHandler))
                .saml2Logout(logout -> logout
                        .logoutUrl(logoutPath)
                        .logoutRequest(request -> request.logoutRequestResolver(niasLogoutRequestResolver)));

        return http.build();
    }
}
