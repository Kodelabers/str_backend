package com.str.backend.rn;

import com.str.backend.auth.LessorPrincipal;
import com.str.backend.auth.SecurityConfig;
import com.str.backend.auth.role.InternalUserResolver;
import com.str.backend.auth.role.StrRoles;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
    // RnController od /api/rn/export/* endpointa ovisi i o exportu; slice ga mora imati.
    @MockBean com.str.backend.statistics.StatisticsExportService exportService;
    // SecurityConfig ga traži za mock-role filter (local/mock); u testu je neaktivan, ali bean mora postojati.
    @MockBean InternalUserResolver internalUserResolver;

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

    /**
     * Interni službenik vidi akt svake stranke — vlasništvo se za INTERNAL ne provjerava.
     * Bez te iznimke bi ga provjera po OIB-u blokirala, jer i on dolazi kroz NIAS s vlastitim
     * OIB-om koji nije vlasnik RB-a.
     */
    @Test
    void document_asInternal_returnsPdf_forSomeoneElsesRn() throws Exception {
        when(rnRepository.isOwnedByOib(eq(RN), any())).thenReturn(false);
        when(documentService.render(eq(StrDocumentType.SUSPENZIJA), eq(RN), any()))
                .thenReturn(new byte[]{1, 2, 3});

        mvc.perform(get("/api/rn/{rn}/documents/{tip}", RN, "suspenzija")
                        .with(authentication(new UsernamePasswordAuthenticationToken(
                                "sluzbenik", null,
                                java.util.List.of(new SimpleGrantedAuthority(StrRoles.ROLE_INTERNAL))))))
                .andExpect(status().isOk());
    }

    /**
     * Registar RB-a više NIJE anoniman. Uvođenjem uloga cijeli {@code /api/rn/**} (osim akata,
     * koji su owner-scoped iznad) zaključan je na INTERNAL — neprijavljeni dobiva 401, interni
     * službenik 200. Ranije je ovaj endpoint padao pod {@code anyRequest().permitAll()}.
     */
    @Test
    void registryEndpoints_requireInternalRole() throws Exception {
        mvc.perform(get("/api/rn/inactive"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/rn/inactive")
                        .with(authentication(new UsernamePasswordAuthenticationToken(
                                "sluzbenik", null,
                                java.util.List.of(new SimpleGrantedAuthority(StrRoles.ROLE_INTERNAL))))))
                .andExpect(status().isOk());
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
