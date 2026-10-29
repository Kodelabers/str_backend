package com.str.backend.rn;

import com.str.backend.auth.LessorPrincipal;
import com.str.backend.auth.SecurityConfig;
import com.str.backend.auth.internal.CountyScope;
import com.str.backend.auth.internal.CountyScopeService;
import com.str.backend.auth.internal.InternalAccessService;
import com.str.backend.auth.nias.ActingSubjectService;
import com.str.backend.auth.nias.EffectiveOibResolver;
import com.str.backend.auth.nias.NiasOibResolver;
import com.str.backend.document.StrDocumentService;
import com.str.backend.document.StrDocumentType;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.domain.RnStatus;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.rn.dto.RnDetailDto;
import com.str.backend.rn.dto.RnDetailExtras;
import com.str.backend.rn.dto.RnDetailResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
@Import({SecurityConfig.class, EffectiveOibResolver.class, NiasOibResolver.class, RnScopeGuard.class})
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
    @MockBean RnRegistrySearch registrySearch;
    /** Opseg službenika; pravi {@link RnScopeGuard} ga čita. Zadano cijela RH. */
    @MockBean CountyScopeService countyScope;

    private static final CountyScope SPLIT =
            CountyScope.county("Splitsko-dalmatinska županija", 17L, 17, "Splitsko-dalmatinska županija");

    @BeforeEach
    void wholeCountry() {
        when(countyScope.scopeFor(any())).thenReturn(CountyScope.all(CountyScope.Outcome.NOT_OFFICIAL));
    }

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
        // Izvoz nosi imena i adrese svih iznajmljivača, uz filtre stupaca ili bez njih.
        mvc.perform(get("/api/rn/export/csv").param("verified", "true").param("decision", "NONE")
                        .with(authentication(principalAuth(lessorId))))
                .andExpect(status().isForbidden());

        verify(service, never()).withdraw(any(), any(), any());
        verify(service, never()).suspend(any(), any(), any(), any(), any());
        verifyNoInteractions(registrySearch, exportService);
    }

    @Test
    void detail_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/rn/{rn}/detail", RN)).andExpect(status().isUnauthorized());

        verify(service, never()).detailView(any());
    }

    @Test
    void detail_owner_returns200() throws Exception {
        UUID lessorId = UUID.randomUUID();
        when(rnRepository.isOwnedByLessor(RN, lessorId)).thenReturn(true);

        mvc.perform(get("/api/rn/{rn}/detail", RN).with(authentication(principalAuth(lessorId))))
                .andExpect(status().isOk());

        verify(service).detailView(RN);
    }

    /** FE čita sva polja s vrha objekta — i postojeća i nova iz koraka 3, 4 i 5 obrasca. */
    @Test
    void detail_owner_returnsFlatJson() throws Exception {
        UUID lessorId = UUID.randomUUID();
        when(rnRepository.isOwnedByLessor(RN, lessorId)).thenReturn(true);
        RnDetailDto detail = new RnDetailDto(RN, RnStatus.ACTIVE, null, null, null, null, null, null, null,
                null, null, null, null, null, null, "Apartman Ana", null, 4, null,
                lessorId, "Ana", "Anić", null, "ana@example.com", LESSOR_OIB,
                false, null, null, null,
                null, null, null, null, null);
        RnDetailExtras extras = new RnDetailExtras(Offering.PART, OfferType.SECONDARY_RESIDENCE,
                "Ana Anić", "01234567", "0911234567",
                "Ilica", "1", "Zagreb", "Grad Zagreb", "Hrvatska");
        when(service.detailView(RN)).thenReturn(RnDetailResponse.of(detail, extras));

        mvc.perform(get("/api/rn/{rn}/detail", RN).with(authentication(principalAuth(lessorId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rn").value(RN))
                .andExpect(jsonPath("$.lessorOib").value(LESSOR_OIB))
                .andExpect(jsonPath("$.accommodationName").value("Apartman Ana"))
                .andExpect(jsonPath("$.offering").value("PART"))
                .andExpect(jsonPath("$.offerType").value("SECONDARY_RESIDENCE"))
                .andExpect(jsonPath("$.lessorContactName").value("Ana Anić"))
                .andExpect(jsonPath("$.lessorPhone").value("01234567"))
                .andExpect(jsonPath("$.lessorMobile").value("0911234567"))
                .andExpect(jsonPath("$.lessorStreet").value("Ilica"))
                .andExpect(jsonPath("$.lessorCountryOfResidenceName").value("Hrvatska"))
                .andExpect(jsonPath("$.lessorAddressKind").value("RESIDENCE"))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.extras").doesNotExist());
    }

    @Test
    void detail_foreignRn_returns404() throws Exception {
        UUID lessorId = UUID.randomUUID();
        when(rnRepository.isOwnedByLessor(RN, lessorId)).thenReturn(false);

        mvc.perform(get("/api/rn/{rn}/detail", RN).with(authentication(principalAuth(lessorId))))
                .andExpect(status().isNotFound());

        verify(service, never()).detailView(any());
    }

    /** Lokalni mock NIAS-a nema prijave — rolu daje mock OIB, pa službenik prolazi i bez nje. */
    @Test
    void detail_officialWithRole_withoutAuthentication_returns200() throws Exception {
        when(internalAccess.hasRole(any(), eq(InternalAccessService.RN_MANAGEMENT))).thenReturn(true);

        mvc.perform(get("/api/rn/{rn}/detail", RN)).andExpect(status().isOk());

        verify(service).detailView(RN);
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

        verify(service, never()).detailView(any());
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

    /** Županijski službenik: RB iz druge županije je nepostojeći, i na detalju i u radnji. */
    @Test
    void countyOfficial_rnFromOtherCounty_returns404() throws Exception {
        Authentication official = nias(OFFICIAL_OIB);
        when(internalAccess.hasRole(any(), eq(InternalAccessService.RN_MANAGEMENT))).thenReturn(true);
        when(countyScope.scopeFor(any())).thenReturn(SPLIT);
        when(rnRepository.findCountyByRn(RN)).thenReturn(Optional.of("Grad Zagreb"));

        mvc.perform(get("/api/rn/{rn}/detail", RN).with(authentication(official)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/rn/{rn}/suspend", RN).param("reason", "INSPECTION")
                        .with(authentication(official)).with(ajax()))
                .andExpect(status().isNotFound());

        verify(service, never()).detailView(any());
        verify(service, never()).suspend(any(), any(), any(), any(), any());
    }

    /** Ista županija zapisana drugačije (bez „županija") i dalje je u opsegu. */
    @Test
    void countyOfficial_rnFromOwnCounty_returns200() throws Exception {
        Authentication official = nias(OFFICIAL_OIB);
        when(internalAccess.hasRole(any(), eq(InternalAccessService.RN_MANAGEMENT))).thenReturn(true);
        when(internalAccess.actorFor(any())).thenReturn(Optional.of("INTERNAL:" + OFFICIAL_OIB));
        when(countyScope.scopeFor(any())).thenReturn(SPLIT);
        when(rnRepository.findCountyByRn(RN)).thenReturn(Optional.of("Splitsko-dalmatinska"));

        mvc.perform(get("/api/rn/{rn}/detail", RN).with(authentication(official)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/rn/{rn}/suspend", RN).param("reason", "INSPECTION")
                        .with(authentication(official)).with(ajax()))
                .andExpect(status().isOk());

        verify(service).detailView(RN);
        verify(service).suspend(eq(RN), eq(com.str.backend.domain.RnTrigger.INSPECTION), isNull(), isNull(),
                eq("INTERNAL:" + OFFICIAL_OIB));
    }

    /** Vlasnik vidi svoj RB bez obzira na opseg — opseg ograničava samo službenika. */
    @Test
    void owner_isNotLimitedByCountyScope() throws Exception {
        Authentication lessor = nias(LESSOR_OIB);
        when(rnRepository.isOwnedByOib(RN, LESSOR_OIB)).thenReturn(true);
        when(countyScope.scopeFor(any())).thenReturn(SPLIT);
        when(rnRepository.findCountyByRn(RN)).thenReturn(Optional.of("Grad Zagreb"));

        mvc.perform(get("/api/rn/{rn}/detail", RN).with(authentication(lessor)))
                .andExpect(status().isOk());
    }

    /**
     * Izvozi registra županijskog službenika: filtar nosi nazive njegove županije iz
     * {@link CountyScopeService}, a {@code county} iz zahtjeva ne ide u filtar — inače bi
     * „Grad Zagreb" uz opseg Splita dao prazan izvoz. Zahtjev ide u servis, koji ga ograničenom
     * službeniku zanemaruje.
     */
    @Test
    void countyOfficial_registryExports_carryScopeCounties_andIgnoreRequestCounty() throws Exception {
        List<String> splitNames = List.of("Splitsko-dalmatinska županija", "Splitsko-dalmatinska");
        when(internalAccess.hasRole(any(), eq(InternalAccessService.RN_MANAGEMENT))).thenReturn(true);
        when(countyScope.scopeFor(any())).thenReturn(SPLIT);
        when(countyScope.accommodationCounties(any(), eq("Grad Zagreb"))).thenReturn(splitNames);
        when(exportService.generateRegistryXlsx(any())).thenReturn(new byte[]{1});
        when(exportService.generateRegistryCsv(any())).thenReturn(new byte[]{1});
        when(exportService.generateRegistryPdf(any())).thenReturn(new byte[]{1});

        for (String format : List.of("xlsx", "csv", "pdf")) {
            mvc.perform(get("/api/rn/export/{format}", format)
                            .param("county", "Grad Zagreb").param("municipality", "Split")
                            .with(authentication(official())))
                    .andExpect(status().isOk());
        }

        ArgumentCaptor<RnRegistryFilter> xlsx = ArgumentCaptor.forClass(RnRegistryFilter.class);
        ArgumentCaptor<RnRegistryFilter> csv = ArgumentCaptor.forClass(RnRegistryFilter.class);
        ArgumentCaptor<RnRegistryFilter> pdf = ArgumentCaptor.forClass(RnRegistryFilter.class);
        verify(exportService).generateRegistryXlsx(xlsx.capture());
        verify(exportService).generateRegistryCsv(csv.capture());
        verify(exportService).generateRegistryPdf(pdf.capture());
        for (RnRegistryFilter filter : List.of(xlsx.getValue(), csv.getValue(), pdf.getValue())) {
            assertThat(filter.scopeCounties()).isEqualTo(splitNames);
            assertThat(filter.county()).isNull();
            // Ostali filtri iz zahtjeva ostaju.
            assertThat(filter.municipality()).isEqualTo("Split");
        }
    }

    /**
     * Bez ograničenja (cijela RH) tražena županija dolazi kao popis njezinih zapisanih oblika iz
     * {@link CountyScopeService}; bez tražene županije popisa nema (cijela RH).
     */
    @Test
    void wholeCountryOfficial_registryExport_requestCountyGoesThroughScopeService() throws Exception {
        List<String> zagrebNames = List.of("Grad Zagreb", "GRAD ZAGREB");
        when(internalAccess.hasRole(any(), eq(InternalAccessService.RN_MANAGEMENT))).thenReturn(true);
        when(countyScope.accommodationCounties(any(), eq("Grad Zagreb"))).thenReturn(zagrebNames);
        // Nestubirani mock za List vraća praznu listu, a ne null (= cijela RH).
        when(countyScope.accommodationCounties(any(), isNull())).thenReturn(null);
        when(exportService.generateRegistryCsv(any())).thenReturn(new byte[]{1});

        mvc.perform(get("/api/rn/export/csv").param("county", "Grad Zagreb")
                        .with(authentication(official())))
                .andExpect(status().isOk());
        mvc.perform(get("/api/rn/export/csv").with(authentication(official())))
                .andExpect(status().isOk());

        ArgumentCaptor<RnRegistryFilter> captor = ArgumentCaptor.forClass(RnRegistryFilter.class);
        verify(exportService, times(2)).generateRegistryCsv(captor.capture());
        assertThat(captor.getAllValues().get(0).scopeCounties()).isEqualTo(zagrebNames);
        assertThat(captor.getAllValues().get(0).county()).isNull();
        assertThat(captor.getAllValues().get(1).scopeCounties()).isNull();
    }

    /** Prijava koja nije ni vlasnik ni službenik više ne prolazi (prije: zadano dopušteno). */
    @Test
    void detail_unknownAuthentication_withoutRole_returns404() throws Exception {
        mvc.perform(get("/api/rn/{rn}/detail", RN).with(authentication(official())))
                .andExpect(status().isNotFound());

        verify(service, never()).detailView(any());
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
