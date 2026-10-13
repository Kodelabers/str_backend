package com.str.backend.registration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.address.AddressHierarchyValidator;
import com.str.backend.address.CountyEntity;
import com.str.backend.address.CountyRepository;
import com.str.backend.admin.AdminCategorizationDecisionService;
import com.str.backend.categorization.CategorizationDecisionAdminDto;
import com.str.backend.categorization.CategorizationDecisionEntity;
import com.str.backend.categorization.CategorizationDecisionEntity.CategorizationDecisionMetadata;
import com.str.backend.categorization.CategorizationDecisionRepository;
import com.str.backend.categorization.CategorizationDecisionStatus;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.lessor.LessorRnActionService;
import com.str.backend.lessor.SubjectProfileService;
import com.str.backend.lookup.AccommodationTypeEntity;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.registration.dto.RegistrationExternalRequest;
import com.str.backend.registration.dto.RegistrationRequest;
import com.str.backend.registration.dto.RegistrationResponse;
import com.str.backend.registration.dto.ReissuePrefillResponse;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T15 kroz stvarni Hibernate i H2: izdavanje → rješenje → povlačenje → predpopuna → ponovni zahtjev
 * s {@code prethodniRb} → drugi pokušaj istim RB-om. Pokriva upite koje jedinični testovi ne vide
 * (EXISTS u projekciji popisa, {@code existsByPreviousRn}, sažetak rješenja, filtar internog reda).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReissueIntegrationTest {

    private static final String OIB = "12312312316";
    /** Testovi dijele H2 bazu: svaka adresa je nova, inače DUPLICATE_LOCATION. */
    private static final AtomicInteger STREET_NUMBERS = new AtomicInteger(5000);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private ReissueService reissueService;
    @Autowired private RegistrationService registrationService;
    @Autowired private LessorRnActionService rnActionService;
    @Autowired private AdminCategorizationDecisionService adminDecisionService;
    @Autowired private RnRepository rnRepository;
    @Autowired private LessorRepository lessorRepository;
    @Autowired private AccommodationRepository accommodationRepository;
    @Autowired private CategorizationDecisionRepository decisionRepository;

    @Autowired private AccommodationTypeRepository accommodationTypeRepository;

    @MockBean private SubjectProfileService subjectProfileService;

    /** Vrsta je dio pravila „isti objekt", pa je zahtjevi moraju nositi. */
    private static String typeId;
    @MockBean private CountyRepository countyRepository;
    /** Zahtjevi nose nazive umjesto id-eva općine i naselja; lanac adrese pokriva AddressHierarchyValidatorTest. */
    @MockBean private AddressHierarchyValidator addressHierarchyValidator;

    @BeforeEach
    void setUp() {
        when(subjectProfileService.resolveLessor(any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    LessorEntity l = LessorEntity.create("PERO", "PERIĆ",
                            "Ilica", "1", "Zagreb", "Grad Zagreb", null);
                    l.setLessorOib((String) inv.getArgument(0));
                    return l;
                });
        when(countyRepository.findById(2L)).thenReturn(Optional.of(county(2L, "Splitsko-dalmatinska županija")));
        typeId = String.valueOf(accommodationTypeRepository
                .save(new AccommodationTypeEntity("Apartman", true, "OBITELJSKI")).getTypeId());
    }

    @Test
    void sameObject_carriesVerifiedDecision_andIsSingleUse() throws Exception {
        String streetNumber = nextNumber();
        String first = issue(request(streetNumber, 4, null)).registrationNumber();
        CategorizationDecisionEntity decision = decision(first);
        decision.verify("sluzbenik");
        decisionRepository.save(decision);
        rnActionService.withdrawOwnByOib(first, OIB, null);

        // Popis: povučeni RB novog objekta nudi ponovni zahtjev do validTo + 18 mjeseci.
        LocalDate withdrawnOn = rnRepository.findById(first).orElseThrow().getValidTo();
        assertThat(deadlineOf(first)).isEqualTo(withdrawnOn.plusMonths(18));

        // Predpopuna: podaci objekta, GO izjave i potvrđeno rješenje.
        ReissuePrefillResponse prefill = reissueService.prefill(first, RnOwner.byOib(OIB));
        assertThat(prefill.objekt().naziv()).isEqualTo("Apartman Sunce");
        assertThat(prefill.objekt().kucniBrojNaziv()).isEqualTo(streetNumber);
        assertThat(prefill.objekt().brKreveta()).isEqualTo(4);
        assertThat(prefill.objekt().legalized()).isTrue();
        assertThat(prefill.objekt().building()).isFalse();
        assertThat(prefill.objekt().kontaktEmail()).isEqualTo("iznajmljivac@example.com");
        assertThat(prefill.rjesenje().decisionId()).isEqualTo(decision.getDecisionId());
        assertThat(prefill.rjesenje().status()).isEqualTo(CategorizationDecisionStatus.VERIFIED);

        // Ponovni zahtjev za isti objekt (manje kreveta je i dalje isti objekt).
        RegistrationResponse second = issue(request(streetNumber, 3, first));
        assertThat(second.registrationNumber()).isNotEqualTo(first);
        assertThat(second.rjesenjePreneseno()).isTrue();

        RnEntity secondRn = rnRepository.findById(second.registrationNumber()).orElseThrow();
        AccommodationEntity secondAccommodation = accommodationRepository.findById(secondRn.getAccommodationId()).orElseThrow();
        assertThat(secondAccommodation.getPreviousRn()).isEqualTo(first);

        CategorizationDecisionEntity copy = decisionRepository
                .findFirstByRnAndStatusInOrderByUploadedAtDesc(second.registrationNumber(),
                        List.of(CategorizationDecisionStatus.VERIFIED))
                .orElseThrow();
        assertThat(copy.getSourceDecisionId()).isEqualTo(decision.getDecisionId());
        assertThat(copy.getVerifiedBy()).isEqualTo("sluzbenik");
        assertThat(copy.getUploadedAt()).isAfterOrEqualTo(decision.getUploadedAt());
        assertThat(copy.getFileContent()).containsExactly(0x25, 0x50, 0x44, 0x46, 1);
        // Izvor ostaje uz povučeni RB.
        assertThat(decisionRepository.findById(decision.getDecisionId()).orElseThrow().getRn()).isEqualTo(first);

        // Iskorišten: popis više ne nudi, a druga predaja istim RB-om je 409.
        assertThat(deadlineOf(first)).isNull();
        mvc.perform(post("/api/generateRegistrationNumber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(request(nextNumber(), 4, first))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.code").value("REISSUE_ALREADY_USED"));
    }

    /** Više kreveta → drugi objekt: novi RB nastaje, ali bez rješenja; FE traži upload. */
    @Test
    void changedObject_issuesRnWithoutDecision() throws Exception {
        String streetNumber = nextNumber();
        String first = issue(request(streetNumber, 4, null)).registrationNumber();
        CategorizationDecisionEntity decision = decision(first);
        decision.verify("sluzbenik");
        decisionRepository.save(decision);
        rnActionService.withdrawOwnByOib(first, OIB, null);

        RegistrationResponse second = issue(request(streetNumber, 6, first));

        assertThat(second.rjesenjePreneseno()).isFalse();
        assertThat(decisionRepository.findFirstByRnAndStatusInOrderByUploadedAtDesc(
                second.registrationNumber(), List.of(CategorizationDecisionStatus.values()))).isEmpty();
        // RB je ipak iskorišten — podaci su preuzeti.
        assertThat(deadlineOf(first)).isNull();
    }

    /** Preneseno SUBMITTED rješenje: u internom redu ostaje samo kopija, izvor ispada. */
    @Test
    void carriedSubmittedDecision_leavesOnlyCopyInAdminQueue() throws Exception {
        String streetNumber = nextNumber();
        String first = issue(request(streetNumber, 4, null)).registrationNumber();
        CategorizationDecisionEntity source = decision(first);
        rnActionService.withdrawOwnByOib(first, OIB, null);
        assertThat(queueIds()).contains(source.getDecisionId());

        RegistrationResponse second = issue(request(streetNumber, 4, first));
        assertThat(second.rjesenjePreneseno()).isTrue();

        CategorizationDecisionEntity copy = decisionRepository.findFirstByRnAndStatusInOrderByUploadedAtDesc(
                second.registrationNumber(), List.of(CategorizationDecisionStatus.SUBMITTED)).orElseThrow();
        List<UUID> queue = queueIds();
        assertThat(queue).contains(copy.getDecisionId()).doesNotContain(source.getDecisionId());
        assertThat(adminDecisionService.list(null, PageRequest.of(0, 500)).getContent())
                .extracting(CategorizationDecisionAdminDto::decisionId)
                .doesNotContain(source.getDecisionId());
    }

    /** Odbijeno rješenje se ne nudi u predpopuni i ne prenosi se na novi RB. */
    @Test
    void rejectedDecision_isNeitherOfferedNorCarried() throws Exception {
        String streetNumber = nextNumber();
        String first = issue(request(streetNumber, 4, null)).registrationNumber();
        CategorizationDecisionEntity rejected = decision(first);
        rejected.reject("sluzbenik");
        decisionRepository.save(rejected);
        rnActionService.withdrawOwnByOib(first, OIB, null);

        assertThat(reissueService.prefill(first, RnOwner.byOib(OIB)).rjesenje()).isNull();

        RegistrationResponse second = issue(request(streetNumber, 4, first));
        assertThat(second.rjesenjePreneseno()).isFalse();
        assertThat(decisionRepository.findFirstByRnAndStatusInOrderByUploadedAtDesc(
                second.registrationNumber(), List.of(CategorizationDecisionStatus.values()))).isEmpty();
    }

    /** Tuđi OIB dobiva 404 s kodom, isto kao za nepostojeći RB. */
    @Test
    void foreignOwner_is404WithCode() throws Exception {
        String first = issue(request(nextNumber(), 4, null)).registrationNumber();
        rnActionService.withdrawOwnByOib(first, OIB, null);

        RegistrationRequest foreign = RegistrationRequest.withOib(request(nextNumber(), 4, first), "99999999990");
        mvc.perform(post("/api/generateRegistrationNumber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(foreign)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details.code").value("REISSUE_NOT_FOUND"));
    }

    @Test
    void activeRn_is409() throws Exception {
        String first = issue(request(nextNumber(), 4, null)).registrationNumber();

        mvc.perform(post("/api/generateRegistrationNumber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(request(nextNumber(), 4, first))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.code").value("REISSUE_NOT_WITHDRAWN"));
    }

    @Test
    void malformedPreviousRn_is400() throws Exception {
        mvc.perform(post("/api/generateRegistrationNumber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(request(nextNumber(), 4, "HR123"))))
                .andExpect(status().isBadRequest());
    }

    /** non-EU: vlasništvo po lessorId ({@code isOwnedByLessor}), popis s rokom i ponovni zahtjev. */
    @Test
    void nonEu_ownedByLessorId_listsDeadline_andReissues() {
        LessorEntity lessor = lessorRepository.save(LessorEntity.createNonEu("John", "Doe", "Main St", "1",
                "London", null, "john" + nextNumber() + "@example.com", "john" + nextNumber(), "hash"));
        UUID lessorId = lessor.getLessorId();
        String streetNumber = nextNumber();

        String first = registrationService.generateRegistrationNumberExternal(external(streetNumber, null), lessorId)
                .registrationNumber();
        rnActionService.withdrawOwn(first, lessorId, null);

        LocalDate deadline = reissueService.withReissueDeadlines(rnRepository.findByLessorId(lessorId)).stream()
                .filter(r -> r.rn().equals(first)).findFirst().orElseThrow().ponovniZahtjevDo();
        assertThat(deadline).isEqualTo(rnRepository.findById(first).orElseThrow().getValidTo().plusMonths(18));
        assertThat(reissueService.prefill(first, RnOwner.byLessor(lessorId)).objekt().naziv()).isEqualTo("Villa");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> reissueService.prefill(first, RnOwner.byLessor(UUID.randomUUID())))
                .isInstanceOf(com.str.backend.exception.ResourceNotFoundException.class);

        String second = registrationService.generateRegistrationNumberExternal(external(streetNumber, first), lessorId)
                .registrationNumber();
        RnEntity secondRn = rnRepository.findById(second).orElseThrow();
        assertThat(accommodationRepository.findById(secondRn.getAccommodationId()).orElseThrow().getPreviousRn())
                .isEqualTo(first);
    }

    // ── pomoćno ─────────────────────────────────────────────────────────────

    private List<UUID> queueIds() {
        return adminDecisionService.list(CategorizationDecisionStatus.SUBMITTED, PageRequest.of(0, 500)).getContent()
                .stream().map(CategorizationDecisionAdminDto::decisionId).toList();
    }

    private CategorizationDecisionEntity decision(String rn) {
        return decisionRepository.save(CategorizationDecisionEntity.create(
                OIB, rn, "rjesenje.pdf", "application/pdf", new byte[]{0x25, 0x50, 0x44, 0x46, 1},
                new CategorizationDecisionMetadata("Apartman Sunce", null, null, null, null, 4, null)));
    }

    private LocalDate deadlineOf(String rn) {
        return reissueService.withReissueDeadlines(rnRepository.findByLessorOib(OIB)).stream()
                .filter(r -> r.rn().equals(rn))
                .findFirst()
                .orElseThrow()
                .ponovniZahtjevDo();
    }

    private RegistrationResponse issue(RegistrationRequest request) throws Exception {
        MvcResult result = mvc.perform(post("/api/generateRegistrationNumber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = om.readTree(result.getResponse().getContentAsByteArray());
        return new RegistrationResponse(body.get("registrationNumber").asText(),
                UUID.fromString(body.get("submissionId").asText()),
                body.get("rjesenjePreneseno").asBoolean());
    }

    private static String nextNumber() {
        return String.valueOf(STREET_NUMBERS.incrementAndGet());
    }

    private static RegistrationRequest request(String streetNumber, int beds, String previousRn) {
        return new RegistrationRequest(
                OIB, "Apartman Sunce", typeId,
                2L, "Split", null,
                "Ulica kralja Tomislava", streetNumber, null, "21000",
                beds,
                OfferType.PRIMARY_RESIDENCE, Offering.WHOLE,
                false, "2", false, true,
                null, null, null, null, null, null, null,
                "iznajmljivac@example.com", "0991234567", null, null, null, null, previousRn);
    }

    private static RegistrationExternalRequest external(String streetNumber, String previousRn) {
        return new RegistrationExternalRequest(
                "Villa", typeId, 2L, "Split", null, "Obala", streetNumber, null, "21000", 4,
                OfferType.OTHER, Offering.WHOLE, false, "0", false, true,
                null, null, null, null, null, null, null,
                null, "+441234567", null, null, null, previousRn);
    }

    private static CountyEntity county(Long id, String name) {
        try {
            var ctor = CountyEntity.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            CountyEntity c = ctor.newInstance();
            set(c, "id", id);
            set(c, "name", name);
            set(c, "zuRb", id.intValue());
            return c;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
