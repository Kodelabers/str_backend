package com.str.backend.auth.nias;

import com.str.backend.address.CountryRepository;
import com.str.backend.auth.SessionIdentityResolver;
import com.str.backend.categorization.CategorizationDecisionResponse;
import com.str.backend.categorization.CategorizationDecisionService;
import com.str.backend.categorization.CategorizationDecisionStatus;
import com.str.backend.exception.ConflictException;
import com.str.backend.exception.ExternalRegistryException;
import com.str.backend.lessor.LessorDocumentRepository;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.lessor.LessorRnActionService;
import com.str.backend.lessor.SubjectDataSource;
import com.str.backend.lessor.SubjectProfile;
import com.str.backend.lessor.SubjectProfileService;
import com.str.backend.registries.eovlastenja.EOvlastenjaException;
import com.str.backend.registries.eovlastenja.ZastupanaTvrtka;
import com.str.backend.rn.RnRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
@Import({EffectiveOibResolver.class, ActingSubjectGuard.class})
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
    @MockBean RnRepository rnRepository;
    @MockBean LessorRepository lessorRepository;
    @MockBean LessorDocumentRepository lessorDocumentRepository;
    @MockBean CountryRepository countryRepository;
    @MockBean LessorRnActionService rnActionService;
    @MockBean SessionIdentityResolver identityResolver;
    @MockBean SubjectProfileService subjectProfileService;
    @MockBean NavigationBarService navigationBarService;

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
                .thenReturn(java.net.URI.create("https://str-test-eturizam.gov.hr/existing-objects?entitySwitch=ok"));

        mvc.perform(get("/api/nias/acting-subject/change-entity")
                        .param("toLegalIps", COMPANY_OIB).param("toLegalIzvorReg", "1")
                        .param("forPersonOib", "12312312316").param("state", "st"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://str-test-eturizam.gov.hr/existing-objects?entitySwitch=ok"));
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
        when(facilityService.list(any(), any(), any())).thenReturn(new FacilityPageResponse(List.of(), 0, 20, 0));

        mvc.perform(get("/api/nias/facilities")).andExpect(status().isOk());

        verify(facilityService).list(eq(COMPANY_OIB), any(), any());
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

    /** U svoje ime povlačenje ne zove e-Ovlaštenja. */
    @Test
    void withdraw_ownName_doesNotReverify() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));

        mvc.perform(post("/api/nias/registrations/HR120001000000000123/withdraw")).andExpect(status().isOk());

        verify(actingSubjectService, never()).reverify(any(), any(), any());
        verify(rnActionService).withdrawOwnByOib("HR120001000000000123", OIB, null);
    }

    /**
     * U ime tvrtke prebivalište zastupnika se ne traži: zastupnik kojeg registar ne poznaje inače
     * bi dobio 400 i ne bi mogao predati zahtjev za tvrtku.
     */
    @Test
    void subjectProfile_actingForCompany_skipsRegistry() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(actingSubjectService.current(any(), eq(OIB))).thenReturn(Optional.of(companySubject()));

        mvc.perform(get("/api/nias/subject"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oib").value(OIB))
                .andExpect(jsonPath("$.ime").value("Test"))
                .andExpect(jsonPath("$.imeIzvor").value("NIAS"))
                .andExpect(jsonPath("$.ulica").doesNotExist())
                .andExpect(jsonPath("$.pravnaOsoba.oib").value(COMPANY_OIB))
                .andExpect(jsonPath("$.pravnaOsoba.naziv").value("TESTNA TVRTKA d.o.o."));

        verify(subjectProfileService, never()).load(any(), any(), any());
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
                // kontakt ne vodi nijedan registar — korisnik ga upisuje, pa ga odgovor ni ne nudi
                .andExpect(jsonPath("$.kontaktEmail").doesNotExist());
    }

    @Test
    void rejectsSubjectProfile_withoutNiasSession() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.empty());

        mvc.perform(get("/api/nias/subject")).andExpect(status().isUnauthorized());
        verify(subjectProfileService, never()).load(any(), any(), any());
    }

    @Test
    void subjectProfile_isServiceUnavailable_whenRegistryDown() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(subjectProfileService.load(any(), any(), any())).thenThrow(new ExternalRegistryException("OIB", "down"));

        mvc.perform(get("/api/nias/subject"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.details.registry").value("OIB"));
    }

    @Test
    void returnsFacilityPage() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(facilityService.list(eq(OIB), any(), any())).thenReturn(new FacilityPageResponse(
                List.of(new FacilityResponse("153049", "Soba 1", "FS_SOBA", "Soba", "Tri zvjezdice",
                        "Aktivan", 2, null, "Splitsko-dalmatinska", "Makarska", "Makarska",
                        "Kraljevska", "88", "21300", "Kraljevska 88", null,
                        "soba1@example.com", "021111222", FacilitySource.ETURIZAM)),
                0, 20, 1));

        mvc.perform(get("/api/nias/facilities").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value("153049"))
                .andExpect(jsonPath("$.items[0].vrstaSifra").value("FS_SOBA"))
                .andExpect(jsonPath("$.items[0].registracijskiBroj").doesNotExist())
                .andExpect(jsonPath("$.items[0].izvor").value("ETURIZAM"));
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
        when(categorizationDecisionService.upload(eq(OIB), any()))
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
        when(categorizationDecisionService.upload(eq(OIB), any()))
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
        when(categorizationDecisionService.upload(eq(OIB), any())).thenThrow(new ConflictException(
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
