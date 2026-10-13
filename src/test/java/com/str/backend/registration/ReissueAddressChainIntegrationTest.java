package com.str.backend.registration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.categorization.CategorizationDecisionEntity;
import com.str.backend.categorization.CategorizationDecisionEntity.CategorizationDecisionMetadata;
import com.str.backend.categorization.CategorizationDecisionRepository;
import com.str.backend.categorization.CategorizationDecisionStatus;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRnActionService;
import com.str.backend.lessor.SubjectProfileService;
import com.str.backend.lookup.AccommodationTypeEntity;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.registration.dto.RegistrationRequest;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T15 kroz <b>stvarnu</b> provjeru lanca adrese: {@link ReissueIntegrationTest} mocka
 * {@code AddressHierarchyValidator} jer šalje nazive umjesto id-eva, pa nijedan test nije vrtio
 * ponovni zahtjev s id-evima iz predpopune. Ovdje ni validator ni {@code AddressIdResolver} nisu
 * mockani: lanac županija → općina → naselje → ulica → kućni broj seeda se u H2 tablice registra
 * ({@code rpj_dgu} / {@code eturizam_test}, string ključevi {@code zu_rb}, {@code jls_mb} s LPAD-om na
 * 5, {@code na_mb}), kao u {@code AddressHierarchyValidatorTest}. Obrazac se ponaša kao FE:
 * {@code cityId = String(opcinaId)}, {@code settlementId = String(naseljeId)}, {@code kucniBrojId}.
 *
 * <p>Tablice koje nemaju entitet ({@code postanski_brojevi}, {@code ka_katastar_ko}) i stupac
 * {@code ar_address.kat_opcina_id} test stvara sam, da i katastar ({@code CadastreResolver}) ide
 * stvarnim upitom. Predpopuna se dohvaća kroz {@code GET /api/nias/registrations/{rn}/ponovni-zahtjev};
 * OIB sesije daje {@code nias.mock.fixed-oib}.
 */
