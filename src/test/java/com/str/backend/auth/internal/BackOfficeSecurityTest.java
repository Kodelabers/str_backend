package com.str.backend.auth.internal;

import com.str.backend.activity.AccommodationActivityController;
import com.str.backend.activity.AccommodationActivityService;
import com.str.backend.activity.ActivityMapper;
import com.str.backend.admin.AdminPendingRegistrationController;
import com.str.backend.admin.AdminPendingRegistrationService;
import com.str.backend.auth.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static com.str.backend.auth.internal.InternalAccessService.RN_MANAGEMENT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Back-office endpointi kroz pravi {@code SecurityConfig} lanac: bez prijave 401, bez role
 * {@code RN_MANAGEMENT} 403, s rolom prolaze. Isto za {@code RnController} pokriva
 * {@code RnDocumentAccessTest}, a za statistike {@code StatisticsSecurityTest}.
 */
@ActiveProfiles("test")
@Import(SecurityConfig.class)
@WebMvcTest(value = {AdminPendingRegistrationController.class, InternalAccessController.class,
                     AccommodationActivityController.class},
            excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
class BackOfficeSecurityTest {

    @Autowired MockMvc mvc;

    @MockBean InternalAccessService internalAccess;
    @MockBean AdminPendingRegistrationService pendingRegistrationService;
    @MockBean UserDetailsService userDetailsService;
    @MockBean AccommodationActivityService activityService;
    @MockBean ActivityMapper activityMapper;
    @MockBean CountyScopeService countyScope;

    @BeforeEach
    void wholeCountry() {
        when(countyScope.scopeFor(any())).thenReturn(CountyScope.all(CountyScope.Outcome.NOT_OFFICIAL));
    }

    private static TestingAuthenticationToken signedIn() {
        TestingAuthenticationToken auth = new TestingAuthenticationToken("korisnik", null);
        auth.setAuthenticated(true);
        return auth;
    }

    @Test
    void adminStats_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/admin/pending-registrations/stats")).andExpect(status().isUnauthorized());

        verify(pendingRegistrationService, never()).getStats();
    }

    @Test
    void adminStats_withoutRole_returns403() throws Exception {
        mvc.perform(get("/api/admin/pending-registrations/stats").with(authentication(signedIn())))
                .andExpect(status().isForbidden());

        verify(pendingRegistrationService, never()).getStats();
    }

    @Test
    void adminStats_withRole_returns200() throws Exception {
        when(internalAccess.hasRole(any(), eq(RN_MANAGEMENT))).thenReturn(true);

        mvc.perform(get("/api/admin/pending-registrations/stats").with(authentication(signedIn())))
                .andExpect(status().isOk());
    }

    @Test
    void roleLookupFailure_deniesAccess() throws Exception {
        when(internalAccess.hasRole(any(), eq(RN_MANAGEMENT)))
                .thenThrow(new DataAccessResourceFailureException("str nedostupan"));

        mvc.perform(get("/api/admin/pending-registrations/stats").with(authentication(signedIn())))
                .andExpect(status().isForbidden());

        verify(pendingRegistrationService, never()).getStats();
    }

    @Test
    void adminApprove_withoutRole_returns403() throws Exception {
        UUID lessorId = UUID.randomUUID();

        mvc.perform(post("/api/admin/pending-registrations/{id}/approve", lessorId).with(authentication(signedIn())))
                .andExpect(status().isForbidden());

        verify(pendingRegistrationService, never()).approve(any(), any());
    }

    @Test
    void adminApprove_withRole_recordsSessionActor() throws Exception {
        UUID lessorId = UUID.randomUUID();
        when(internalAccess.hasRole(any(), eq(RN_MANAGEMENT))).thenReturn(true);
        when(internalAccess.actorFor(any())).thenReturn(Optional.of("INTERNAL:12345678903"));

        mvc.perform(post("/api/admin/pending-registrations/{id}/approve", lessorId)
                        .with(authentication(signedIn()))
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isNoContent());

        verify(pendingRegistrationService).approve(lessorId, "INTERNAL:12345678903");
    }

    @Test
    void internalAccess_returnsOfficialRole() throws Exception {
        when(internalAccess.effectiveRole(any())).thenReturn(RN_MANAGEMENT);

        mvc.perform(get("/api/nias/internal-access"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value(RN_MANAGEMENT))
                .andExpect(jsonPath("$.countyId").doesNotExist());
    }

    /** Županijski službenik: frontend iz odgovora zaključava filtar županije i prikazuje tijelo. */
    @Test
    void internalAccess_countyOfficial_returnsCounty() throws Exception {
        when(internalAccess.effectiveRole(any())).thenReturn(RN_MANAGEMENT);
        when(countyScope.scopeFor(any())).thenReturn(CountyScope.county(
                "Splitsko-dalmatinska županija", 17L, 17, "Splitsko-dalmatinska županija"));

        mvc.perform(get("/api/nias/internal-access"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value(RN_MANAGEMENT))
                .andExpect(jsonPath("$.subjectName").value("Splitsko-dalmatinska županija"))
                .andExpect(jsonPath("$.countyId").value(17))
                .andExpect(jsonPath("$.countyName").value("Splitsko-dalmatinska županija"));
    }

    /** Opseg se ne utvrđuje za iznajmljivača — bez upita organizacije u shemi str. */
    @Test
    void internalAccess_ordinaryUser_doesNotResolveScope() throws Exception {
        when(internalAccess.effectiveRole(any())).thenReturn(InternalAccessService.USER);

        mvc.perform(get("/api/nias/internal-access"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countyName").doesNotExist());

        verify(countyScope, never()).scopeFor(any());
    }

    @Test
    void internalAccess_returnsUserRoleForOrdinaryUser() throws Exception {
        when(internalAccess.effectiveRole(any())).thenReturn(InternalAccessService.USER);

        mvc.perform(get("/api/nias/internal-access"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("USER"));
    }

    /** CSRF: forma s tuđe stranice ne može postaviti X-Requested-With. */
    @Test
    void adminApprove_withRole_withoutAjaxHeader_returns403() throws Exception {
        when(internalAccess.hasRole(any(), eq(RN_MANAGEMENT))).thenReturn(true);

        mvc.perform(post("/api/admin/pending-registrations/{id}/approve", UUID.randomUUID())
                        .with(authentication(signedIn())))
                .andExpect(status().isForbidden());

        verify(pendingRegistrationService, never()).approve(any(), any());
    }

    /** Aktivnosti platformi su funkcija role — pretraga i purge nisu javni. */
    @Test
    void activity_search_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/activity")).andExpect(status().isUnauthorized());

        verify(activityService, never()).search(any(), any(), any(), any(), any());
    }

    @Test
    void activity_purge_withoutRole_returns403() throws Exception {
        mvc.perform(delete("/api/activity/purge").with(authentication(signedIn()))
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isForbidden());

        verify(activityService, never()).purgeExpired();
    }

    @Test
    void activity_search_withRole_returns200() throws Exception {
        when(internalAccess.hasRole(any(), eq(RN_MANAGEMENT))).thenReturn(true);

        mvc.perform(get("/api/activity").with(authentication(signedIn())))
                .andExpect(status().isOk());
    }

    /** Purge briše aktivnosti svih županija — županijski službenik ga ne smije pokrenuti. */
    @Test
    void activity_purge_countyOfficial_returns403() throws Exception {
        when(internalAccess.hasRole(any(), eq(RN_MANAGEMENT))).thenReturn(true);
        when(countyScope.scopeFor(any())).thenReturn(CountyScope.county(
                "Splitsko-dalmatinska županija", 2195100017L, 17, "Splitsko-dalmatinska županija"));

        mvc.perform(delete("/api/activity/purge").with(authentication(signedIn()))
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isForbidden());

        verify(activityService, never()).purgeExpired();
    }

    @Test
    void activity_ingest_countyOfficial_returns403() throws Exception {
        when(internalAccess.hasRole(any(), eq(RN_MANAGEMENT))).thenReturn(true);
        when(countyScope.scopeFor(any())).thenReturn(CountyScope.county(
                "Splitsko-dalmatinska županija", 2195100017L, 17, "Splitsko-dalmatinska županija"));

        mvc.perform(post("/api/activity/ingest").with(authentication(signedIn()))
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platformId\":1,\"entries\":[{\"rn\":\"HR00000001\",\"periodFrom\":\"2026-09-01\",\"periodTo\":\"2026-09-30\"}]}"))
                .andExpect(status().isForbidden());

        verify(activityService, never()).ingest(any());
    }

    @Test
    void activity_purge_ministry_returns200() throws Exception {
        when(internalAccess.hasRole(any(), eq(RN_MANAGEMENT))).thenReturn(true);
        when(countyScope.scopeFor(any())).thenReturn(CountyScope.ministry("Ministarstvo turizma i sporta"));

        mvc.perform(delete("/api/activity/purge").with(authentication(signedIn()))
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isOk());

        verify(activityService).purgeExpired();
    }
}
