package com.str.backend.auth.nias;

import com.str.backend.address.CountryEntity;
import com.str.backend.address.CountryRepository;
import com.str.backend.auth.SessionIdentityResolver;
import com.str.backend.categorization.CategorizationDecisionResponse;
import com.str.backend.categorization.CategorizationDecisionService;
import com.str.backend.categorization.CategorizationDecisionStatus;
import com.str.backend.config.ApplicationConfig;
import com.str.backend.domain.RnStatus;
import com.str.backend.egop.ChangeRequestFilingService;
import com.str.backend.exception.ConflictException;
import com.str.backend.lessor.LegalEntityProfile;
import com.str.backend.lessor.LessorDocumentRepository;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.lessor.LessorRnActionService;
import com.str.backend.lessor.LessorRnSummaryDto;
import com.str.backend.lessor.SubjectDataSource;
import com.str.backend.lessor.SubjectProfile;
import com.str.backend.lessor.SubjectProfileService;
import com.str.backend.objection.SuspensionObjectionService;
import com.str.backend.registration.ReissueService;
import com.str.backend.registration.RnOwner;
import com.str.backend.registration.dto.ReissuePrefillResponse;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.exception.GoneException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.registries.eovlastenja.EOvlastenjaException;
import com.str.backend.registries.eovlastenja.ZastupanaTvrtka;
import com.str.backend.rn.RnRepository;
import com.str.backend.str.RnFacilityVerification;
import com.str.backend.str.StrSubjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ugovor dvaju endpointa NIAS dashboarda. Filteri su isključeni, pa se identitet vrti oko
 * {@link NiasOibResolver} — kad on ne razriješi OIB, oba puta moraju vratiti 401 i ne dirati servis.
 */
@ActiveProfiles("test")
@Import({EffectiveOibResolver.class, ActingSubjectGuard.class, SubjectContactResolver.class, ApplicationConfig.class})
@WebMvcTest(NiasController.class)
@AutoConfigureMockMvc(addFilters = false)
class NiasFacilityControllerTest {

    private static final String OIB = "99999999990";
    private static final String DECISION_RN = "HR120001000000000123";

    @Autowired MockMvc mvc;

    /** Bez odabrane pravne osobe (mock vraća prazno) — efektivni OIB je OIB osobe. */
    @MockBean ActingSubjectService actingSubjectService;

    @MockBean NiasOibResolver oibResolver;
    @MockBean NiasFacilityService facilityService;
    @MockBean CategorizationDecisionService categorizationDecisionService;
    @MockBean SuspensionObjectionService suspensionObjectionService;
    @MockBean RnRepository rnRepository;
    @MockBean LessorRepository lessorRepository;
    @MockBean LessorDocumentRepository lessorDocumentRepository;
    @MockBean CountryRepository countryRepository;
    @MockBean LessorRnActionService rnActionService;
    @MockBean SessionIdentityResolver identityResolver;
    @MockBean SubjectProfileService subjectProfileService;
    @MockBean NavigationBarService navigationBarService;
    @MockBean ChangeRequestFilingService changeRequestFilingService;
    @MockBean StrSubjectRepository strSubjectRepository;
    @MockBean RnFacilityVerification rnFacilityVerification;
    @MockBean ReissueService reissueService;

    // ── FINA navigacijska traka ─────────────────────────────────────────────

    @Test
    void navigationBar_returnsScriptUrl() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(navigationBarService.scriptUrl(any(), any())).thenReturn(Optional.of("https://eusluge-nav-test.gov.hr/e_gradani.aspx?x=1"));

