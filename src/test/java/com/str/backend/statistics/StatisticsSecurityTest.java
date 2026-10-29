package com.str.backend.statistics;

import com.str.backend.auth.SecurityConfig;
import com.str.backend.auth.internal.CountyScope;
import com.str.backend.auth.internal.CountyScopeService;
import com.str.backend.auth.internal.InternalAccessService;
import com.str.backend.rn.RnScopeGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static com.str.backend.auth.internal.InternalAccessService.RN_MANAGEMENT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** STR dashboard i aktivnosti na platformama samo uz rolu {@code RN_MANAGEMENT}. */
@ActiveProfiles("test")
@Import(SecurityConfig.class)
@WebMvcTest(value = StatisticsController.class,
            excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
class StatisticsSecurityTest {

    @Autowired MockMvc mvc;

    @MockBean InternalAccessService internalAccess;
    @MockBean StatisticsService service;
    @MockBean PlatformActivityQuery platformActivityQuery;
    @MockBean StatisticsExportService exportService;
    @MockBean UserDetailsService userDetailsService;
    @MockBean CountyScopeService countyScope;
    @MockBean RnScopeGuard scopeGuard;

    private static TestingAuthenticationToken signedIn() {
        TestingAuthenticationToken auth = new TestingAuthenticationToken("korisnik", null);
        auth.setAuthenticated(true);
        return auth;
    }

    @Test
    void str_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/statistics/str")).andExpect(status().isUnauthorized());

        verify(service, never()).str(any(), any(), any());
    }

    @Test
    void str_withoutRole_returns403() throws Exception {
        mvc.perform(get("/api/statistics/str").with(authentication(signedIn())))
                .andExpect(status().isForbidden());

        verify(service, never()).str(any(), any(), any());
    }

    @Test
    void str_withRole_returns200() throws Exception {
        when(internalAccess.hasRole(any(), eq(RN_MANAGEMENT))).thenReturn(true);

        mvc.perform(get("/api/statistics/str").with(authentication(signedIn())))
                .andExpect(status().isOk());
    }

    @Test
    void platformActivities_withoutRole_returns403() throws Exception {
        mvc.perform(get("/api/statistics/platform-activities").with(authentication(signedIn())))
                .andExpect(status().isForbidden());
    }

    // --- Opseg službenika (županija): kontroler mora proslijediti opseg iz sesije ---

    private static final CountyScope SPLIT =
            CountyScope.county("Splitsko-dalmatinska županija", 2195100017L, 17, "Splitsko-dalmatinska županija");
    private static final List<String> SPLIT_NAMES =
            List.of("Splitsko-dalmatinska županija", "SPLITSKO-DALMATINSKA ŽUPANIJA");

    private void countyOfficial() {
        when(internalAccess.hasRole(any(), eq(RN_MANAGEMENT))).thenReturn(true);
        when(countyScope.scopeFor(any())).thenReturn(SPLIT);
        when(countyScope.accommodationCounties(any())).thenReturn(SPLIT_NAMES);
    }

    @Test
    void str_countyOfficial_passesRestrictedScopeToService() throws Exception {
        countyOfficial();

        mvc.perform(get("/api/statistics/str").param("from", "2026-01-01").param("to", "2026-06-30")
                        .with(authentication(signedIn())))
                .andExpect(status().isOk());

        verify(service).str(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), SPLIT);
    }

    @Test
    void strPdf_countyOfficial_passesRestrictedScopeToExport() throws Exception {
        countyOfficial();
        when(exportService.generateStrPdf(any())).thenReturn(new byte[]{1});

        mvc.perform(get("/api/statistics/str/export/pdf").with(authentication(signedIn())))
                .andExpect(status().isOk());

        verify(exportService).generateStrPdf(SPLIT);
    }

    @Test
    void strDetailCsvAndXlsx_countyOfficial_passScopeCountiesToExport() throws Exception {
        countyOfficial();
        when(exportService.generateStrCsv(any())).thenReturn(new byte[]{1});
        when(exportService.generateStrXlsx(any())).thenReturn(new byte[]{1});

        mvc.perform(get("/api/statistics/str/detail/csv").with(authentication(signedIn())))
                .andExpect(status().isOk());
        mvc.perform(get("/api/statistics/str/detail/xlsx").with(authentication(signedIn())))
                .andExpect(status().isOk());

        verify(exportService).generateStrCsv(SPLIT_NAMES);
        verify(exportService).generateStrXlsx(SPLIT_NAMES);
    }
}
