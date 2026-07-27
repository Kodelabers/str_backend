package com.str.backend.rn;

import com.str.backend.auth.LessorPrincipal;
import com.str.backend.auth.SecurityConfig;
import com.str.backend.document.StrDocumentService;
import com.str.backend.document.StrDocumentType;
import com.str.backend.lessor.LessorEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Endpoint akata prolazi kroz pravi {@code SecurityConfig} lanac — za razliku od
 * {@code RnDocumentControllerTest}, koji filtere isključuje.
 *
 * <p>Akt po čl. 98. st. 2 ZUP-a nosi OIB stranke; do ovog rada je put
 * {@code /api/rn/*} /documents/** padao pod {@code anyRequest().permitAll()}, pa je svatko
 * tko zna registracijski broj mogao povući ime, adresu i OIB iznajmljivača.
 */
@ActiveProfiles("test")
@Import(SecurityConfig.class)
@WebMvcTest(value = RnController.class,
            excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
class RnDocumentAccessTest {

    private static final String RN = "HR180000123456789001";

    @Autowired MockMvc mvc;

    @MockBean RnService service;
    @MockBean RnMapper mapper;
    @MockBean StrDocumentService documentService;
    @MockBean RnDocumentsService documentsService;
    @MockBean RnRepository rnRepository;
    @MockBean UserDetailsService userDetailsService;

    @Test
    void document_unauthenticated_returns401_andRendersNothing() throws Exception {
        mvc.perform(get("/api/rn/{rn}/documents/{tip}", RN, "suspenzija"))
                .andExpect(status().isUnauthorized());

        verify(documentService, never()).render(any(), any(), any());
    }

    @Test
    void document_authenticatedOwner_returnsPdf() throws Exception {
        UUID lessorId = UUID.randomUUID();
        when(rnRepository.isOwnedByLessor(RN, lessorId)).thenReturn(true);
        when(documentService.render(eq(StrDocumentType.SUSPENZIJA), eq(RN), any()))
                .thenReturn(new byte[]{1, 2, 3});

        mvc.perform(get("/api/rn/{rn}/documents/{tip}", RN, "suspenzija")
                        .with(authentication(principalAuth(lessorId))))
                .andExpect(status().isOk());
    }

    /** Isti sigurnosni lanac štiti i popis dokumenata — bez prijave 401, bez listanja. */
    @Test
    void documentsList_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/rn/{rn}/documents", RN))
                .andExpect(status().isUnauthorized());

        verify(documentsService, never()).listForRn(any());
    }

    /** Javni pregled RB-a ostaje otvoren — zatvara se samo put do akata. */
    @Test
    void publicRnEndpoints_remainOpen() throws Exception {
        mvc.perform(get("/api/rn/inactive")).andExpect(status().isOk());
    }

    private UsernamePasswordAuthenticationToken principalAuth(UUID lessorId) {
        LessorEntity entity = mock(LessorEntity.class);
        when(entity.getLessorId()).thenReturn(lessorId);
        when(entity.getUsername()).thenReturn("ana@example.com");
        when(entity.getPasswordHash()).thenReturn("$2a$10$hash");
        LessorPrincipal principal = new LessorPrincipal(entity);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
