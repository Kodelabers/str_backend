package com.str.backend.lessor;

import com.str.backend.address.CountryRepository;
import com.str.backend.auth.LessorPrincipal;
import com.str.backend.captcha.AltchaService;
import com.str.backend.categorization.CategorizationDecisionRequest;
import com.str.backend.categorization.CategorizationDecisionResponse;
import com.str.backend.categorization.CategorizationDecisionService;
import com.str.backend.categorization.CategorizationDecisionStatus;
import com.str.backend.exception.ConflictException;
import com.str.backend.registration.ReissueService;
import com.str.backend.registration.RnOwner;
import com.str.backend.rn.RnRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Naknadna predaja rješenja o kategorizaciji non-EU iznajmljivača: vlasnik je {@code lessorId} iz
 * {@link LessorPrincipal}, RB je u putanji. Filteri su isključeni, pa se prijava predaje kroz
 * {@code .principal()}.
 */
@ActiveProfiles("test")
@WebMvcTest(LessorController.class)
@AutoConfigureMockMvc(addFilters = false)
class LessorControllerCategorizationDecisionTest {

    private static final String RN = "HR120001000000000123";
    private static final String URL = "/api/lessor/registrations/{rn}/categorization-decision";
    private static final UUID LESSOR_ID = UUID.fromString("0f6c2c9e-6a43-4d8f-9b0d-6a1f3b1b7c55");

    @Autowired MockMvc mvc;

    @MockBean LessorRegistrationService registrationService;
    @MockBean LessorRepository lessorRepository;
    @MockBean LessorDocumentRepository lessorDocumentRepository;
    @MockBean CountryRepository countryRepository;
    @MockBean RnRepository rnRepository;
    @MockBean LessorRnActionService rnActionService;
    @MockBean AltchaService altchaService;
    @MockBean ReissueService reissueService;
    @MockBean CategorizationDecisionService categorizationDecisionService;

    @Test
    void upload_ownLessor_rnFromPath() throws Exception {
        UUID decisionId = UUID.randomUUID();
        when(categorizationDecisionService.upload(eq(RnOwner.byLessor(LESSOR_ID)), any()))
                .thenReturn(new CategorizationDecisionResponse(decisionId, RN,
                        CategorizationDecisionStatus.SUBMITTED, "rjesenje.pdf", 12, Instant.now()));

        // RB iz tijela ne vrijedi — mjerodavna je putanja.
        mvc.perform(multipart(URL, RN).file(pdf()).param("registrationNumber", "HR999")
                        .principal(lessorAuth()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.decisionId").value(decisionId.toString()))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        ArgumentCaptor<CategorizationDecisionRequest> req = ArgumentCaptor.forClass(CategorizationDecisionRequest.class);
        verify(categorizationDecisionService).upload(eq(RnOwner.byLessor(LESSOR_ID)), req.capture());
        assertThat(req.getValue().getRegistrationNumber()).isEqualTo(RN);
        assertThat(req.getValue().getDatoteka().getOriginalFilename()).isEqualTo("rjesenje.pdf");
    }

    @Test
    void upload_alreadySubmitted_is409WithCode() throws Exception {
        when(categorizationDecisionService.upload(any(), any())).thenThrow(new ConflictException(
                "error.categorization.alreadySubmitted", "CATEGORIZATION_ALREADY_SUBMITTED"));

        mvc.perform(multipart(URL, RN).file(pdf()).principal(lessorAuth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.code").value("CATEGORIZATION_ALREADY_SUBMITTED"));
    }

    @Test
    void upload_withoutLessorPrincipal_is401() throws Exception {
        mvc.perform(multipart(URL, RN).file(pdf()))
                .andExpect(status().isUnauthorized());
        verify(categorizationDecisionService, never()).upload(any(), any());
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("datoteka", "rjesenje.pdf", "application/pdf",
                "%PDF-1.7\n...".getBytes(StandardCharsets.US_ASCII));
    }

    private static Authentication lessorAuth() {
        LessorEntity entity = mock(LessorEntity.class);
        when(entity.getLessorId()).thenReturn(LESSOR_ID);
        when(entity.getUsername()).thenReturn("john@example.com");
        LessorPrincipal principal = new LessorPrincipal(entity);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
