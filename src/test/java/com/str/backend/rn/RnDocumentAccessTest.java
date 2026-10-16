package com.str.backend.rn;

import com.str.backend.auth.LessorPrincipal;
import com.str.backend.auth.SecurityConfig;
import com.str.backend.auth.internal.InternalAccessService;
import com.str.backend.auth.nias.ActingSubjectService;
import com.str.backend.auth.nias.EffectiveOibResolver;
import com.str.backend.auth.nias.NiasOibResolver;
import com.str.backend.document.StrDocumentService;
import com.str.backend.document.StrDocumentType;
import com.str.backend.lessor.LessorEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
@Import({SecurityConfig.class, EffectiveOibResolver.class, NiasOibResolver.class})
@WebMvcTest(value = RnController.class,
            excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
class RnDocumentAccessTest {

    /** Bez odabrane pravne osobe (mock vraća prazno) — efektivni OIB je OIB osobe. */
    @MockBean ActingSubjectService actingSubjectService;

    /** Bez stubiranja nitko nema rolu — pojedini testovi je daju službeniku. */
    @MockBean InternalAccessService internalAccess;

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

    @Test
    void document_unauthenticated_returns401_andRendersNothing() throws Exception {
        mvc.perform(get("/api/rn/{rn}/documents/{tip}", RN, "suspenzija"))
                .andExpect(status().isUnauthorized());

        verify(documentService, never()).render(any(), any(), any());
    }

    @Test
    void document_authenticatedOwner_returnsDodjela() throws Exception {
        UUID lessorId = UUID.randomUUID();
        when(rnRepository.isOwnedByLessor(RN, lessorId)).thenReturn(true);
        when(documentService.render(eq(StrDocumentType.DODJELA), eq(RN), any(), any()))
                .thenReturn(new byte[]{1, 2, 3});

        mvc.perform(get("/api/rn/{rn}/documents/{tip}", RN, "dodjela")
                        .with(authentication(principalAuth(lessorId))))
                .andExpect(status().isOk());
    }

    /**
     * Vlasnik ne smije renderirati akt životnog ciklusa na zahtjev — „Obavijest o povlačenju"
     * aktivnog RB-a s razlogom po želji bila bi krivotvoreni službeni akt. Dobiva pohranjene.
     */
    @Test
    void document_owner_cannotRenderLifecycleAct() throws Exception {
        UUID lessorId = UUID.randomUUID();
        when(rnRepository.isOwnedByLessor(RN, lessorId)).thenReturn(true);

        mvc.perform(get("/api/rn/{rn}/documents/{tip}", RN, "povlacenje").param("reason", "izmišljeno")
                        .with(authentication(principalAuth(lessorId))))
                .andExpect(status().isNotFound());

        verify(documentService, never()).render(any(), any(), any());
    }

    /** Vlasnikov {@code reason} se ignorira i na dodjeli. */
    @Test
    void document_owner_reasonIsIgnored() throws Exception {
        UUID lessorId = UUID.randomUUID();
        when(rnRepository.isOwnedByLessor(RN, lessorId)).thenReturn(true);

        mvc.perform(get("/api/rn/{rn}/documents/{tip}", RN, "dodjela").param("reason", "izmišljeno")
                        .with(authentication(principalAuth(lessorId))))
                .andExpect(status().isOk());

        verify(documentService).render(eq(StrDocumentType.DODJELA), eq(RN), isNull(), any());
    }

    /** Isti sigurnosni lanac štiti i popis dokumenata — bez prijave 401, bez listanja. */
    @Test
    void documentsList_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/rn/{rn}/documents", RN))
                .andExpect(status().isUnauthorized());

        verify(documentsService, never()).listForRn(any());
    }

    /** Pregled i životni ciklus RB-a su back-office: bez prijave 401. */
    @Test
    void backOfficeRnEndpoints_unauthenticated_return401() throws Exception {
        mvc.perform(get("/api/rn/inactive")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/rn")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/rn/{rn}/withdraw", RN)).andExpect(status().isUnauthorized());

        verify(service, never()).withdraw(any(), any(), any());
    }

    /**
     * Iznajmljivač je prijavljen, ali nema rolu — 403 na povlačenje i suspenziju, ni za vlastiti
     * RB. Opoziv vlasnika ide kroz /api/nias/registrations i /api/lessor/registrations.
     */
    @Test
    void backOfficeRnEndpoints_lessor_returns403() throws Exception {
        UUID lessorId = UUID.randomUUID();
        when(rnRepository.isOwnedByLessor(RN, lessorId)).thenReturn(true);

        mvc.perform(post("/api/rn/{rn}/withdraw", RN).with(authentication(principalAuth(lessorId))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/rn/{rn}/suspend", RN).param("reason", "INSPECTION")
                        .with(authentication(principalAuth(lessorId))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/rn").with(authentication(principalAuth(lessorId))))
                .andExpect(status().isForbidden());

        verify(service, never()).withdraw(any(), any(), any());
        verify(service, never()).suspend(any(), any(), any(), any(), any());
    }

    @Test
    void detail_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/rn/{rn}/detail", RN)).andExpect(status().isUnauthorized());

        verify(service, never()).detail(any());
    }

    @Test
    void detail_owner_returns200() throws Exception {
        UUID lessorId = UUID.randomUUID();
        when(rnRepository.isOwnedByLessor(RN, lessorId)).thenReturn(true);

        mvc.perform(get("/api/rn/{rn}/detail", RN).with(authentication(principalAuth(lessorId))))
                .andExpect(status().isOk());

        verify(service).detail(RN);
    }

    @Test
    void detail_foreignRn_returns404() throws Exception {
        UUID lessorId = UUID.randomUUID();
        when(rnRepository.isOwnedByLessor(RN, lessorId)).thenReturn(false);

        mvc.perform(get("/api/rn/{rn}/detail", RN).with(authentication(principalAuth(lessorId))))
                .andExpect(status().isNotFound());

        verify(service, never()).detail(any());
    }

    /** Lokalni mock NIAS-a nema prijave — rolu daje mock OIB, pa službenik prolazi i bez nje. */
    @Test
    void detail_officialWithRole_withoutAuthentication_returns200() throws Exception {
        when(internalAccess.hasRole(any(), eq(InternalAccessService.RN_MANAGEMENT))).thenReturn(true);

        mvc.perform(get("/api/rn/{rn}/detail", RN)).andExpect(status().isOk());

        verify(service).detail(RN);
    }

    @Test
    void backOfficeRnEndpoints_withRole_pass() throws Exception {
        when(internalAccess.hasRole(any(), eq(InternalAccessService.RN_MANAGEMENT))).thenReturn(true);

        when(internalAccess.actorFor(any())).thenReturn(java.util.Optional.of("INTERNAL:" + OFFICIAL_OIB));

        mvc.perform(post("/api/rn/{rn}/withdraw", RN).with(authentication(official())).with(ajax()))
                .andExpect(status().isOk());

        verify(service).withdraw(RN, null, "INTERNAL:" + OFFICIAL_OIB);
    }

    /** CSRF: bez X-Requested-With mutirajuća radnja ne prolazi ni službeniku s rolom. */
    @Test
    void backOfficeRnEndpoints_withoutAjaxHeader_returns403() throws Exception {
        when(internalAccess.hasRole(any(), eq(InternalAccessService.RN_MANAGEMENT))).thenReturn(true);

        mvc.perform(post("/api/rn/{rn}/withdraw", RN).with(authentication(official())))
                .andExpect(status().isForbidden());

        verify(service, never()).withdraw(any(), any(), any());
    }

    /**
     * Službenik (NIAS, s rolom) vidi i renderira akte RB-a koji nije njegov. Pravi SAML principal:
     * s TestingAuthenticationTokenom test ne bi razlikovao rolu od ostalih grana.
     */
    @Test
    void document_niasOfficial_rendersAnyActForForeignRn() throws Exception {
        Authentication official = nias(OFFICIAL_OIB);
        when(rnRepository.isOwnedByOib(RN, OFFICIAL_OIB)).thenReturn(false);
        when(internalAccess.hasRole(official, InternalAccessService.RN_MANAGEMENT)).thenReturn(true);

        mvc.perform(get("/api/rn/{rn}/documents/{tip}", RN, "suspenzija").param("reason", "nalaz nadzora")
                        .with(authentication(official)))
                .andExpect(status().isOk());

        verify(documentService).render(StrDocumentType.SUSPENZIJA, RN, "nalaz nadzora");
    }

    @Test
    void detail_niasLessor_foreignRn_returns404() throws Exception {
        Authentication lessor = nias(LESSOR_OIB);
        when(rnRepository.isOwnedByOib(RN, LESSOR_OIB)).thenReturn(false);

        mvc.perform(get("/api/rn/{rn}/detail", RN).with(authentication(lessor)))
                .andExpect(status().isNotFound());

        verify(service, never()).detail(any());
    }

    @Test
    void detail_niasLessor_ownRn_returns200_withoutRoleLookup() throws Exception {
        Authentication lessor = nias(LESSOR_OIB);
        when(rnRepository.isOwnedByOib(RN, LESSOR_OIB)).thenReturn(true);

        mvc.perform(get("/api/rn/{rn}/detail", RN).with(authentication(lessor)))
                .andExpect(status().isOk());

        verify(internalAccess, never()).hasRole(any(), any());
    }

    /** Nedostupna shema str ne smije vlasniku srušiti vlastiti RB. */
    @Test
    void detail_niasLessor_ownRn_survivesRoleLookupFailure() throws Exception {
        Authentication lessor = nias(LESSOR_OIB);
        when(rnRepository.isOwnedByOib(RN, LESSOR_OIB)).thenReturn(true);
        when(internalAccess.hasRole(any(), any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("str nedostupan"));

        mvc.perform(get("/api/rn/{rn}/detail", RN).with(authentication(lessor)))
                .andExpect(status().isOk());
    }

    @Test
    void detail_niasOfficial_foreignRn_returns200() throws Exception {
        Authentication official = nias(OFFICIAL_OIB);
        when(rnRepository.isOwnedByOib(RN, OFFICIAL_OIB)).thenReturn(false);
        when(internalAccess.hasRole(official, InternalAccessService.RN_MANAGEMENT)).thenReturn(true);

        mvc.perform(get("/api/rn/{rn}/detail", RN).with(authentication(official)))
                .andExpect(status().isOk());
    }

    /** Prijava koja nije ni vlasnik ni službenik više ne prolazi (prije: zadano dopušteno). */
    @Test
    void detail_unknownAuthentication_withoutRole_returns404() throws Exception {
        mvc.perform(get("/api/rn/{rn}/detail", RN).with(authentication(official())))
                .andExpect(status().isNotFound());

        verify(service, never()).detail(any());
    }

    private static final String OFFICIAL_OIB = "12345678903";
    private static final String LESSOR_OIB = "77777777778";

    private static Authentication nias(String oib) {
        DefaultSaml2AuthenticatedPrincipal p = new DefaultSaml2AuthenticatedPrincipal(
                "nameid", Map.of("oib", List.<Object>of(oib)));
        return new Saml2Authentication(p, "<r/>", List.of());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor ajax() {
        return request -> {
            request.addHeader("X-Requested-With", "XMLHttpRequest");
            return request;
        };
    }

    private static TestingAuthenticationToken official() {
        TestingAuthenticationToken auth = new TestingAuthenticationToken("sluzbenik", null);
        auth.setAuthenticated(true);
        return auth;
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
