package com.str.backend.rn;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.domain.RnStatus;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.lookup.AccommodationTypeEntity;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

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
    @Autowired private RnRepository rnRepository;
    @Autowired private AccommodationRepository accommodationRepository;
    @Autowired private SubmissionRepository submissionRepository;
    @Autowired private LessorRepository lessorRepository;
    @Autowired private AccommodationTypeRepository accommodationTypeRepository;

    private Long apartmanTypeId;

    @BeforeEach
    void seed() {
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
}
