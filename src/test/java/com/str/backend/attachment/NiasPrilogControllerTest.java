package com.str.backend.attachment;

import com.str.backend.auth.LessorPrincipal;
import com.str.backend.auth.nias.ActingSubjectChangedException;
import com.str.backend.auth.nias.ActingSubjectGuard;
import com.str.backend.auth.nias.EffectiveOibResolver;
import com.str.backend.exception.GlobalExceptionHandler;
import com.str.backend.lessor.LessorEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** NIAS staging: učitavatelj je efektivni vlasnik (tvrtka kad osoba djeluje u njezino ime). */
class NiasPrilogControllerTest {

    private static final String COMPANY_OIB = "11111111117";

    private final PrilogService service = mock(PrilogService.class);
    private final EffectiveOibResolver effectiveOibResolver = mock(EffectiveOibResolver.class);
    private final ActingSubjectGuard guard = mock(ActingSubjectGuard.class);
    private final TestingAuthenticationToken person = new TestingAuthenticationToken("ana", null);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new NiasPrilogController(service, effectiveOibResolver, guard))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
    }

    @Test
    void upload_actingForCompany_stagesUnderCompanyOib() throws Exception {
        when(effectiveOibResolver.resolve(any())).thenReturn(Optional.of(COMPANY_OIB));
        when(service.stage(eq("NIAS:" + COMPANY_OIB), any()))
                .thenReturn(new PrilogResponse(4L, "tlocrt.pdf", 4, "application/pdf"));

        mvc.perform(multipart("/api/nias/attachments").file(pdf()).principal(person)
                        .header(ActingSubjectGuard.HEADER, COMPANY_OIB))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(4))
                .andExpect(jsonPath("$.naziv").value("tlocrt.pdf"));

        verify(guard).requireUnchanged(person, COMPANY_OIB);
    }

    /** Forma pripremljena za drugog subjekta od onog u sesiji: 409, ništa se ne sprema. */
    @Test
    void upload_actingSubjectChanged_is409() throws Exception {
        doThrow(new ActingSubjectChangedException(COMPANY_OIB)).when(guard).requireUnchanged(any(), eq("SELF"));

        mvc.perform(multipart("/api/nias/attachments").file(pdf()).principal(person)
                        .header(ActingSubjectGuard.HEADER, "SELF"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.code").value("ACTING_SUBJECT_CHANGED"));

        verifyNoInteractions(service);
    }

    @Test
    void upload_withoutNiasIdentity_is401() throws Exception {
        when(effectiveOibResolver.resolve(any())).thenReturn(Optional.empty());

        mvc.perform(multipart("/api/nias/attachments").file(pdf()).principal(person))
                .andExpect(unauthorized());

        verifyNoInteractions(service);
    }

    /** Non-EU sesija prolazi NIAS lanac, ali učitava kroz /api/lessor/attachments. */
    @Test
    void upload_lessorSession_is401() throws Exception {
        LessorEntity entity = mock(LessorEntity.class);
        when(entity.getLessorId()).thenReturn(UUID.randomUUID());
        when(entity.getUsername()).thenReturn("john@example.com");
        when(entity.getPasswordHash()).thenReturn("$2a$10$hash");
        LessorPrincipal principal = new LessorPrincipal(entity);

        mvc.perform(multipart("/api/nias/attachments").file(pdf())
                        .principal(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())))
                .andExpect(unauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void upload_errorsCarryDetailsCode() throws Exception {
        when(effectiveOibResolver.resolve(any())).thenReturn(Optional.of(COMPANY_OIB));
        when(service.stage(any(), any())).thenThrow(new PrilogException(HttpStatus.CONFLICT,
                "error.attachment.stagedLimit", PrilogException.STAGED_LIMIT));

        mvc.perform(multipart("/api/nias/attachments").file(pdf()).principal(person))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.code").value("ATTACHMENT_STAGED_LIMIT"));
    }

    @Test
    void delete_ownStaged_is204() throws Exception {
        when(effectiveOibResolver.resolve(any())).thenReturn(Optional.of(COMPANY_OIB));

        mvc.perform(delete("/api/nias/attachments/{id}", 4).principal(person))
                .andExpect(status().isNoContent());

        verify(service).deleteStaged("NIAS:" + COMPANY_OIB, 4L);
    }

    /**
     * U aplikaciji Spring {@code ResponseStatusException} pretvara u njezin status (v.
     * {@code PrilogAccessTest#officialUpload_withoutActor_isForbidden}); standalone MockMvc je propušta do
     * generičkog rukovatelja, pa se ovdje provjerava sama iznimka.
     */
    private static org.springframework.test.web.servlet.ResultMatcher unauthorized() {
        return result -> org.assertj.core.api.Assertions.assertThat(result.getResolvedException())
                .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.getStatusCode())
                                .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("datoteka", "tlocrt.pdf", "application/pdf", "%PDF".getBytes());
    }
}
