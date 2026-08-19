package com.str.backend.statistics;

import com.str.backend.auth.SecurityConfig;
import com.str.backend.auth.role.InternalUserResolver;
import com.str.backend.auth.role.StrRoles;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * STR dashboard agregat ({@code GET /api/statistics/str}) je dozvoljen USER-u; izvozi i SDIP
 * aktivnosti platformi (PII) su INTERNAL-only.
 */
@ActiveProfiles("test")
@Import(SecurityConfig.class)
@WebMvcTest(value = StatisticsController.class,
            excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
class StatisticsControllerSecurityTest {

    @Autowired MockMvc mvc;

    @MockBean StatisticsService service;
    @MockBean PlatformActivityQuery platformActivityQuery;
    @MockBean StatisticsExportService exportService;
    @MockBean UserDetailsService userDetailsService;
    @MockBean InternalUserResolver internalUserResolver;

    private static Authentication auth(String role) {
        return new UsernamePasswordAuthenticationToken("u", null, List.of(new SimpleGrantedAuthority(role)));
    }

    @Test
    void strDashboard_asUser_isAllowed() throws Exception {
        mvc.perform(get("/api/statistics/str").with(authentication(auth(StrRoles.ROLE_USER))))
                .andExpect(status().isOk());
    }

    @Test
    void strDashboard_unauthenticated_returns_401() throws Exception {
        mvc.perform(get("/api/statistics/str"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void strExport_asUser_returns_403() throws Exception {
        mvc.perform(get("/api/statistics/str/export/pdf").with(authentication(auth(StrRoles.ROLE_USER))))
                .andExpect(status().isForbidden());
    }

    @Test
    void platformActivities_asUser_returns_403() throws Exception {
        mvc.perform(get("/api/statistics/platform-activities").with(authentication(auth(StrRoles.ROLE_USER))))
                .andExpect(status().isForbidden());
    }

    @Test
    void platformActivities_asInternal_isAllowed() throws Exception {
        mvc.perform(get("/api/statistics/platform-activities").with(authentication(auth(StrRoles.ROLE_INTERNAL))))
                .andExpect(status().isOk());
    }
}
