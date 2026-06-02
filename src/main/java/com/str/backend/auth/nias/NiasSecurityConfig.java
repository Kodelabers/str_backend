package com.str.backend.auth.nias;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "nias.saml.enabled", havingValue = "true")
@EnableConfigurationProperties(NiasSamlProperties.class)
public class NiasSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain niasSecurityFilterChain(HttpSecurity http,
                                                        RelyingPartyRegistrationRepository registrations,
                                                        NiasSamlProperties props) throws Exception {
        http
                .securityMatcher(
                        "/api/generateRegistrationNumber",
                        "/api/generateRegistrationNumber/**",
                        "/api/drafts/**",
                        "/api/nias/**",
                        "/saml2/**",
                        "/login/saml2/**",
                        "/logout/saml2/**")
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .saml2Login(saml -> saml
                        .relyingPartyRegistrationRepository(registrations)
                        .defaultSuccessUrl(props.successRedirectUrl(), true))
                .saml2Logout(logout -> {});
        return http.build();
    }
}
