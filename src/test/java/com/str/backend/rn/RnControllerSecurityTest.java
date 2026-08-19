package com.str.backend.rn;

import com.str.backend.auth.SecurityConfig;
import com.str.backend.auth.role.InternalUserResolver;
import com.str.backend.auth.role.StrRoles;
import com.str.backend.document.StrDocumentService;
import com.str.backend.statistics.StatisticsExportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Role-gating za registry/write endpointe. Sve pod {@code /api/rn/**} je INTERNAL-only:
 * suspend/reactivate/withdraw + javni registar (lista, detalj, akti). USER dobiva 403,
 * neprijavljeni 401.
 */
@ActiveProfiles("test")
@Import(SecurityConfig.class)
@WebMvcTest(value = RnController.class,
            excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
class RnControllerSecurityTest {

    private static final String RN = "HR210000000000000001";

    @Autowired MockMvc mvc;

    @MockBean RnService service;
    @MockBean RnMapper mapper;
    @MockBean StrDocumentService documentService;
    @MockBean RnDocumentsService documentsService;
    @MockBean RnRepository rnRepository;
    @MockBean StatisticsExportService exportService;
    @MockBean UserDetailsService userDetailsService;
    @MockBean InternalUserResolver internalUserResolver;

    private static Authentication auth(String role) {
        return new UsernamePasswordAuthenticationToken("u", null, List.of(new SimpleGrantedAuthority(role)));
    }

    @Test
    void suspend_unauthenticated_returns_401() throws Exception {
        mvc.perform(post("/api/rn/" + RN + "/suspend").param("reason", "INSPECTION"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void suspend_asUser_returns_403() throws Exception {
        mvc.perform(post("/api/rn/" + RN + "/suspend").param("reason", "INSPECTION")
                        .with(authentication(auth(StrRoles.ROLE_USER))))
                .andExpect(status().isForbidden());
    }

    @Test
    void suspend_asInternal_isAllowed() throws Exception {
        mvc.perform(post("/api/rn/" + RN + "/suspend").param("reason", "INSPECTION")
                        .with(authentication(auth(StrRoles.ROLE_INTERNAL))))
                .andExpect(status().isOk());
    }

    @Test
    void registryDetail_asUser_returns_403() throws Exception {
        mvc.perform(get("/api/rn/" + RN + "/detail")
                        .with(authentication(auth(StrRoles.ROLE_USER))))
                .andExpect(status().isForbidden());
    }

    @Test
    void registryList_asUser_returns_403() throws Exception {
        mvc.perform(get("/api/rn").with(authentication(auth(StrRoles.ROLE_USER))))
                .andExpect(status().isForbidden());
    }

    @Test
    void registryDetail_unauthenticated_returns_401() throws Exception {
        mvc.perform(get("/api/rn/" + RN + "/detail"))
                .andExpect(status().isUnauthorized());
    }
}
