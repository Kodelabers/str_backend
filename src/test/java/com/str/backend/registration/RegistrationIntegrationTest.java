package com.str.backend.registration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Scenario;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.registration.dto.AccommodationRequest;
import com.str.backend.registration.dto.LessorRequest;
import com.str.backend.registration.dto.RegistrationRequest;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end registration flow: HTTP POST -> enrichment (GIS/RPJ/SR) -> PDF -> eGOP filing
 * -> persist lessor/submission/accommodations -> GO validation -> RB issuance.
 * Test profile uses H2 with JPA create-drop so core mock tables are populated directly
 * via JdbcTemplate (Liquibase is disabled in test).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegistrationIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SubmissionRepository submissionRepository;
    @Autowired private LessorRepository lessorRepository;

    @BeforeEach
    void seedCoreMocks() {
        jdbc.update("DELETE FROM core.gis_parcela");
        jdbc.update("DELETE FROM core.rpj_adresa");
        jdbc.update("DELETE FROM core.sr_pravna_osoba");
        jdbc.update("INSERT INTO core.rpj_adresa (id, zupanija, grad, naselje, ulica, kucni_broj, postanski_broj) "
                + "VALUES (1, 'Grad Zagreb', 'Zagreb', 'Donji grad', 'Ilica', '1', '10000')");
        jdbc.update("INSERT INTO core.sr_pravna_osoba (oib, naziv, sjediste, zastupnici) "
                + "VALUES ('12345678901', 'Apartmani Horvat d.o.o.', 'Ilica 1, Zagreb', 'Ivan Horvat;Ana Kovac')");
        jdbc.update("INSERT INTO core.gis_parcela (id, katastarska_opcina, broj_cestice, povrsina_m2, namjena, legalan_objekt) "
                + "VALUES (1, 'Donji grad', '123/4', 450, 'Stambena', TRUE)");
    }

    @Test
    void full_registration_flow_returns_rb_and_stores_pdf() throws Exception {
        RegistrationRequest req = baseRequest();

        MvcResult posted = mvc.perform(post("/api/registracija")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scenario").value("S2_NEW_UNIT_EXTERNAL"))
                .andExpect(jsonPath("$.assignedRbs").isArray())
                .andExpect(jsonPath("$.assignedRbs[0].rn").exists())
                .andReturn();

        ObjectNode body = (ObjectNode) om.readTree(posted.getResponse().getContentAsByteArray());
        String rb = body.get("assignedRbs").get(0).get("rn").asText();
        UUID lessorId = UUID.fromString(body.get("lessorId").asText());

        assertThat(rb).matches("HR\\d{8}");

        LessorEntity lessor = lessorRepository.findById(lessorId).orElseThrow();
        // SR enrichment should have populated legal entity name from mock SR.
        assertThat(lessor.getLegalEntityName()).isEqualTo("Apartmani Horvat d.o.o.");
        assertThat(lessor.getLegalRepresentativeName()).isEqualTo("Ivan Horvat");

        List<SubmissionEntity> submissions = submissionRepository.findByLessorId(lessorId);
        assertThat(submissions).hasSize(1);
        SubmissionEntity submission = submissions.get(0);
        // eGOP stub generates KLASA 334-01/.. URBROJ 529-06/..
        assertThat(submission.getFilingNumber()).contains("KLASA: 334-01");
        assertThat(submission.getFilingNumber()).contains("URBROJ: 529-06");
        assertThat(submission.getFilingDate()).isNotNull();
        assertThat(submission.getDocumentLink()).startsWith("egop://");
        assertThat(submission.getPdfContent()).isNotNull();
        assertThat(submission.getPdfContent()).startsWith(new byte[] {'%', 'P', 'D', 'F'});

        mvc.perform(get("/api/registracija/{id}/pdf", submission.getSubmissionId()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(submission.getPdfContent()));
    }

    @Test
    void rejected_validation_returns_422() throws Exception {
        RegistrationRequest req = baseRequest();
        // GO-3 rejects anything not legalized.
        req.getAccommodations().get(0).setLegalized(false);

        mvc.perform(post("/api/registracija")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.step").value("GO-3"));
    }

    private RegistrationRequest baseRequest() {
        RegistrationRequest req = new RegistrationRequest();
        req.setScenario(Scenario.S2_NEW_UNIT_EXTERNAL);
        req.setCompetentAuthorityId(null);

        LessorRequest lr = new LessorRequest();
        lr.setFirstName("Marko");
        lr.setLastName("Maric");
        lr.setEmail("marko@example.com");
        lr.setStreet("Ilica");
        lr.setStreetNumber("1");
        lr.setPlace("Zagreb");
        lr.setCounty("Grad Zagreb");
        lr.setRepresentativeOib("12345678901");
        req.setLessor(lr);

        AccommodationRequest ar = new AccommodationRequest();
        ar.setCounty("Grad Zagreb");
        ar.setCity("Zagreb");
        ar.setStreet("Ilica");
        ar.setStreetNumber("1");
        ar.setCadastralMunicipality("Donji grad");
        ar.setCadastralParcelNumber("123/4");
        ar.setMaxBeds(2);
        ar.setMaxGuests(4);
        ar.setOfferType(OfferType.FULL);
        // building=false, apartments=false → GO-2 exits early (not building/apartments),
        // GO-1 can mark host, GO-4 not required, GO-5 no core → passed
        ar.setBuilding(false);
        ar.setApartments(false);
        ar.setLegalized(true);
        req.setAccommodations(List.of(ar));
        return req;
    }
}
