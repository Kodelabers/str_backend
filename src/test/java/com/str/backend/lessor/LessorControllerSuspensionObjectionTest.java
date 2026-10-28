package com.str.backend.lessor;

import com.str.backend.address.CountryRepository;
import com.str.backend.auth.LessorPrincipal;
import com.str.backend.captcha.AltchaService;
import com.str.backend.categorization.CategorizationDecisionService;
import com.str.backend.exception.ConflictException;
import com.str.backend.objection.SuspensionObjectionRequest;
import com.str.backend.objection.SuspensionObjectionResponse;
import com.str.backend.objection.SuspensionObjectionService;
import com.str.backend.objection.SuspensionObjectionStatus;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prigovor non-EU iznajmljivača: vlasnik je {@code lessorId} iz {@link LessorPrincipal}, RB je u
 * putanji. Filteri su isključeni, pa se prijava predaje kroz {@code .principal()}.
 */
@ActiveProfiles("test")
@WebMvcTest(LessorController.class)
@AutoConfigureMockMvc(addFilters = false)
class LessorControllerSuspensionObjectionTest {

    private static final String RN = "HR120001000000000123";
    private static final String URL = "/api/lessor/registrations/{rn}/suspension-objection";
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
    @MockBean SuspensionObjectionService suspensionObjectionService;

    @Test
    void submit_ownLessor_rnFromPath() throws Exception {
        UUID objectionId = UUID.randomUUID();
        when(suspensionObjectionService.submit(eq(RnOwner.byLessor(LESSOR_ID)), anyString(), any()))
                .thenReturn(new SuspensionObjectionResponse(objectionId, RN, SuspensionObjectionStatus.SUBMITTED,
                        "prigovor.docx", 12, Instant.now()));

        // RB iz tijela ne vrijedi — mjerodavna je putanja.
        mvc.perform(multipart(URL, RN).file(docx()).param("registrationNumber", "HR999")
                        .principal(lessorAuth()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.objectionId").value(objectionId.toString()))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        ArgumentCaptor<SuspensionObjectionRequest> req = ArgumentCaptor.forClass(SuspensionObjectionRequest.class);
        verify(suspensionObjectionService).submit(eq(RnOwner.byLessor(LESSOR_ID)), eq("LESSOR:" + LESSOR_ID),
                req.capture());
        assertThat(req.getValue().getRegistrationNumber()).isEqualTo(RN);
        assertThat(req.getValue().getDatoteka().getOriginalFilename()).isEqualTo("prigovor.docx");
    }

    @Test
    void submit_pending_is409WithCode() throws Exception {
        when(suspensionObjectionService.submit(any(), anyString(), any())).thenThrow(new ConflictException(
                "error.objection.alreadySubmitted", "OBJECTION_ALREADY_SUBMITTED"));

        mvc.perform(multipart(URL, RN).file(docx()).principal(lessorAuth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.code").value("OBJECTION_ALREADY_SUBMITTED"));
    }

    @Test
    void submit_withoutLessorPrincipal_is401() throws Exception {
        mvc.perform(multipart(URL, RN).file(docx()))
                .andExpect(status().isUnauthorized());
        verify(suspensionObjectionService, never()).submit(any(), any(), any());
    }

    private static MockMultipartFile docx() {
        return new MockMultipartFile("datoteka", "prigovor.docx", "application/octet-stream",
                "PK\u0003\u0004...".getBytes(StandardCharsets.ISO_8859_1));
    }

    private static Authentication lessorAuth() {
        LessorEntity entity = mock(LessorEntity.class);
        when(entity.getLessorId()).thenReturn(LESSOR_ID);
        when(entity.getUsername()).thenReturn("john@example.com");
        LessorPrincipal principal = new LessorPrincipal(entity);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
