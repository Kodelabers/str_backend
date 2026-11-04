package com.str.backend.attachment;

import com.str.backend.auth.LessorPrincipal;
import com.str.backend.auth.SecurityConfig;
import com.str.backend.auth.internal.CountyScope;
import com.str.backend.auth.internal.CountyScopeService;
import com.str.backend.auth.internal.InternalAccessService;
import com.str.backend.auth.nias.ActingSubjectService;
import com.str.backend.auth.nias.EffectiveOibResolver;
import com.str.backend.auth.nias.NiasOibResolver;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.rn.RnAccessGuard;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RnScopeGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prilozi kroz pravi {@code SecurityConfig} lanac: preuzimanje ima pravilo akata (vlasnik ili
 * službenik u opsegu), učitavanje službenika traži rolu, a non-EU učitavanje prijavu i AJAX zaglavlje.
 */
@ActiveProfiles("test")
@Import({SecurityConfig.class, EffectiveOibResolver.class, NiasOibResolver.class, RnScopeGuard.class,
        RnAccessGuard.class})
@WebMvcTest(value = {RnPrilogController.class, LessorPrilogController.class},
            excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
class PrilogAccessTest {

    private static final String RN = "HR180000123456789001";
    private static final String OFFICIAL_OIB = "12345678903";

    @Autowired MockMvc mvc;

    @MockBean PrilogService prilogService;
    @MockBean InternalAccessService internalAccess;
    @MockBean ActingSubjectService actingSubjectService;
    @MockBean RnRepository rnRepository;
    @MockBean CountyScopeService countyScope;
    @MockBean UserDetailsService userDetailsService;

    @BeforeEach
    void wholeCountry() {
        when(countyScope.scopeFor(any())).thenReturn(CountyScope.all(CountyScope.Outcome.NOT_OFFICIAL));
        when(prilogService.zaPreuzimanje(RN, 5L))
                .thenReturn(new PrilogSadrzaj("zapisnik.pdf", "application/pdf", "%PDF".getBytes()));
    }

    // --- preuzimanje -------------------------------------------------------------------------

    @Test
    void download_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/rn/{rn}/attachments/{id}", RN, 5)).andExpect(status().isUnauthorized());

        verify(prilogService, never()).zaPreuzimanje(any(), any());
    }

    @Test
    void download_owner_returnsInlinePdf() throws Exception {
        UUID lessorId = UUID.randomUUID();
        when(rnRepository.isOwnedByLessor(RN, lessorId)).thenReturn(true);

        mvc.perform(get("/api/rn/{rn}/attachments/{id}", RN, 5).with(authentication(lessorAuth(lessorId))))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", startsWith("inline;")));
    }

    @Test
    void download_word_isAttachmentWithEncodedName() throws Exception {
        UUID lessorId = UUID.randomUUID();
        when(rnRepository.isOwnedByLessor(RN, lessorId)).thenReturn(true);
        when(prilogService.zaPreuzimanje(RN, 6L)).thenReturn(new PrilogSadrzaj("očitovanje.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", new byte[]{0x50}));

        mvc.perform(get("/api/rn/{rn}/attachments/{id}", RN, 6).with(authentication(lessorAuth(lessorId))))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", startsWith("attachment;")))
                .andExpect(header().string("Content-Disposition", containsString("UTF-8''o%C4%8Ditovanje.docx")));
    }

    @Test
    void download_foreignLessor_returns404() throws Exception {
        mvc.perform(get("/api/rn/{rn}/attachments/{id}", RN, 5).with(authentication(lessorAuth(UUID.randomUUID()))))
                .andExpect(status().isNotFound());

        verify(prilogService, never()).zaPreuzimanje(any(), any());
    }

    /** NIAS vlasnik po efektivnom OIB-u. */
    @Test
    void download_niasOwner_returns200() throws Exception {
        when(rnRepository.isOwnedByOib(RN, "98765432106")).thenReturn(true);

        mvc.perform(get("/api/rn/{rn}/attachments/{id}", RN, 5).with(authentication(nias("98765432106"))))
                .andExpect(status().isOk());
    }

    @Test
    void download_officialInScope_returns200_outOfScope_returns404() throws Exception {
        Authentication official = nias(OFFICIAL_OIB);
        when(internalAccess.hasRole(any(), eq(InternalAccessService.RN_MANAGEMENT))).thenReturn(true);

        mvc.perform(get("/api/rn/{rn}/attachments/{id}", RN, 5).with(authentication(official)))
                .andExpect(status().isOk());

        when(countyScope.scopeFor(any())).thenReturn(
                CountyScope.county("Splitsko-dalmatinska županija", 17L, 17, "Splitsko-dalmatinska županija"));
        when(rnRepository.findCountyByRn(RN)).thenReturn(Optional.of("Grad Zagreb"));

        mvc.perform(get("/api/rn/{rn}/attachments/{id}", RN, 5).with(authentication(official)))
                .andExpect(status().isNotFound());
    }

    // --- učitavanje službenika -----------------------------------------------------------------

    @Test
    void officialUpload_lessor_isForbidden() throws Exception {
        mvc.perform(multipart("/api/rn/attachments").file(pdf())
                        .with(authentication(lessorAuth(UUID.randomUUID()))).with(ajax()))
                .andExpect(status().isForbidden());

        verify(prilogService, never()).stage(any(), any());
    }

    @Test
    void officialUpload_withRole_stagesUnderInternalActor() throws Exception {
        Authentication official = nias(OFFICIAL_OIB);
        when(internalAccess.hasRole(any(), eq(InternalAccessService.RN_MANAGEMENT))).thenReturn(true);
        when(internalAccess.actorFor(any())).thenReturn(Optional.of("INTERNAL:" + OFFICIAL_OIB));
        when(prilogService.stage(eq("INTERNAL:" + OFFICIAL_OIB), any()))
                .thenReturn(new PrilogResponse(9L, "zapisnik.pdf", 4, "application/pdf"));

        mvc.perform(multipart("/api/rn/attachments").file(pdf()).with(authentication(official)).with(ajax()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.naziv").value("zapisnik.pdf"))
                .andExpect(jsonPath("$.velicina").value(4))
                .andExpect(jsonPath("$.contentType").value("application/pdf"));
    }

    /** Rola bez prepoznatog aktera (ne bi se smjelo dogoditi): 403, ne 500. */
    @Test
    void officialUpload_withoutActor_isForbidden() throws Exception {
        when(internalAccess.hasRole(any(), eq(InternalAccessService.RN_MANAGEMENT))).thenReturn(true);
        when(internalAccess.actorFor(any())).thenReturn(Optional.empty());

        mvc.perform(multipart("/api/rn/attachments").file(pdf()).with(authentication(nias(OFFICIAL_OIB))).with(ajax()))
                .andExpect(status().isForbidden());

        verify(prilogService, never()).stage(any(), any());
    }

    @Test
    void officialUpload_withoutAjaxHeader_isForbidden() throws Exception {
        when(internalAccess.hasRole(any(), eq(InternalAccessService.RN_MANAGEMENT))).thenReturn(true);

        mvc.perform(multipart("/api/rn/attachments").file(pdf()).with(authentication(nias(OFFICIAL_OIB))))
                .andExpect(status().isForbidden());

        verify(prilogService, never()).stage(any(), any());
    }

    // --- učitavanje non-EU iznajmljivača -------------------------------------------------------

    @Test
    void lessorUpload_unauthenticated_returns401() throws Exception {
        mvc.perform(multipart("/api/lessor/attachments").file(pdf()).with(ajax()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void lessorUpload_withoutAjaxHeader_isForbidden() throws Exception {
        mvc.perform(multipart("/api/lessor/attachments").file(pdf()).with(authentication(lessorAuth(UUID.randomUUID()))))
                .andExpect(status().isForbidden());

        verify(prilogService, never()).stage(any(), any());
    }

    @Test
    void lessorUpload_stagesUnderLessorId_andErrorCodeIsReturned() throws Exception {
        UUID lessorId = UUID.randomUUID();
        when(prilogService.stage(eq("LESSOR:" + lessorId), any()))
                .thenReturn(new PrilogResponse(3L, "a.pdf", 4, "application/pdf"))
                .thenThrow(new PrilogException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "error.attachment.file.type",
                        PrilogException.TYPE));

        mvc.perform(multipart("/api/lessor/attachments").file(pdf())
                        .with(authentication(lessorAuth(lessorId))).with(ajax()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(3));
        mvc.perform(multipart("/api/lessor/attachments").file(pdf())
                        .with(authentication(lessorAuth(lessorId))).with(ajax()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.details.code").value("ATTACHMENT_TYPE"));
    }

    @Test
    void lessorDelete_ownStaged_returns204_foreign_returns403() throws Exception {
        UUID lessorId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new PrilogException(HttpStatus.FORBIDDEN, "error.attachment.forbidden",
                PrilogException.FORBIDDEN)).when(prilogService).deleteStaged(anyString(), eq(8L));

        mvc.perform(delete("/api/lessor/attachments/{id}", 7).with(authentication(lessorAuth(lessorId))).with(ajax()))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/lessor/attachments/{id}", 8).with(authentication(lessorAuth(lessorId))).with(ajax()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.details.code").value("ATTACHMENT_FORBIDDEN"));

        verify(prilogService).deleteStaged("LESSOR:" + lessorId, 7L);
        verify(prilogService, never()).deleteStaged(eq("LESSOR:" + UUID.randomUUID()), anyLong());
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("datoteka", "zapisnik.pdf", "application/pdf", "%PDF".getBytes());
    }

    private static RequestPostProcessor ajax() {
        return request -> {
            request.addHeader("X-Requested-With", "XMLHttpRequest");
            return request;
        };
    }

    private static Authentication nias(String oib) {
        DefaultSaml2AuthenticatedPrincipal p = new DefaultSaml2AuthenticatedPrincipal(
                "nameid", Map.of("oib", List.<Object>of(oib)));
        return new Saml2Authentication(p, "<r/>", List.of());
    }

    private static UsernamePasswordAuthenticationToken lessorAuth(UUID lessorId) {
        LessorEntity entity = mock(LessorEntity.class);
        when(entity.getLessorId()).thenReturn(lessorId);
        when(entity.getUsername()).thenReturn("ana@example.com");
        when(entity.getPasswordHash()).thenReturn("$2a$10$hash");
        LessorPrincipal principal = new LessorPrincipal(entity);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
