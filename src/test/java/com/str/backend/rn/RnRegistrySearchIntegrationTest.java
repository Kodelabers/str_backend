package com.str.backend.rn;

import com.str.backend.auth.internal.CountyScope;
import com.str.backend.auth.internal.InternalAccessService;
import com.str.backend.auth.internal.OfficialCountyResolver;
import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.activity.AccommodationActivityEntity;
import com.str.backend.activity.AccommodationActivityRepository;
import com.str.backend.categorization.CategorizationDecisionEntity;
import com.str.backend.categorization.CategorizationDecisionEntity.CategorizationDecisionMetadata;
import com.str.backend.categorization.CategorizationDecisionRepository;
import com.str.backend.categorization.CategorizationDecisionStatus;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.domain.RnStatus;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.lookup.AccommodationTypeEntity;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.dto.RnSummaryDto;
import com.str.backend.str.RnFacilityVerification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Megasearch + per-field filter acceptance for GET /api/rn (BACKEND_SEARCH_SPEC §1).
 * Pins the token-AND cases and the COALESCE/NULL-lessor invariant.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RnRegistrySearchIntegrationTest {

    @Autowired private MockMvc mvc;

    /** Endpoint je back-office; ovdje se testira pretraga, ne autorizacija (v. BackOfficeSecurityTest). */
    @MockBean private InternalAccessService internalAccess;
    /** eTurizam (str.*) — verifikacija objekta; H2 nema njegove podatke. */
    @MockBean private RnFacilityVerification facilityVerification;
    /** Organizacija službenika (eTurizam); bez stubiranja nitko nije službenik, pa je opseg cijela RH. */
    @MockBean private OfficialCountyResolver countyResolver;
    @Autowired private RnRegistrySearch registrySearch;
    @Autowired private CategorizationDecisionRepository decisionRepository;
    @Autowired private RnRepository rnRepository;
    @Autowired private AccommodationRepository accommodationRepository;
    @Autowired private SubmissionRepository submissionRepository;
    @Autowired private LessorRepository lessorRepository;
    @Autowired private AccommodationTypeRepository accommodationTypeRepository;
    @Autowired private AccommodationActivityRepository activityRepository;

    private Long apartmanTypeId;

    @BeforeEach
    void seed() {
        when(internalAccess.hasRole(any(), eq(InternalAccessService.RN_MANAGEMENT))).thenReturn(true);
        activityRepository.deleteAll();
        decisionRepository.deleteAll();
        rnRepository.deleteAll();
        accommodationRepository.deleteAll();
        submissionRepository.deleteAll();
        lessorRepository.deleteAll();
        accommodationTypeRepository.deleteAll();

        apartmanTypeId = accommodationTypeRepository
                .save(new AccommodationTypeEntity("Apartman", true, "OBITELJSKI")).getTypeId();

        // Record A — the canonical spec fixture (full lessor).
        LessorEntity ivan = lessorRepository.save(
                LessorEntity.create("Ivan", "Ivić", "Korzo", "2", "Rijeka",
                        "Primorsko-goranska županija", "ivan@example.hr"));
        seedRn("HR00000001", ivan.getLessorId(), "Primorsko-goranska županija", "Rijeka",
                "Korzo", "2", "Apartman More");

        // Record B — different county/city, for AND-semantics negatives.
        LessorEntity marko = lessorRepository.save(
                LessorEntity.create("Marko", "Marić", "Ilica", "5", "Zagreb",
                        "Grad Zagreb", "marko@example.hr"));
        seedRn("HR00000002", marko.getLessorId(), "Grad Zagreb", "Zagreb",
                "Ilica", "5", "Stan Centar");

        // Record C — NULL lessor (no submission row → correlated subquery yields null).
        // This is the COALESCE guard: if the haystack CONCAT propagated NULL, C would
        // vanish from any non-empty q search.
        seedRn("HR00000003", null, "Splitsko-dalmatinska županija", "Split",
                "Riva", "8", "Vila Split");
    }

    private void seedRn(String rn, UUID lessorId, String county, String city,
                        String street, String streetNumber, String name) {
        UUID submissionId;
        if (lessorId != null) {
            SubmissionEntity submission = submissionRepository.save(
                    SubmissionEntity.create("FN-" + rn, lessorId, 1L, Instant.now(), null, null));
            submissionId = submission.getSubmissionId();
        } else {
            submissionId = UUID.randomUUID(); // no matching submission → lessor stays null
        }
        AccommodationEntity acc = AccommodationEntity.create(submissionId, county, city, street,
                streetNumber, 4, 8, OfferType.PRIMARY_RESIDENCE, Offering.WHOLE, true, false, true);
        acc.setName(name);
        acc.setAccommodationTypeId(apartmanTypeId);
        accommodationRepository.save(acc);

        rnRepository.save(RnEntity.issue(rn, submissionId, acc.getAccommodationId(), LocalDate.now()));
    }

    @Test
    void tokenAnd_matchesAcrossFields() throws Exception {
        mvc.perform(get("/api/rn").param("q", "korzo 2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].rn").value("HR00000001"));
    }

    @Test
    void tokenAnd_crossFieldWithCity() throws Exception {
        mvc.perform(get("/api/rn").param("q", "korzo 2 rijeka"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].rn").value("HR00000001"));
    }

    @Test
    void tokenAnd_commaTreatedAsWhitespace() throws Exception {
        mvc.perform(get("/api/rn").param("q", "korzo 2, rijeka"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].rn").value("HR00000001"));
    }

    @Test
    void tokenAnd_oneTokenInNoField_yieldsNoMatch() throws Exception {
        mvc.perform(get("/api/rn").param("q", "korzo 2 split"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void q_matchesRegistrationNumber() throws Exception {
        mvc.perform(get("/api/rn").param("q", "hr00000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].rn").value("HR00000001"));
    }

    @Test
    void perFieldFilters_eachNarrowToRecordA() throws Exception {
        assertSingleA(get("/api/rn").param("rb", "HR00000001"));
        assertSingleA(get("/api/rn").param("city", "rij"));
        assertSingleA(get("/api/rn").param("street", "korz"));
        assertSingleA(get("/api/rn").param("name", "more"));
        assertSingleA(get("/api/rn").param("lessor", "ivić"));
    }

    private void assertSingleA(MockHttpServletRequestBuilder req) throws Exception {
        mvc.perform(req)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].rn").value("HR00000001"));
    }

    @Test
    void combinedFilters_areAnded() throws Exception {
        // rb=HR0000 matches all three rns; county+city narrow to A only.
        mvc.perform(get("/api/rn")
                        .param("county", "Primorsko-goranska županija")
                        .param("city", "rij")
                        .param("rb", "HR0000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].rn").value("HR00000001"));

        mvc.perform(get("/api/rn").param("county", "Grad Zagreb"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].rn").value("HR00000002"));
    }

    @Test
    void invalidView_appliesFiltersOverSuspendedAndWithdrawnOnly() throws Exception {
        RnEntity a = rnRepository.findById("HR00000001").orElseThrow();
        a.applyStatus(RnStatus.SUSPENDED); // package-private, same package
        rnRepository.save(a);

        // default ACTIVE view no longer sees the suspended record
        mvc.perform(get("/api/rn").param("q", "korzo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // INVALID view does, and the megasearch still applies within that view
        mvc.perform(get("/api/rn").param("view", "INVALID").param("q", "korzo 2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].rn").value("HR00000001"));
    }

    @Test
    void nullLessorRecord_stillMatchesViaItsOwnFields() throws Exception {
        // Critical: record C has no lessor. Token-AND over the COALESCE'd haystack must
        // still find it by city. A NULL-propagating CONCAT would drop it entirely.
        mvc.perform(get("/api/rn").param("q", "split"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].rn").value("HR00000003"));
    }

    // --- Radna lista „rok za očitovanje ističe uskoro" (primjedba s UAT-a) ---

    /** Postavlja rok bez prolaska kroz suspend(), da fixture ne ovisi o statusnom stroju. */
    private void seedProposedWithDeadline(String rn, LocalDate deadline) {
        RnEntity e = rnRepository.findById(rn).orElseThrow();
        e.applyStatus(RnStatus.SUSPENSION_PROPOSED); // package-private, same package
        e.setSuspensionDeadline(deadline);
        rnRepository.save(e);
    }

    @Test
    void deadlineWithinDays_selectsOnlyRnsExpiringInsideTheWindow() throws Exception {
        seedProposedWithDeadline("HR00000001", LocalDate.now().plusDays(3));
        seedProposedWithDeadline("HR00000002", LocalDate.now().plusDays(30));

        mvc.perform(get("/api/rn")
                        .param("view", "SUSPENSION_PROPOSED")
                        .param("deadlineWithinDays", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].rn").value("HR00000001"))
                .andExpect(jsonPath("$.content[0].suspensionDeadline").exists());

        mvc.perform(get("/api/rn")
                        .param("view", "SUSPENSION_PROPOSED")
                        .param("deadlineWithinDays", "60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    /**
     * Rok koji je već istekao mora ostati na listi: job se vrti jednom dnevno, pa između isteka
     * i prelaska u SUSPENDED postoji prozor u kojem je predmet najhitniji. Donje granice nema.
     */
    @Test
    void deadlineWithinDays_keepsAlreadyExpiredDeadlines() throws Exception {
        seedProposedWithDeadline("HR00000001", LocalDate.now().minusDays(2));

        mvc.perform(get("/api/rn")
                        .param("view", "SUSPENSION_PROPOSED")
                        .param("deadlineWithinDays", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].rn").value("HR00000001"));
    }

    /** RB bez postavljenog roka ne smije upasti u radnu listu. */
    @Test
    void deadlineWithinDays_excludesRnsWithoutDeadline() throws Exception {
        RnEntity e = rnRepository.findById("HR00000001").orElseThrow();
        e.applyStatus(RnStatus.SUSPENSION_PROPOSED);
        rnRepository.save(e);

        mvc.perform(get("/api/rn")
                        .param("view", "SUSPENSION_PROPOSED")
                        .param("deadlineWithinDays", "365"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    /**
     * {@code Integer.MAX_VALUE} dana daje godinu izvan raspona Postgresovog {@code date} tipa;
     * bez ograničenja upit tamo pada s 500. H2 to proguta, pa test čuva samo da endpoint ostane
     * na 200 — sama kapa je u {@code RnService}.
     */
    @Test
    void deadlineWithinDays_absurdWindowDoesNotBlowUp() throws Exception {
        seedProposedWithDeadline("HR00000001", LocalDate.now().plusDays(3));

        mvc.perform(get("/api/rn")
                        .param("view", "SUSPENSION_PROPOSED")
                        .param("deadlineWithinDays", String.valueOf(Integer.MAX_VALUE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    /** Negativan prozor je smislen upit („istekao prije barem x dana"), ne izostanak filtra. */
    @Test
    void deadlineWithinDays_negativeWindowLooksIntoThePast() throws Exception {
        seedProposedWithDeadline("HR00000001", LocalDate.now().minusDays(10));
        seedProposedWithDeadline("HR00000002", LocalDate.now().plusDays(10));

        mvc.perform(get("/api/rn")
                        .param("view", "SUSPENSION_PROPOSED")
                        .param("deadlineWithinDays", "-5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].rn").value("HR00000001"));
    }

    /** Bez parametra filtar ne smije ništa odsjeći. */
    @Test
    void withoutDeadlineParam_allProposedRnsAreListed() throws Exception {
        seedProposedWithDeadline("HR00000001", LocalDate.now().plusDays(3));
        RnEntity b = rnRepository.findById("HR00000002").orElseThrow();
        b.applyStatus(RnStatus.SUSPENSION_PROPOSED);
        rnRepository.save(b);

        mvc.perform(get("/api/rn").param("view", "SUSPENSION_PROPOSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    // --- Stupci „Rješenje o kategorizaciji" i „Verificiran" (pravilo kao „Moji RB") ---

    private void setFacility(String rn, String facilityId) {
        AccommodationEntity acc = accommodationRepository
                .findById(rnRepository.findById(rn).orElseThrow().getAccommodationId()).orElseThrow();
        acc.setFacilityId(facilityId);
        accommodationRepository.save(acc);
    }

    private CategorizationDecisionEntity decision(String rn) {
        return decisionRepository.save(CategorizationDecisionEntity.create("99999999990", rn,
                "rjesenje.pdf", "application/pdf", new byte[]{1, 2, 3},
                new CategorizationDecisionMetadata(null, null, null, null, null, null, null)));
    }

    /**
     * A: novi objekt, predano rješenje. B: objekt iz eTurizma bez rješenja. C: novi objekt bez
     * rješenja. D: novi objekt, potvrđeno. E: novi objekt, odbijeno. F: novi objekt bez rješenja,
     * povučen — konačan broj rješenje ne traži.
     */
    private void seedDecisions() {
        seedRn("HR00000004", null, "Grad Zagreb", "Zagreb", "Vlaška", "1", "Stan D");
        seedRn("HR00000005", null, "Grad Zagreb", "Zagreb", "Vlaška", "2", "Stan E");
        seedRn("HR00000006", null, "Grad Zagreb", "Zagreb", "Vlaška", "3", "Stan F");
        RnEntity f = rnRepository.findById("HR00000006").orElseThrow();
        f.applyStatus(RnStatus.WITHDRAWN);
        rnRepository.save(f);
        setFacility("HR00000002", "153049");

        decision("HR00000001");
        CategorizationDecisionEntity verified = decision("HR00000004");
        verified.verify("sluzbenik");
        decisionRepository.save(verified);
        CategorizationDecisionEntity rejected = decision("HR00000005");
        rejected.reject("sluzbenik");
        decisionRepository.save(rejected);
    }

    @Test
    void decisionColumn_followsTheLessorListRule() throws Exception {
        seedDecisions();

        mvc.perform(get("/api/rn").param("view", "ALL").param("rb", "HR0000000")
                        .param("sort", "rn,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.content[0].categorizationStatus").value("SUBMITTED"))
                .andExpect(jsonPath("$.content[0].categorizationRequired").value(true))
                .andExpect(jsonPath("$.content[1].categorizationStatus").doesNotExist())
                .andExpect(jsonPath("$.content[1].categorizationRequired").value(false))
                .andExpect(jsonPath("$.content[2].categorizationRequired").value(true))
                .andExpect(jsonPath("$.content[3].categorizationStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.content[4].categorizationStatus").value("REJECTED"))
                .andExpect(jsonPath("$.content[5].categorizationRequired").value(false))
                // facilityId služi samo izračunu stupca „Verificiran"
                .andExpect(jsonPath("$.content[1].facilityId").doesNotExist());
    }

    @Test
    void decisionFilter_eachValueSelectsWhatTheColumnShows() throws Exception {
        seedDecisions();

        assertOnly(get("/api/rn").param("view", "ALL").param("decision", "SUBMITTED"), "HR00000001");
        assertOnly(get("/api/rn").param("view", "ALL").param("decision", "VERIFIED"), "HR00000004");
        assertOnly(get("/api/rn").param("view", "ALL").param("decision", "REJECTED"), "HR00000005");
        assertOnly(get("/api/rn").param("view", "ALL").param("decision", "NONE"), "HR00000003");
        mvc.perform(get("/api/rn").param("view", "ALL").param("decision", "NOT_REQUIRED")
                        .param("sort", "rn,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].rn").value("HR00000002"))
                .andExpect(jsonPath("$.content[1].rn").value("HR00000006"));
    }

    @Test
    void decisionFilter_unknownValueIs400() throws Exception {
        mvc.perform(get("/api/rn").param("decision", "NEPOSTOJECI"))
                .andExpect(status().isBadRequest());
    }

    private void assertOnly(MockHttpServletRequestBuilder req, String rn) throws Exception {
        mvc.perform(req)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].rn").value(rn));
    }

    @Test
    void verifiedColumn_comesFromETurizam_newObjectIsNotVerified() throws Exception {
        setFacility("HR00000002", "153049");
        when(facilityVerification.verifiedFacilityIds(any())).thenReturn(Set.of("153049"));

        mvc.perform(get("/api/rn").param("sort", "rn,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].facilityVerified").value(false))
                .andExpect(jsonPath("$.content[1].facilityVerified").value(true))
                .andExpect(jsonPath("$.content[2].facilityVerified").value(false));
    }

    /** Kvar eTurizma ne ruši registar: stupac je prazan, frontend prikazuje „-". */
    @Test
    void verifiedColumn_eTurizamDown_isNullButRegistryLoads() throws Exception {
        setFacility("HR00000002", "153049");
        when(facilityVerification.verifiedFacilityIds(any()))
                .thenThrow(new DataAccessResourceFailureException("str nedostupan"));

        mvc.perform(get("/api/rn"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].facilityVerified").doesNotExist());
    }

    @Test
    void verifiedFilter_pagesOverTheWholeRegistry() throws Exception {
        setFacility("HR00000002", "153049");
        setFacility("HR00000003", "200");
        when(facilityVerification.verifiedFacilityIds(any())).thenReturn(Set.of("153049"));

        mvc.perform(get("/api/rn").param("verified", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].rn").value("HR00000002"))
                .andExpect(jsonPath("$.content[0].facilityVerified").value(true));

        // „Ne" su i neverificirani eTurizam objekt i novi objekt bez facilityId.
        mvc.perform(get("/api/rn").param("verified", "false").param("sort", "rn,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].rn").value("HR00000001"))
                .andExpect(jsonPath("$.content[1].rn").value("HR00000003"))
                .andExpect(jsonPath("$.content[1].facilityVerified").value(false));

        // Pita se samo za objekte koje ostali filtri propuštaju.
        verify(facilityVerification, atLeastOnce()).verifiedFacilityIds(
                argThat(ids -> ids.containsAll(List.of("153049", "200")) && ids.size() == 2));
    }

    @Test
    void verifiedFilter_withoutAnyVerifiedObject() throws Exception {
        setFacility("HR00000002", "153049");
        when(facilityVerification.verifiedFacilityIds(any())).thenReturn(Set.of());

        mvc.perform(get("/api/rn").param("verified", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/rn").param("verified", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    /** Filtar se ne smije tiho preskočiti: bez eTurizma ne zna se što bi propustio. */
    @Test
    void verifiedFilter_eTurizamDown_is503() throws Exception {
        setFacility("HR00000002", "153049");
        when(facilityVerification.verifiedFacilityIds(any()))
                .thenThrow(new DataAccessResourceFailureException("str nedostupan"));

        mvc.perform(get("/api/rn").param("verified", "true"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void verifiedAndDecisionFilters_combine() throws Exception {
        seedDecisions();
        when(facilityVerification.verifiedFacilityIds(any())).thenReturn(Set.of("153049"));

        assertOnly(get("/api/rn").param("view", "ALL").param("verified", "true")
                .param("decision", "NOT_REQUIRED"), "HR00000002");
        mvc.perform(get("/api/rn").param("view", "ALL").param("verified", "true")
                        .param("decision", "NONE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void csvExport_carriesBothColumnsAndFilters() throws Exception {
        seedDecisions();
        when(facilityVerification.verifiedFacilityIds(any())).thenReturn(Set.of("153049"));

        String csv = mvc.perform(get("/api/rn/export/csv").param("decision", "NONE"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        String[] lines = csv.split("\r\n");
        assertThat(lines[0]).endsWith("Županija,Verificiran,Rješenje o kategorizaciji");
        assertThat(lines).hasSize(2);
        assertThat(lines[1]).startsWith("HR00000003,").endsWith(",Ne,Nije predano");
    }

    // --- Opseg službenika (županija) ---

    /** Županijski službenik dobiva opseg iz svoje organizacije u eTurizmu; ovdje ga daje mock resolvera. */
    private void countyOfficial(String countyName) {
        when(internalAccess.officialOib(any())).thenReturn(Optional.of("12345678903"));
        when(countyResolver.resolve(any()))
                .thenReturn(CountyScope.county(countyName, 17L, 17, countyName));
    }

    @Test
    void scopeCounties_restrictRegistryToListedCounty() {
        RnRegistryFilter filter = new RnRegistryFilter(RnRegistryView.ALL, null, null, null, null,
                false, null, null, null, null, null, null, null, null, List.of("Splitsko-dalmatinska županija"));

        assertThat(registrySearch.search(filter, PageRequest.of(0, 20)).getContent())
                .extracting(RnSummaryDto::rn).containsExactly("HR00000003");
    }

    /**
     * U upitu se {@code county} i opseg zbrajaju (AND), pa tuđa županija ne proširuje opseg. Da
     * službenik ne dobije prazan registar, {@code county} iz zahtjeva zanemaruje već kontroler
     * (v. {@link #countyOfficial_registry_seesOnlyOwnCounty_evenWithForeignCountyParam}).
     */
    @Test
    void scopeCounties_andForeignCountyFilter_neverWidenScope() {
        RnRegistryFilter filter = new RnRegistryFilter(RnRegistryView.ALL, null, "Grad Zagreb", null, null,
                false, null, null, null, null, null, null, null, null, List.of("Splitsko-dalmatinska županija"));

        assertThat(registrySearch.search(filter, PageRequest.of(0, 20)).getContent()).isEmpty();
    }

    @Test
    void scopeCounties_matchEveryListedVariant() {
        RnRegistryFilter filter = new RnRegistryFilter(RnRegistryView.ALL, null, null, null, null,
                false, null, null, null, null, null, null, null, null,
                List.of("Splitsko-dalmatinska županija", "Grad Zagreb"));

        assertThat(registrySearch.search(filter, PageRequest.of(0, 20)).getContent())
                .extracting(RnSummaryDto::rn).containsExactlyInAnyOrder("HR00000002", "HR00000003");
    }

    /** Kroz kontroler: opseg iz {@code CountyScopeService}, nazivi iz {@code accommodation.county}. */
    @Test
    void countyOfficial_registry_seesOnlyOwnCounty_evenWithForeignCountyParam() throws Exception {
        countyOfficial("Splitsko-dalmatinska županija");

        assertOnly(get("/api/rn").param("county", "Grad Zagreb"), "HR00000003");
        assertOnly(get("/api/rn"), "HR00000003");
    }

    /** Opseg se uspoređuje u Javi: „SPLITSKO-DALMATINSKA" (velika slova) pogađa zapis „…županija". */
    @Test
    void countyOfficial_scopeNameInDifferentForm_stillMatches() throws Exception {
        countyOfficial("SPLITSKO-DALMATINSKA");

        assertOnly(get("/api/rn"), "HR00000003");
    }

    @Test
    void countyOfficial_detailOfForeignCountyRn_is404() throws Exception {
        countyOfficial("Splitsko-dalmatinska županija");

        mvc.perform(get("/api/rn/{rn}/detail", "HR00000002")).andExpect(status().isNotFound());
        mvc.perform(get("/api/rn/{rn}/detail", "HR00000003")).andExpect(status().isOk());
    }

    @Test
    void countyOfficial_inactive_listsOnlyOwnCounty() throws Exception {
        for (String rn : List.of("HR00000002", "HR00000003")) {
            RnEntity e = rnRepository.findById(rn).orElseThrow();
            e.applyStatus(RnStatus.SUSPENDED); // package-private, same package
            rnRepository.save(e);
        }
        countyOfficial("Splitsko-dalmatinska županija");

        mvc.perform(get("/api/rn/inactive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].rn").value("HR00000003"));
    }

    @Test
    void findCountyByRn_returnsStoredAccommodationCounty() {
        assertThat(rnRepository.findCountyByRn("HR00000002")).contains("Grad Zagreb");
        assertThat(rnRepository.findCountyByRn("HR00000003")).contains("Splitsko-dalmatinska županija");
        assertThat(rnRepository.findCountyByRn("HR99999999")).isEmpty();
    }

    @Test
    void findByStatusInAndCountyIn_filtersByStatusAndCounty() {
        RnEntity b = rnRepository.findById("HR00000002").orElseThrow();
        b.applyStatus(RnStatus.REVOKED); // package-private, same package
        rnRepository.save(b);

        assertThat(rnRepository.findByStatusInAndCountyIn(List.of(RnStatus.ACTIVE),
                List.of("Grad Zagreb", "Splitsko-dalmatinska županija")))
                .extracting(RnEntity::getRn).containsExactly("HR00000003");
        assertThat(rnRepository.findByStatusInAndCountyIn(List.of(RnStatus.REVOKED, RnStatus.ACTIVE),
                List.of("Grad Zagreb")))
                .extracting(RnEntity::getRn).containsExactly("HR00000002");
        assertThat(rnRepository.findByStatusInAndCountyIn(List.of(RnStatus.ACTIVE),
                List.of("Splitsko-dalmatinska")))
                .isEmpty();
    }

    /**
     * JPQL {@code searchInCounties} na H2: samo aktivnosti RB-ova čiji je smještaj u zadanim
     * županijama; aktivnost RB-a kojeg nema u registru ne pripada nijednoj županiji.
     */
    @Test
    void activitySearchInCounties_returnsOnlyActivitiesOfListedCounties() {
        LocalDate jan = LocalDate.of(2026, 1, 1);
        LocalDate janEnd = LocalDate.of(2026, 1, 31);
        LocalDate mar = LocalDate.of(2026, 3, 1);
        LocalDate marEnd = LocalDate.of(2026, 3, 31);
        activity(1L, "HR00000001", jan, janEnd);
        activity(1L, "HR00000002", jan, janEnd);
        activity(1L, "HR00000003", jan, janEnd);
        activity(2L, "HR00000003", mar, marEnd);
        activity(1L, "HR99999999", jan, janEnd);

        assertThat(activityRepository.searchInCounties(null, null, null, null,
                List.of("Splitsko-dalmatinska županija")))
                .extracting(AccommodationActivityEntity::getRn)
                .containsExactlyInAnyOrder("HR00000003", "HR00000003");
        assertThat(activityRepository.searchInCounties(null, null, null, null,
                List.of("Splitsko-dalmatinska županija", "Grad Zagreb")))
                .extracting(AccommodationActivityEntity::getRn)
                .containsExactlyInAnyOrder("HR00000002", "HR00000003", "HR00000003");
        // Ostali filtri se zbrajaju s opsegom.
        assertThat(activityRepository.searchInCounties(2L, null, null, null,
                List.of("Splitsko-dalmatinska županija", "Grad Zagreb")))
                .extracting(AccommodationActivityEntity::getPeriodFrom).containsExactly(mar);
        assertThat(activityRepository.searchInCounties(null, "HR00000002", null, null,
                List.of("Splitsko-dalmatinska županija")))
                .isEmpty();
        assertThat(activityRepository.searchInCounties(null, null, LocalDate.of(2026, 2, 1), null,
                List.of("Splitsko-dalmatinska županija")))
                .extracting(AccommodationActivityEntity::getPeriodFrom).containsExactly(mar);
        // Usporedba je doslovna — varijante naziva daje CountyScope.matching, ne upit.
        assertThat(activityRepository.searchInCounties(null, null, null, null,
                List.of("Splitsko-dalmatinska")))
                .isEmpty();
    }

    /**
     * JPQL interni red rješenja u opsegu na H2: samo rješenja RB-ova iz zadanih županija, a izvor
     * prenesen na novi RB (T15) ostaje izvan reda i kad je u opsegu — obrađuje se kopija.
     */
    @Test
    void decisionReviewInCounties_returnsOnlyListedCounties_andExcludesCarriedOverSource() {
        seedRn("HR00000007", null, "Splitsko-dalmatinska županija", "Split", "Riva", "9", "Vila Nova");
        seedRn("HR00000008", null, "Splitsko-dalmatinska županija", "Split", "Riva", "10", "Vila Potvrđena");
        decision("HR00000001");
        CategorizationDecisionEntity zagreb = decision("HR00000002");
        CategorizationDecisionEntity source = decision("HR00000003");
        CategorizationDecisionEntity copy = decisionRepository.save(
                CategorizationDecisionEntity.copyForReissue(source, "HR00000007", Instant.now()));
        CategorizationDecisionEntity verified = decision("HR00000008");
        verified.verify("sluzbenik");
        decisionRepository.save(verified);
        List<String> split = List.of("Splitsko-dalmatinska županija");
        PageRequest page = PageRequest.of(0, 20);

        var splitQueue = decisionRepository.findForReviewInCounties(split, page);
        assertThat(splitQueue.getContent()).extracting(CategorizationDecisionEntity::getDecisionId)
                .containsExactlyInAnyOrder(copy.getDecisionId(), verified.getDecisionId());
        assertThat(splitQueue.getTotalElements()).isEqualTo(2);

        assertThat(decisionRepository.findForReviewInCounties(List.of("Splitsko-dalmatinska županija",
                        "Grad Zagreb"), page).getContent())
                .extracting(CategorizationDecisionEntity::getDecisionId)
                .containsExactlyInAnyOrder(zagreb.getDecisionId(), copy.getDecisionId(), verified.getDecisionId());

        var submitted = decisionRepository.findForReviewByStatusInCounties(
                CategorizationDecisionStatus.SUBMITTED, split, page);
        assertThat(submitted.getContent()).extracting(CategorizationDecisionEntity::getDecisionId)
                .containsExactly(copy.getDecisionId());
        assertThat(submitted.getTotalElements()).isEqualTo(1);

        assertThat(decisionRepository.findForReviewByStatusInCounties(
                        CategorizationDecisionStatus.VERIFIED, split, page).getContent())
                .extracting(CategorizationDecisionEntity::getDecisionId)
                .containsExactly(verified.getDecisionId());
        assertThat(decisionRepository.findForReviewByStatusInCounties(
                        CategorizationDecisionStatus.SUBMITTED, List.of("Primorsko-goranska županija"), page)
                .getContent())
                .extracting(CategorizationDecisionEntity::getRn).containsExactly("HR00000001");
    }

    private void activity(Long platformId, String rn, LocalDate from, LocalDate to) {
        activityRepository.save(AccommodationActivityEntity.ingest(platformId, rn, null, from, to, 3, 2, "DE"));
    }
}