        mvc.perform(get("/api/nias/navigation-bar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scriptUrl").value("https://eusluge-nav-test.gov.hr/e_gradani.aspx?x=1"));
    }

    @Test
    void navigationBar_unavailable_is204() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(navigationBarService.scriptUrl(any(), any())).thenReturn(Optional.empty());

        mvc.perform(get("/api/nias/navigation-bar")).andExpect(status().isNoContent());
    }

    @Test
    void navigationBar_withoutLogin_is401() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.empty());

        mvc.perform(get("/api/nias/navigation-bar")).andExpect(status().isUnauthorized());
        verify(navigationBarService, never()).scriptUrl(any(), any());
    }

    @Test
    void changeEntity_alwaysRedirects() throws Exception {
        when(navigationBarService.change(any(), any(), eq(COMPANY_OIB), eq("1"), eq("12312312316"), eq("st")))
                .thenReturn(java.net.URI.create("https://str-test-eturizam.gov.hr/new-registration-number?entitySwitch=ok"));

        mvc.perform(get("/api/nias/acting-subject/change-entity")
                        .param("toLegalIps", COMPANY_OIB).param("toLegalIzvorReg", "1")
                        .param("forPersonOib", "12312312316").param("state", "st"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://str-test-eturizam.gov.hr/new-registration-number?entitySwitch=ok"));
    }

    // ── djelovanje u ime pravne osobe (e-Zastupanja) ────────────────────────

    private static final String COMPANY_OIB = "33333333360";

    private static ActingSubject companySubject() {
        return new ActingSubject(COMPANY_OIB, "TESTNA TVRTKA d.o.o.", List.of("Direktor"),
                OIB, "Test", "Korisnik", Instant.parse("2026-09-29T10:00:00Z"));
    }

    @Test
    void selectActingSubject_returnsVerifiedCompany() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(actingSubjectService.select(any(), eq(new NiasIdentity(OIB, null, null)), eq(COMPANY_OIB)))
                .thenReturn(companySubject());

        mvc.perform(post("/api/nias/acting-subject").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oib\":\"" + COMPANY_OIB + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oib").value(COMPANY_OIB))
                .andExpect(jsonPath("$.naziv").value("TESTNA TVRTKA d.o.o."))
                .andExpect(jsonPath("$.funkcije[0]").value("Direktor"))
                .andExpect(jsonPath("$.zastupnikOib").value(OIB))
                .andExpect(jsonPath("$.izvor").value("E_OVLASTENJA"));
    }

    @Test
    void selectActingSubject_notRepresentative_is403() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(actingSubjectService.select(any(), any(), any())).thenThrow(new EOvlastenjaException(
                EOvlastenjaException.Reason.NOT_REPRESENTATIVE, null, "ne"));

        mvc.perform(post("/api/nias/acting-subject").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oib\":\"" + COMPANY_OIB + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.details.code").value("EOVLASTENJA_NOT_REPRESENTATIVE"));
    }

    @Test
    void selectActingSubject_expiredNiasSession_is401() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(actingSubjectService.select(any(), any(), any())).thenThrow(new EOvlastenjaException(
                EOvlastenjaException.Reason.SESSION, "200", "istekla"));

        mvc.perform(post("/api/nias/acting-subject").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oib\":\"" + COMPANY_OIB + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.details.eovlastenjaCode").value("200"));
    }

    @Test
    void actingSubject_noneSelected_is204() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));

        mvc.perform(get("/api/nias/acting-subject")).andExpect(status().isNoContent());
    }

    /** Zastupnik vidi objekte tvrtke u čije ime djeluje, a ne svoje. */
    @Test
    void facilities_useCompanyOib_whenActingForCompany() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(actingSubjectService.current(any(), eq(OIB))).thenReturn(Optional.of(companySubject()));
        when(facilityService.list(any(), any(), any())).thenReturn(new FacilityPageResponse(List.of(), 0, 20, 0, 0));

        mvc.perform(get("/api/nias/facilities")).andExpect(status().isOk());

        verify(facilityService).list(eq(COMPANY_OIB), any(), any());
    }

    /** Stupac „Verificiran": popis nosi facilityVerified, a interni facilityId ne izlazi u odgovor. */
    @Test
    void registrations_carryFacilityVerified_withoutFacilityId() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        LessorRnSummaryDto row = new LessorRnSummaryDto("HR120001000000000123", RnStatus.ACTIVE,
                LocalDate.of(2026, 10, 7), "Apartman", "Riva", "1", "Split", "Apartman", false, null, null, "153049");
        when(rnRepository.findByLessorOib(OIB)).thenReturn(List.of(row));
        when(rnFacilityVerification.withFacilityVerified(List.of(row))).thenReturn(List.of(row.withFacilityVerified(true)));
        when(reissueService.withReissueDeadlines(any())).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(get("/api/nias/registrations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rn").value("HR120001000000000123"))
                .andExpect(jsonPath("$[0].facilityVerified").value(true))
                .andExpect(jsonPath("$[0].facilityId").doesNotExist());
    }

    // ── T15: ponovni zahtjev nakon povlačenja ───────────────────────────────

    /** Rok ponovnog zahtjeva dolazi iz ReissueService i izlazi kao datum; prazan je null. */
    @Test
    void registrations_carryPonovniZahtjevDo() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        LessorRnSummaryDto row = new LessorRnSummaryDto("HR120001000000000123", RnStatus.WITHDRAWN,
                LocalDate.of(2026, 1, 7), "Apartman", "Riva", "1", "Split", "Apartman", false, null, null, null);
        when(rnRepository.findByLessorOib(OIB)).thenReturn(List.of(row));
        when(rnFacilityVerification.withFacilityVerified(List.of(row))).thenReturn(List.of(row));
        when(reissueService.withReissueDeadlines(List.of(row)))
                .thenReturn(List.of(row.withPonovniZahtjevDo(LocalDate.of(2027, 7, 7))));

        mvc.perform(get("/api/nias/registrations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ponovniZahtjevDo").value("2027-07-07"));
    }

    @Test
    void reissuePrefill_returnsFormShape_forEffectiveOib() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        UUID decisionId = UUID.fromString("7a0c7d3e-3f7a-4b55-9a49-1b9e2f0a1c11");
        when(reissueService.prefill(eq("HR120001000000000123"), eq(RnOwner.byOib(OIB))))
                .thenReturn(new ReissuePrefillResponse("HR120001000000000123", LocalDate.of(2026, 1, 7),
                        LocalDate.of(2027, 7, 7),
                        new ReissuePrefillResponse.Objekt("Apartman Riva", 3L, "FS_APARTMAN", "Apartman", 4, 2,
                                7L, "Splitsko-dalmatinska županija", 70L, "Split", 700L, "Split",
                                7000L, "Riva", 70000L, "1", "21000", "Split", "1234/5",
                                OfferType.PRIMARY_RESIDENCE, Offering.WHOLE,
                                true, false, true, true, true, LocalDate.of(2025, 3, 1), null, false,
                                "ana@example.com", "0991234567", null, null),
                        new ReissuePrefillResponse.Rjesenje(decisionId, "rjesenje.pdf",
                                CategorizationDecisionStatus.VERIFIED)));

        mvc.perform(get("/api/nias/registrations/HR120001000000000123/ponovni-zahtjev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.povucenRb").value("HR120001000000000123"))
                .andExpect(jsonPath("$.povucenDana").value("2026-01-07"))
                .andExpect(jsonPath("$.ponovniZahtjevDo").value("2027-07-07"))
                .andExpect(jsonPath("$.objekt.naziv").value("Apartman Riva"))
                .andExpect(jsonPath("$.objekt.vrstaSifra").value("FS_APARTMAN"))
                .andExpect(jsonPath("$.objekt.kucniBrojId").value(70000))
                .andExpect(jsonPath("$.objekt.offerType").value("PRIMARY_RESIDENCE"))
                .andExpect(jsonPath("$.objekt.ponuda").value("WHOLE"))
                .andExpect(jsonPath("$.objekt.building").value(true))
                .andExpect(jsonPath("$.objekt.apartments").value(false))
                .andExpect(jsonPath("$.objekt.legalized").value(true))
                .andExpect(jsonPath("$.objekt.lessorResidence").value(true))
                .andExpect(jsonPath("$.objekt.coOwnerConsent").value(true))
                .andExpect(jsonPath("$.objekt.consentDate").value("2025-03-01"))
                .andExpect(jsonPath("$.objekt.host").value(false))
                .andExpect(jsonPath("$.rjesenje.decisionId").value(decisionId.toString()))
                .andExpect(jsonPath("$.rjesenje.nazivDatoteke").value("rjesenje.pdf"))
                .andExpect(jsonPath("$.rjesenje.status").value("VERIFIED"));
    }

    /** U ime tvrtke vlasnik je tvrtka — efektivni OIB, nikad OIB osobe. */
    @Test
    void reissuePrefill_actingForCompany_usesCompanyOib() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(actingSubjectService.current(any(), eq(OIB))).thenReturn(Optional.of(companySubject()));
        when(reissueService.prefill(any(), any())).thenThrow(new ResourceNotFoundException("error.rn.notFound"));

        mvc.perform(get("/api/nias/registrations/HR120001000000000123/ponovni-zahtjev"))
                .andExpect(status().isNotFound());

        verify(reissueService).prefill("HR120001000000000123", RnOwner.byOib(COMPANY_OIB));
    }

    @Test
    void reissuePrefill_foreignRn_is404WithCode() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(reissueService.prefill(any(), any()))
                .thenThrow(new ResourceNotFoundException("error.rn.notFound", "REISSUE_NOT_FOUND"));

        mvc.perform(get("/api/nias/registrations/HR999/ponovni-zahtjev"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details.code").value("REISSUE_NOT_FOUND"));
    }

    @Test
    void reissuePrefill_expired_is410WithCode() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(reissueService.prefill(any(), any()))
                .thenThrow(new GoneException("error.reissue.expired", "REISSUE_EXPIRED"));

        mvc.perform(get("/api/nias/registrations/HR120001000000000123/ponovni-zahtjev"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.details.code").value("REISSUE_EXPIRED"));
    }

    @Test
    void reissuePrefill_withoutLogin_is401() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.empty());

        mvc.perform(get("/api/nias/registrations/HR120001000000000123/ponovni-zahtjev"))
                .andExpect(status().isUnauthorized());
        verify(reissueService, never()).prefill(any(), any());
    }

    /** Povlačenje je nepovratno: u ime tvrtke tek nakon ponovne potvrde zastupanja, i na tvrtku. */
    @Test
    void withdraw_actingForCompany_reverifiesFirst() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(actingSubjectService.current(any(), eq(OIB))).thenReturn(Optional.of(companySubject()));
        when(actingSubjectService.reverify(any(), any(), any())).thenReturn(companySubject());

        mvc.perform(post("/api/nias/registrations/HR120001000000000123/withdraw")).andExpect(status().isOk());

        verify(actingSubjectService).reverify(any(), eq(new NiasIdentity(OIB, "Test", "Korisnik")), eq(companySubject()));
        verify(rnActionService).withdrawOwnByOib("HR120001000000000123", COMPANY_OIB, null);
    }

    @Test
    void withdraw_actingForCompany_noLongerRepresentative_withdrawsNothing() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(actingSubjectService.current(any(), eq(OIB))).thenReturn(Optional.of(companySubject()));
        when(actingSubjectService.reverify(any(), any(), any())).thenThrow(new EOvlastenjaException(
                EOvlastenjaException.Reason.NOT_REPRESENTATIVE, "400", "ne"));

        mvc.perform(post("/api/nias/registrations/HR120001000000000123/withdraw"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.details.code").value("EOVLASTENJA_NOT_REPRESENTATIVE"));

        verify(rnActionService, never()).withdrawOwnByOib(any(), any(), any());
    }

    // ── zaštita od promjene subjekta (X-Acting-Subject) ─────────────────────

    /** Povlačenje pripremljeno „u svoje ime", a sesija u međuvremenu djeluje za tvrtku: ništa se ne izvodi. */
    @Test
    void withdraw_headerSelf_whileActingForCompany_is409_withoutReverification() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(actingSubjectService.current(any(), eq(OIB))).thenReturn(Optional.of(companySubject()));

        mvc.perform(post("/api/nias/registrations/HR120001000000000123/withdraw").header("X-Acting-Subject", "SELF"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.code").value("ACTING_SUBJECT_CHANGED"))
                .andExpect(jsonPath("$.details.current").value(COMPANY_OIB));

        verify(actingSubjectService, never()).reverify(any(), any(), any());
        verify(rnActionService, never()).withdrawOwnByOib(any(), any(), any());
    }

    @Test
    void withdraw_headerCompany_whileOwnName_is409_withNullCurrent() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));

        mvc.perform(post("/api/nias/registrations/HR120001000000000123/withdraw").header("X-Acting-Subject", COMPANY_OIB))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.code").value("ACTING_SUBJECT_CHANGED"))
                .andExpect(jsonPath("$.details.current").isEmpty());

        verify(rnActionService, never()).withdrawOwnByOib(any(), any(), any());
    }

    @Test
    void withdraw_matchingHeader_proceeds() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(actingSubjectService.current(any(), eq(OIB))).thenReturn(Optional.of(companySubject()));
        when(actingSubjectService.reverify(any(), any(), any())).thenReturn(companySubject());

        mvc.perform(post("/api/nias/registrations/HR120001000000000123/withdraw").header("X-Acting-Subject", COMPANY_OIB))
                .andExpect(status().isOk());

        verify(rnActionService).withdrawOwnByOib("HR120001000000000123", COMPANY_OIB, null);
    }

    @Test
    void withdraw_malformedHeader_is400() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));

        mvc.perform(post("/api/nias/registrations/HR120001000000000123/withdraw").header("X-Acting-Subject", "tvrtka"))
                .andExpect(status().isBadRequest());

        verify(rnActionService, never()).withdrawOwnByOib(any(), any(), any());
    }

    /** Rješenje pripremljeno za tvrtku ne smije se upisati osobi nakon povratka u svoje ime (drugi prozor). */
    @Test
    void uploadDecision_headerCompany_whileOwnName_is409() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));

        mvc.perform(multipart("/api/nias/categorization-decisions")
                        .file(pdf())
                        .param("registrationNumber", DECISION_RN)
                        .header("X-Acting-Subject", COMPANY_OIB))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.code").value("ACTING_SUBJECT_CHANGED"));

        verify(categorizationDecisionService, never()).upload(any(), any());
    }

    @Test
    void actingSubjectOptions_listsCompanies() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(actingSubjectService.representedCompanies(any(), eq(new NiasIdentity(OIB, null, null))))
                .thenReturn(List.of(new ZastupanaTvrtka(COMPANY_OIB, "TESTNA TVRTKA d.o.o.")));

        mvc.perform(get("/api/nias/acting-subject/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].oib").value(COMPANY_OIB))
                .andExpect(jsonPath("$[0].naziv").value("TESTNA TVRTKA d.o.o."));
    }

    /** Usluga nema pristup metodi (šifra 100) ili FINA nedostupna: 503, izbornik ostaje na upisu OIB-a. */
    @Test
    void actingSubjectOptions_unavailable_is503() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(actingSubjectService.representedCompanies(any(), any()))
                .thenThrow(new ActingSubjectOptionsUnavailableException());

        mvc.perform(get("/api/nias/acting-subject/options"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.details.code").value("EOVLASTENJA_OPTIONS_UNAVAILABLE"))
                .andExpect(jsonPath("$.details.registry").value("EOVLASTENJA"));
    }

    /** Previše odabira: 429 s istim brojem sekundi u zaglavlju i u tijelu. */
    @Test
    void selectActingSubject_rateLimited_is429() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(actingSubjectService.select(any(), any(), any())).thenThrow(new ActingSubjectRateLimitException(42));

        mvc.perform(post("/api/nias/acting-subject").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oib\":\"" + COMPANY_OIB + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"))
                .andExpect(jsonPath("$.details.code").value("EOVLASTENJA_RATE_LIMIT"))
                .andExpect(jsonPath("$.details.retryAfterSeconds").value(42));
    }

    // ── profil (GET /api/nias/profile) ──────────────────────────────────────

    /** NIAS iznajmljivač dobiva Hrvatsku pri kreiranju — profil je prikazuje kao „Zemlju”. */
    @Test
    void profile_niasLessor_returnsCountryName() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        LessorEntity lessor = LessorEntity.create("Pero", "Perić", "Ilica", "1", "Zagreb", "Grad Zagreb", null);
        lessor.setLessorOib(OIB);
        lessor.applyCountryOfResidence(191);
        lessor.applyContact("pero@example.hr", "Pero Perić", "012345678", "0911234567");
        when(lessorRepository.findFirstByLessorOibOrderByCreatedAtDesc(OIB)).thenReturn(Optional.of(lessor));
        CountryEntity croatia = mock(CountryEntity.class);
        when(croatia.getName()).thenReturn("Hrvatska");
        when(countryRepository.findById(191L)).thenReturn(Optional.of(croatia));

        mvc.perform(get("/api/nias/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Pero"))
                .andExpect(jsonPath("$.countryName").value("Hrvatska"))
                // Telefon i kontakt osoba su samo za non-EU obrazac; u ime tvrtke bi bili tuđi.
                .andExpect(jsonPath("$.phoneNumber").doesNotExist())
                .andExpect(jsonPath("$.contactName").doesNotExist());
    }

    // ── pismeno „Zahtjev za promjenu podataka" ──────────────────────────────

    private static final String CHANGE_REQUEST =
            "/api/nias/registrations/HR120001000000000123/zahtjev-promjene-podataka";

    @Test
    void changeRequest_ownName_filesForPerson() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));

        mvc.perform(post(CHANGE_REQUEST).header("X-Acting-Subject", "SELF")).andExpect(status().isNoContent());

        verify(actingSubjectService, never()).reverify(any(), any(), any());
        verify(changeRequestFilingService).file("HR120001000000000123", OIB);
    }

    /** Mijenja spis tvrtke — u ime tvrtke tek nakon ponovne potvrde zastupanja. */
    @Test
    void changeRequest_actingForCompany_reverifiesAndFilesForCompany() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(actingSubjectService.current(any(), eq(OIB))).thenReturn(Optional.of(companySubject()));
        when(actingSubjectService.reverify(any(), any(), any())).thenReturn(companySubject());

        mvc.perform(post(CHANGE_REQUEST).header("X-Acting-Subject", COMPANY_OIB)).andExpect(status().isNoContent());

        verify(actingSubjectService).reverify(any(), any(), eq(companySubject()));
        verify(changeRequestFilingService).file("HR120001000000000123", COMPANY_OIB);
    }

    /** Obrazac popunjen u svoje ime, a sesija sada djeluje za tvrtku: pismeno ne ide u tvrtkin spis. */
    @Test
    void changeRequest_subjectChanged_is409_andFilesNothing() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(actingSubjectService.current(any(), eq(OIB))).thenReturn(Optional.of(companySubject()));

        mvc.perform(post(CHANGE_REQUEST).header("X-Acting-Subject", "SELF"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.code").value("ACTING_SUBJECT_CHANGED"));

        verify(changeRequestFilingService, never()).file(any(), any());
    }

    @Test
    void changeRequest_withoutNiasIdentity_is401() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.empty());

        mvc.perform(post(CHANGE_REQUEST)).andExpect(status().isUnauthorized());

        verify(changeRequestFilingService, never()).file(any(), any());
    }

    /** U svoje ime povlačenje ne zove e-Ovlaštenja. */
    @Test
    void withdraw_ownName_doesNotReverify() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));

        mvc.perform(post("/api/nias/registrations/HR120001000000000123/withdraw")).andExpect(status().isOk());

        verify(actingSubjectService, never()).reverify(any(), any(), any());
        verify(rnActionService).withdrawOwnByOib("HR120001000000000123", OIB, null);
    }

    /**
     * U ime tvrtke (T8): MBS i sjedište tvrtke te jedan zastupnik s adresom iz
     * {@code loadLegal}, kontakt s dokumenata tvrtke. Registar fizičke osobe ({@code load}) se ne
     * pita — zastupnik kojeg ne poznaje inače bi dobio 400 i ne bi mogao predati zahtjev za tvrtku.
     */
    @Test
    void subjectProfile_actingForCompany_returnsCompanySeatRepresentativeAndContact() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(actingSubjectService.current(any(), eq(OIB))).thenReturn(Optional.of(companySubject()));
        SubjectProfile representative = new SubjectProfile(OIB, "TEST", "KORISNIK", SubjectDataSource.OIB_REGISTAR,
                null, "SJENJAK", "19", "OSIJEK", "31000", "OSIJEK", "Osječko-baranjska", SubjectDataSource.OIB_REGISTAR);
        when(subjectProfileService.loadLegal(COMPANY_OIB, "TESTNA TVRTKA d.o.o.", OIB, "Test", "Korisnik"))
                .thenReturn(new LegalEntityProfile(COMPANY_OIB, "TESTNA TVRTKA d.o.o.", "080123456",
                        "ULICA CVIJETE ZUZORIĆ", "3", "ZAGREB", "10000", "GRAD ZAGREB", "Grad Zagreb",
                        SubjectDataSource.OIB_REGISTAR, representative));
        when(strSubjectRepository.findDocumentContactByOib(COMPANY_OIB)).thenReturn(Optional.of(contact()));

        mvc.perform(get("/api/nias/subject"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oib").value(OIB))
                .andExpect(jsonPath("$.ime").value("TEST"))
                .andExpect(jsonPath("$.imeIzvor").value("OIB_REGISTAR"))
                .andExpect(jsonPath("$.ulica").value("SJENJAK"))
                .andExpect(jsonPath("$.adresaIzvor").value("OIB_REGISTAR"))
                .andExpect(jsonPath("$.pravnaOsoba.oib").value(COMPANY_OIB))
                .andExpect(jsonPath("$.pravnaOsoba.naziv").value("TESTNA TVRTKA d.o.o."))
                .andExpect(jsonPath("$.pravnaOsoba.mbs").value("080123456"))
                .andExpect(jsonPath("$.pravnaOsoba.ulica").value("ULICA CVIJETE ZUZORIĆ"))
                .andExpect(jsonPath("$.pravnaOsoba.adresaIzvor").value("OIB_REGISTAR"))
                .andExpect(jsonPath("$.kontaktTelefon").value("012345678"))
                .andExpect(jsonPath("$.kontaktEmail").value("info@firma.hr"))
                .andExpect(jsonPath("$.kontaktIzvor").value("DOKUMENT"));

        verify(subjectProfileService, never()).load(any(), any(), any());
        verify(strSubjectRepository, never()).findDocumentContactByOib(OIB);
        // Snapshot tvrtke nosi kontakt tadašnjeg zastupnika — RB-ovi se u ime tvrtke ni ne traže.
        verify(lessorRepository, never()).findLatestRbSnapshot(any(), any(), any(), any());
    }

    private static StrSubjectRepository.DocumentContactRow contact() {
        return new StrSubjectRepository.DocumentContactRow() {
            public String getName() { return "Recepcija"; }
            public String getPhone() { return "012345678"; }
            public String getMobile() { return "0911234567"; }
            public String getEmail() { return "info@firma.hr"; }
        };
    }

    /** E-mail iz document_contact predpopunjava se i fizičkoj osobi. */
    @Test
    void subjectProfile_naturalPerson_includesContactEmail() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(subjectProfileService.load(OIB, null, null)).thenReturn(new SubjectProfile(
                OIB, "Test", "Korisnik", SubjectDataSource.STR_SUBJEKT, null,
                "Ilica", "1", "Zagreb", "10000", "Zagreb", "Grad Zagreb", SubjectDataSource.STR_SUBJEKT));
        when(strSubjectRepository.findDocumentContactByOib(OIB)).thenReturn(Optional.of(contact()));

        mvc.perform(get("/api/nias/subject"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kontaktEmail").value("info@firma.hr"))
                .andExpect(jsonPath("$.kontaktMobitel").value("0911234567"));
    }

    /**
     * Kontakt sa zadnjeg RB-a ima prednost pred dokumentom iz eTurizma i uzima se cijeli; objekt s
     * obrasca ({@code facilityId}, {@code prethodniRb}) ide u upit kao prednost RB-a istog objekta.
     */
    @Test
    void subjectProfile_contactFromLatestRb_winsOverDocument() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(subjectProfileService.load(OIB, null, null)).thenReturn(new SubjectProfile(
                OIB, "Test", "Korisnik", SubjectDataSource.STR_SUBJEKT, null,
                "Ilica", "1", "Zagreb", "10000", "Zagreb", "Grad Zagreb", SubjectDataSource.STR_SUBJEKT));
        LessorEntity snapshot = LessorEntity.create("Test", "Korisnik", "Ilica", "1", "Zagreb", "Grad Zagreb", null);
        snapshot.applyContact("rb@example.hr", null, null, "0987654321");
        when(lessorRepository.findLatestRbSnapshot(eq(OIB), eq("F-77"), eq("HR12345678"), any())).thenReturn(Optional.of(snapshot));
        when(strSubjectRepository.findDocumentContactByOib(OIB)).thenReturn(Optional.of(contact()));

        mvc.perform(get("/api/nias/subject").param("facilityId", "F-77").param("prethodniRb", "HR12345678"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kontaktIzvor").value("RB"))
                .andExpect(jsonPath("$.kontaktEmail").value("rb@example.hr"))
                .andExpect(jsonPath("$.kontaktMobitel").value("0987654321"))
                // prazno na RB-u ostaje prazno — dokument ga ne dopunjava
                .andExpect(jsonPath("$.kontaktTelefon").doesNotExist())
                .andExpect(jsonPath("$.kontaktOsoba").doesNotExist());

        verify(strSubjectRepository, never()).findDocumentContactByOib(any());
    }

    /** Stavka 2: sva polja podnositelja vidljiva, s izvorom po grupi. */
    @Test
    void returnsSubjectProfile_forSessionOib() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(subjectProfileService.load(OIB, null, null)).thenReturn(new SubjectProfile(
                OIB, "Test", "Korisnik", SubjectDataSource.STR_SUBJEKT, null,
                "Ilica", "1", "Zagreb", "10000", "Zagreb", "Grad Zagreb",
                SubjectDataSource.STR_SUBJEKT));

        mvc.perform(get("/api/nias/subject"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oib").value(OIB))
                .andExpect(jsonPath("$.ime").value("Test"))
                .andExpect(jsonPath("$.prezime").value("Korisnik"))
                .andExpect(jsonPath("$.imeIzvor").value("STR_SUBJEKT"))
                .andExpect(jsonPath("$.ulica").value("Ilica"))
                .andExpect(jsonPath("$.kucniBroj").value("1"))
                .andExpect(jsonPath("$.mjesto").value("Zagreb"))
                .andExpect(jsonPath("$.postanskiBroj").value("10000"))
                .andExpect(jsonPath("$.opcina").value("Zagreb"))
                .andExpect(jsonPath("$.zupanija").value("Grad Zagreb"))
                .andExpect(jsonPath("$.adresaIzvor").value("STR_SUBJEKT"))
                // bez zapisa u str.document_contact kontakt upisuje korisnik
                .andExpect(jsonPath("$.kontaktEmail").doesNotExist());
    }

    @Test
    void rejectsSubjectProfile_withoutNiasSession() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.empty());

        mvc.perform(get("/api/nias/subject")).andExpect(status().isUnauthorized());
        verify(subjectProfileService, never()).load(any(), any(), any());
    }

    /** Registar adresu nije vratio (ne zna osobu ili nije dostupan): 200 bez adrese, obrazac je traži upisom. */
    @Test
    void subjectProfile_withoutRegistryAddress_hasNullAddressSource() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(subjectProfileService.load(OIB, null, null)).thenReturn(new SubjectProfile(
                OIB, "Test", "Korisnik", SubjectDataSource.NIAS, null,
                null, null, null, null, null, null, null));

        mvc.perform(get("/api/nias/subject"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ime").value("Test"))
                .andExpect(jsonPath("$.ulica").doesNotExist())
                .andExpect(jsonPath("$.adresaIzvor").doesNotExist());
    }

    @Test
    void returnsFacilityPage() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(facilityService.list(eq(OIB), any(), any())).thenReturn(new FacilityPageResponse(
                List.of(new FacilityResponse("153049", "Soba 1", "FS_SOBA", "Soba", "Tri zvjezdice",
                        "Aktivan", 2, null, "Splitsko-dalmatinska", "Makarska", "Makarska",
                        "Kraljevska", "88", "21300", "Kraljevska 88", null,
                        "soba1@example.com", "021111222", FacilitySource.ETURIZAM,
                        "8a3e5c1e-0000-4000-8000-000000000001", true, null, null)),
                0, 20, 1, 1));

        mvc.perform(get("/api/nias/facilities").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value("153049"))
                .andExpect(jsonPath("$.items[0].vrstaSifra").value("FS_SOBA"))
                .andExpect(jsonPath("$.items[0].registracijskiBroj").doesNotExist())
                .andExpect(jsonPath("$.items[0].izvor").value("ETURIZAM"))
                .andExpect(jsonPath("$.items[0].objektId").value("8a3e5c1e-0000-4000-8000-000000000001"))
                .andExpect(jsonPath("$.items[0].verificiran").value(true))
                .andExpect(jsonPath("$.totalUnits").value(1));
    }

    @Test
    void rejectsFacilityList_withoutNiasSession() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.empty());

        mvc.perform(get("/api/nias/facilities")).andExpect(status().isUnauthorized());
        verify(facilityService, never()).list(any(), any(), any());
    }

    @Test
    void acceptsScannedDecision() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        UUID decisionId = UUID.randomUUID();
        when(categorizationDecisionService.upload(eq(RnOwner.byOib(OIB)), any()))
                .thenReturn(new CategorizationDecisionResponse(decisionId, DECISION_RN,
                        CategorizationDecisionStatus.SUBMITTED, "rjesenje.pdf", 12, Instant.now()));

        mvc.perform(multipart("/api/nias/categorization-decisions").file(pdf()).param("registrationNumber", DECISION_RN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.decisionId").value(decisionId.toString()))
                .andExpect(jsonPath("$.registrationNumber").value(DECISION_RN))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    void rejectsScannedDecision_withoutNiasSession() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.empty());

        mvc.perform(multipart("/api/nias/categorization-decisions").file(pdf()).param("registrationNumber", DECISION_RN))
                .andExpect(status().isUnauthorized());
        verify(categorizationDecisionService, never()).upload(any(), any());
    }

    /**
     * Datoteka iznad 10 MB: bez rukovatelja u {@code GlobalExceptionHandler} Spring bi vratio 500,
     * a frontend to ograničenje već prikazuje pa mora dobiti poruku, ne „greška na serveru".
     */
    @Test
    void rejectsScannedDecision_whenFileTooLarge() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(categorizationDecisionService.upload(eq(RnOwner.byOib(OIB)), any()))
                .thenThrow(new MaxUploadSizeExceededException(10 * 1024 * 1024));

        mvc.perform(multipart("/api/nias/categorization-decisions").file(pdf()).param("registrationNumber", DECISION_RN))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void rejectsScannedDecision_withoutFile() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));

        mvc.perform(multipart("/api/nias/categorization-decisions").param("registrationNumber", DECISION_RN))
                .andExpect(status().isBadRequest());
        verify(categorizationDecisionService, never()).upload(any(), any());
    }

    /** Rješenje se predaje samo uz RB — bez njega zahtjev ni ne dolazi do servisa. */
    @Test
    void rejectsScannedDecision_withoutRegistrationNumber() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));

        mvc.perform(multipart("/api/nias/categorization-decisions").file(pdf()))
                .andExpect(status().isBadRequest());
        verify(categorizationDecisionService, never()).upload(any(), any());
    }

    /** Sukob (npr. već predano rješenje) nosi `details.code` po kojem frontend bira poruku. */
    @Test
    void scannedDecision_conflict_is409WithCode() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(categorizationDecisionService.upload(eq(RnOwner.byOib(OIB)), any())).thenThrow(new ConflictException(
                "error.categorization.alreadySubmitted", "CATEGORIZATION_ALREADY_SUBMITTED"));

        mvc.perform(multipart("/api/nias/categorization-decisions").file(pdf()).param("registrationNumber", DECISION_RN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.code").value("CATEGORIZATION_ALREADY_SUBMITTED"));
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("datoteka", "rjesenje.pdf", "application/pdf",
                "%PDF-1.7 test".getBytes(StandardCharsets.US_ASCII));
    }
}
