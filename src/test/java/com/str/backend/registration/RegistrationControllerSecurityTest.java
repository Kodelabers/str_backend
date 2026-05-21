package com.str.backend.registration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.str.backend.auth.LessorPrincipal;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.registration.dto.RegistrationExternalRequest;
import com.str.backend.registration.dto.RegistrationResponse;
import com.str.backend.auth.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@Import(SecurityConfig.class)
@WebMvcTest(value = RegistrationController.class,
            excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
class RegistrationControllerSecurityTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    @MockBean RegistrationService service;
    // ensures SecurityConfig.authenticationManager() gets a concrete bean and our filter chain loads
    @MockBean UserDetailsService userDetailsService;

    @Test
    void external_unauthenticated_returns_401() throws Exception {
        mvc.perform(post("/api/generateRegistrationNumberExternal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(validExternalRequest())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void external_authenticated_returns_201() throws Exception {
        UUID lessorId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        when(service.generateRegistrationNumberExternal(any(), eq(lessorId)))
                .thenReturn(new RegistrationResponse("HR120002000000000001", submissionId));

        mvc.perform(post("/api/generateRegistrationNumberExternal")
                        .with(authentication(principalAuth(lessorId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(validExternalRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.registrationNumber").value("HR120002000000000001"))
                .andExpect(jsonPath("$.submissionId").value(submissionId.toString()));
    }

    @Test
    void external_authenticated_invalid_payload_returns_400() throws Exception {
        RegistrationExternalRequest invalid = new RegistrationExternalRequest(
                "", null, 2L, "Split", null, "Ulica", "14a", null,
                0, 6, OfferType.RESIDENCE, Offering.WHOLE,
                false, null, false, true, null, null, null, null, null);

        mvc.perform(post("/api/generateRegistrationNumberExternal")
                        .with(authentication(principalAuth(UUID.randomUUID())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(invalid)))
                .andExpect(status().isBadRequest());
    }

    private RegistrationExternalRequest validExternalRequest() {
        return new RegistrationExternalRequest(
                "Apartman More", null,
                2L, "Split", null,
                "Ulica kralja Tomislava", "14a", null,
                4, 6,
                OfferType.RESIDENCE, Offering.WHOLE,
                false, null, false, true,
                null, null, null, null, null);
    }

    private UsernamePasswordAuthenticationToken principalAuth(UUID lessorId) {
        LessorEntity mockEntity = mock(LessorEntity.class);
        when(mockEntity.getLessorId()).thenReturn(lessorId);
        when(mockEntity.getUsername()).thenReturn("test@example.com");
        when(mockEntity.getPasswordHash()).thenReturn("$2a$10$hash");
        LessorPrincipal principal = new LessorPrincipal(mockEntity);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
