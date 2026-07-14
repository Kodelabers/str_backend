package com.str.backend.auth.nias;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class NiasOibExtractorTest {

    private static Saml2Authentication samlAuth(String... namesAndValues) {
        Map<String, List<Object>> attrs = new HashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            attrs.put(namesAndValues[i], List.<Object>of(namesAndValues[i + 1]));
        }
        DefaultSaml2AuthenticatedPrincipal principal =
                new DefaultSaml2AuthenticatedPrincipal("persistent-nameid", attrs);
        return new Saml2Authentication(principal, "<saml2p:Response/>", List.of());
    }

    @Test
    void extractIdentity_readsAllAttributes() {
        Optional<NiasIdentity> id = NiasOibExtractor.extractIdentity(samlAuth(
                "oib", "12345678901",
                "ime", "Ana",
                "prezime", "Anić",
                "rola", "NAJMODAVAC",
                "email", "ana@example.hr"));

        assertThat(id).isPresent();
        assertThat(id.get().oib()).isEqualTo("12345678901");
        assertThat(id.get().firstName()).isEqualTo("Ana");
        assertThat(id.get().lastName()).isEqualTo("Anić");
        assertThat(id.get().role()).isEqualTo("NAJMODAVAC");
        assertThat(id.get().email()).isEqualTo("ana@example.hr");
    }

    @Test
    void extractIdentity_missingOptionalAttributes_areNull_butOibPresent() {
        Optional<NiasIdentity> id = NiasOibExtractor.extractIdentity(samlAuth("oib", "12345678901"));

        assertThat(id).isPresent();
        assertThat(id.get().oib()).isEqualTo("12345678901");
        assertThat(id.get().firstName()).isNull();
        assertThat(id.get().lastName()).isNull();
        assertThat(id.get().role()).isNull();
    }

    @Test
    void extractIdentity_noOib_isEmpty() {
        assertThat(NiasOibExtractor.extractIdentity(samlAuth("ime", "Ana"))).isEmpty();
    }

    @Test
    void extractIdentity_nonSamlAuthentication_isEmpty() {
        Authentication auth = new UsernamePasswordAuthenticationToken("user", "pw");
        assertThat(NiasOibExtractor.extractIdentity(auth)).isEmpty();
        assertThat(NiasOibExtractor.extractOib(auth)).isEmpty();
    }

    @Test
    void extractOib_stillWorks() {
        assertThat(NiasOibExtractor.extractOib(samlAuth("oib", "12345678901"))).contains("12345678901");
    }
}