@SpringBootTest(properties = "nias.mock.fixed-oib=" + ReissueAddressChainIntegrationTest.OIB)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReissueAddressChainIntegrationTest {

    static final String OIB = "78978978970";

    // Rasponi id-eva su samo ovog testa (AddressHierarchyValidatorTest 97xxxx, PostalCodeQueryTest 99xxxx).
    private static final long COUNTY = 980001L;
    private static final long MUNICIPALITY = 981001L;
    /** Druga općina iste županije — njezino naselje ne pripada {@link #MUNICIPALITY}. */
    private static final long OTHER_MUNICIPALITY = 981002L;
    private static final long SETTLEMENT = 982001L;
    private static final long OTHER_SETTLEMENT = 982002L;
    private static final long STREET = 983001L;
    /** Kućni broj po testu: povučeni RB-ovi i novi RB-ovi dijele H2, inače DUPLICATE_LOCATION. */
    private static final long HOUSE_7 = 984001L;
    private static final long HOUSE_9 = 984002L;
    private static final long CADASTRAL_MUNICIPALITY = 986001L;

    // Nazivi su jedinstveni u bazi: resolver prihvaća razinu samo kad se naziv poklopi s točno jednim zapisom.
    private static final String COUNTY_NAME = "Lanac-T15 županija";
    private static final String MUNICIPALITY_NAME = "Lanac-T15 Grad";
    private static final String SETTLEMENT_NAME = "Lanac-T15 Naselje";
    private static final String STREET_NAME = "Ulica Lanca T15";
    private static final String POSTAL_CODE = "21998";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LessorRnActionService rnActionService;
    @Autowired private RnRepository rnRepository;
    @Autowired private AccommodationRepository accommodationRepository;
    @Autowired private CategorizationDecisionRepository decisionRepository;
    @Autowired private AccommodationTypeRepository accommodationTypeRepository;

    /** Identitet i adresa podnositelja nisu predmet testa. */
    @MockBean private SubjectProfileService subjectProfileService;

    private String typeId;

    @BeforeEach
    void setUp() {
        when(subjectProfileService.resolveLessor(any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    LessorEntity l = LessorEntity.create("IVA", "IVIĆ",
                            "Ilica", "1", "Zagreb", "Grad Zagreb", null);
                    l.setLessorOib((String) inv.getArgument(0));
                    return l;
                });
        typeId = String.valueOf(accommodationTypeRepository
                .save(new AccommodationTypeEntity("Apartman", true, "OBITELJSKI")).getTypeId());
        seedAddressRegistry();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM eturizam_test.ar_address WHERE id BETWEEN 984000 AND 984999");
        jdbc.update("DELETE FROM eturizam_test.ar_ulice WHERE id BETWEEN 983000 AND 983999");
        jdbc.update("DELETE FROM eturizam_test.ka_katastar_ko WHERE id BETWEEN 986000 AND 986999");
        jdbc.update("DELETE FROM rpj_dgu.postanski_brojevi WHERE id BETWEEN 985000 AND 985999");
        jdbc.update("DELETE FROM rpj_dgu.naselja WHERE id BETWEEN 982000 AND 982999");
        jdbc.update("DELETE FROM rpj_dgu.gradovi_i_opcine WHERE id BETWEEN 981000 AND 981999");
        jdbc.update("DELETE FROM rpj_dgu.zupanije WHERE id BETWEEN 980000 AND 980999");
    }

    /**
     * Izdavanje s id-evima registra → povlačenje → predpopuna razrješava id-eve svih razina →
     * ponovni zahtjev s id-evima IZ predpopune prolazi stvarni lanac i katastar te prenosi rješenje.
     */
    @Test
    void reissueWithPrefilledIds_passesRealAddressChain_andCarriesDecision() throws Exception {
        String first = issue(newObjectRequest(MUNICIPALITY, SETTLEMENT, HOUSE_7, "7", null, null));
        CategorizationDecisionEntity decision = decision(first);
        decision.verify("sluzbenik");
        decisionRepository.save(decision);
        rnActionService.withdrawOwnByOib(first, OIB, null);

        JsonNode objekt = json(prefill(first)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.povucenRb").value(first))
                .andExpect(jsonPath("$.rjesenje.status").value(CategorizationDecisionStatus.VERIFIED.name())))
                .get("objekt");

        // AddressIdResolver: nazivi spremljeni uz smještaj → točni id-evi svake razine.
        assertThat(objekt.get("zupanijaId").asLong()).isEqualTo(COUNTY);
        assertThat(objekt.get("opcinaId").asLong()).isEqualTo(MUNICIPALITY);
        assertThat(objekt.get("naseljeId").asLong()).isEqualTo(SETTLEMENT);
        assertThat(objekt.get("ulicaId").asLong()).isEqualTo(STREET);
        assertThat(objekt.get("kucniBrojId").asLong()).isEqualTo(HOUSE_7);
        assertThat(objekt.get("zupanijaNaziv").asText()).isEqualTo(COUNTY_NAME);
        assertThat(objekt.get("opcinaNaziv").asText()).isEqualTo(MUNICIPALITY_NAME);
        assertThat(objekt.get("naseljeNaziv").asText()).isEqualTo(SETTLEMENT_NAME);
        assertThat(objekt.get("ulicaNaziv").asText()).isEqualTo(STREET_NAME);
        assertThat(objekt.get("kucniBrojNaziv").asText()).isEqualTo("7");
        // Katastar je pri prvom izdavanju pročitan iz registra po kucniBrojId.
        assertThat(objekt.get("katastarskaOpcina").asText()).isEqualTo("Lanac-T15 KO");
        assertThat(objekt.get("kcBroj").asText()).isEqualTo("666/3");

        // Ponovni zahtjev s id-evima i nazivima iz predpopune, kako ih FE preslikava u obrazac.
        RegistrationRequest reissue = newObjectRequest(
                objekt.get("opcinaId").asLong(), objekt.get("naseljeId").asLong(),
                objekt.get("kucniBrojId").asLong(), objekt.get("kucniBrojNaziv").asText(),
                objekt.get("kcBroj").asText(), first);
        assertThat(reissue.countyId()).isEqualTo(objekt.get("zupanijaId").asLong());
        assertThat(reissue.street()).isEqualTo(objekt.get("ulicaNaziv").asText());

        JsonNode body = om.readTree(mvc.perform(post("/api/generateRegistrationNumber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(reissue)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rjesenjePreneseno").value(true))
                .andReturn().getResponse().getContentAsByteArray());
        String second = body.get("registrationNumber").asText();
        assertThat(second).isNotEqualTo(first);

        RnEntity secondRn = rnRepository.findById(second).orElseThrow();
        AccommodationEntity accommodation = accommodationRepository.findById(secondRn.getAccommodationId()).orElseThrow();
        assertThat(accommodation.getPreviousRn()).isEqualTo(first);
        // Nazivi iz registra (ne iz teksta zahtjeva) i katastar ponovo iz registra.
        assertThat(accommodation.getCounty()).isEqualTo(COUNTY_NAME);
        assertThat(accommodation.getCity()).isEqualTo(MUNICIPALITY_NAME);
        assertThat(accommodation.getSettlement()).isEqualTo(SETTLEMENT_NAME);
        assertThat(accommodation.getCadastralMunicipality()).isEqualTo("Lanac-T15 KO");
        assertThat(accommodation.getCadastralParcelNumber()).isEqualTo("666/3");

        CategorizationDecisionEntity copy = decisionRepository.findFirstByRnAndStatusInOrderByUploadedAtDesc(
                second, List.of(CategorizationDecisionStatus.VERIFIED)).orElseThrow();
        assertThat(copy.getSourceDecisionId()).isEqualTo(decision.getDecisionId());

        // Iskorišten: predpopuna povučenog RB-a više nije dostupna.
        prefill(first)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.code").value("REISSUE_ALREADY_USED"));
    }

    /**
     * Naselje iz druge općine iste županije: 400 ADDRESS_INCONSISTENT, a povučeni RB ostaje
     * neiskorišten (transakcija se vraća — predpopuna je i dalje 200, rješenje nije preneseno).
     */
    @Test
    void reissueWithBrokenChain_is400_andWithdrawnRnStaysUnused() throws Exception {
        String first = issue(newObjectRequest(MUNICIPALITY, SETTLEMENT, HOUSE_9, "9", null, null));
        CategorizationDecisionEntity source = decision(first);
        rnActionService.withdrawOwnByOib(first, OIB, null);

        JsonNode objekt = json(prefill(first)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rjesenje.status").value(CategorizationDecisionStatus.SUBMITTED.name())))
                .get("objekt");
        assertThat(objekt.get("kucniBrojId").asLong()).isEqualTo(HOUSE_9);

        RegistrationRequest broken = newObjectRequest(
                objekt.get("opcinaId").asLong(), OTHER_SETTLEMENT,
                objekt.get("kucniBrojId").asLong(), objekt.get("kucniBrojNaziv").asText(),
                objekt.get("kcBroj").asText(), first);
        mvc.perform(post("/api/generateRegistrationNumber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(broken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.code").value("ADDRESS_INCONSISTENT"))
                .andExpect(jsonPath("$.details.field").value("settlementId"));

        assertThat(accommodationRepository.existsByPreviousRn(first)).isFalse();
        prefill(first)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.povucenRb").value(first))
                .andExpect(jsonPath("$.objekt.naseljeId").value(SETTLEMENT));
        // Izvorno rješenje i dalje je samo uz povučeni RB.
        assertThat(decisionRepository.findById(source.getDecisionId()).orElseThrow().getRn()).isEqualTo(first);
        assertThat(decisionRepository.findById(source.getDecisionId()).orElseThrow().getStatus())
                .isEqualTo(CategorizationDecisionStatus.SUBMITTED);
    }

    // ── pomoćno ─────────────────────────────────────────────────────────────

    private void seedAddressRegistry() {
        // Tablice bez entiteta (Hibernate ih ne stvara) i stupac koji entitet ne mapira.
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rpj_dgu.postanski_brojevi (
                  id INTEGER PRIMARY KEY, broj_pu VARCHAR(10) NOT NULL, red_broj VARCHAR(10),
                  naziv_pu VARCHAR(100), naselje VARCHAR(100), zupanija VARCHAR(100))
                """);
        jdbc.execute("CREATE TABLE IF NOT EXISTS eturizam_test.ka_katastar_ko (id BIGINT PRIMARY KEY, naziv VARCHAR(255))");
        jdbc.execute("ALTER TABLE eturizam_test.ar_address ADD COLUMN IF NOT EXISTS kat_opcina_id BIGINT");
        cleanUp();

        jdbc.update("INSERT INTO rpj_dgu.zupanije (id, zu_ime, zu_rb) VALUES (?, ?, 981)", COUNTY, COUNTY_NAME);
        jdbc.update("INSERT INTO rpj_dgu.gradovi_i_opcine (id, jls_ime, jls_mb, zu_rb) VALUES (?, ?, '98101', 981)",
                MUNICIPALITY, MUNICIPALITY_NAME);
        jdbc.update("INSERT INTO rpj_dgu.gradovi_i_opcine (id, jls_ime, jls_mb, zu_rb) VALUES (?, 'Lanac-T15 Općina', '98102', 981)",
                OTHER_MUNICIPALITY);
        jdbc.update("INSERT INTO rpj_dgu.naselja (id, na_ime, na_mb, jls_mb) VALUES (?, ?, '9810101', 98101)",
                SETTLEMENT, SETTLEMENT_NAME);
        jdbc.update("INSERT INTO rpj_dgu.naselja (id, na_ime, na_mb, jls_mb) VALUES (?, 'Lanac-T15 Drugo naselje', '9810201', 98102)",
                OTHER_SETTLEMENT);
        jdbc.update("INSERT INTO rpj_dgu.postanski_brojevi (id, broj_pu, naselje, zupanija) VALUES (985001, ?, ?, ?)",
                POSTAL_CODE, SETTLEMENT_NAME, COUNTY_NAME);
        jdbc.update("INSERT INTO eturizam_test.ar_ulice (id, naziv_ulice, naselje_id) VALUES (?, ?, '9810101')",
                STREET, STREET_NAME);
        jdbc.update("INSERT INTO eturizam_test.ka_katastar_ko (id, naziv) VALUES (?, 'Lanac-T15 KO')",
                CADASTRAL_MUNICIPALITY);
        // kc_broj u obliku s prave baze: <MB katastarske općine>|<broj čestice>.
        jdbc.update("INSERT INTO eturizam_test.ar_address (id, broj, ulica_id, kc_broj, kat_opcina_id) VALUES (?, '7', ?, '300071|666/3', ?)",
                HOUSE_7, STREET, CADASTRAL_MUNICIPALITY);
        jdbc.update("INSERT INTO eturizam_test.ar_address (id, broj, ulica_id, kc_broj, kat_opcina_id) VALUES (?, '9', ?, '300071|666/3', ?)",
                HOUSE_9, STREET, CADASTRAL_MUNICIPALITY);
    }

    private String issue(RegistrationRequest request) throws Exception {
        JsonNode body = om.readTree(mvc.perform(post("/api/generateRegistrationNumber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray());
        return body.get("registrationNumber").asText();
    }

    private ResultActions prefill(String rn) throws Exception {
        return mvc.perform(get("/api/nias/registrations/{rn}/ponovni-zahtjev", rn));
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return om.readTree(actions.andReturn().getResponse().getContentAsByteArray());
    }

    private CategorizationDecisionEntity decision(String rn) {
        return decisionRepository.save(CategorizationDecisionEntity.create(
                OIB, rn, "rjesenje.pdf", "application/pdf", new byte[]{0x25, 0x50, 0x44, 0x46, 1},
                new CategorizationDecisionMetadata("Apartman Lanac", null, null, null, null, 4, null)));
    }

    /** Kao FE: id-evi razina kao stringovi, ulica i broj nazivima iz registra, uz {@code kucniBrojId}. */
    private RegistrationRequest newObjectRequest(long municipalityId, long settlementId, long houseNumberId,
                                                 String houseNumber, String kcBroj, String previousRn) {
        return new RegistrationRequest(
                OIB, "Apartman Lanac", typeId,
                COUNTY, String.valueOf(municipalityId), String.valueOf(settlementId),
                STREET_NAME, houseNumber, houseNumberId, POSTAL_CODE,
                4,
                OfferType.PRIMARY_RESIDENCE, Offering.WHOLE,
                false, "2", false, true,
                null, null, null, null, null, null, null,
                "iznajmljivac@example.com", "0991234567", null, null, kcBroj, null, previousRn);
    }
}
