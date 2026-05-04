package com.str.backend.registration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.str.backend.domain.OfferType;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.registration.dto.RegistrationRequest;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
 * End-to-end: POST /api/generateRegistrationNumber → GO validation → eGOP stub → RN issuance.
 * Uses H2 in-memory DB with JPA create-drop; stub clients (GIS/RPJ/SR/MPGI/eGOP) are active.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegistrationIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private SubmissionRepository submissionRepository;
    @Autowired private LessorRepository lessorRepository;

    @Test
    void generates_rn_and_stores_submission_with_pdf() throws Exception {
        MvcResult result = mvc.perform(post("/api/generateRegistrationNumber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(baseRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assignedRbs").isArray())
                .andExpect(jsonPath("$.assignedRbs[0].rn").exists())
                .andReturn();

        ObjectNode body = (ObjectNode) om.readTree(result.getResponse().getContentAsByteArray());
        String rn = body.get("assignedRbs").get(0).get("rn").asText();
        UUID lessorId = UUID.fromString(body.get("lessorId").asText());

        assertThat(rn).matches("HR\\d{8}");
        assertThat(lessorRepository.findById(lessorId)).isPresent();

        List<SubmissionEntity> submissions = submissionRepository.findByLessorId(lessorId);
        assertThat(submissions).hasSize(1);
        SubmissionEntity submission = submissions.get(0);
        assertThat(submission.getFilingNumber()).isNotBlank();
        assertThat(submission.getDocumentLink()).startsWith("egop://");
        assertThat(submission.getPdfContent()).isNotNull();
        assertThat(submission.getPdfContent()).startsWith(new byte[]{'%', 'P', 'D', 'F'});
    }

    @Test
    void pdf_endpoint_returns_stored_pdf() throws Exception {
        MvcResult postResult = mvc.perform(post("/api/generateRegistrationNumber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(baseRequest())))
                .andExpect(status().isCreated())
                .andReturn();

        ObjectNode body = (ObjectNode) om.readTree(postResult.getResponse().getContentAsByteArray());
        UUID lessorId = UUID.fromString(body.get("lessorId").asText());
        SubmissionEntity submission = submissionRepository.findByLessorId(lessorId).get(0);

        mvc.perform(get("/api/generateRegistrationNumber/{id}/pdf", submission.getSubmissionId()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(submission.getPdfContent()));
    }

    @Test
    void returns_422_when_accommodation_not_legalized() throws Exception {
        RegistrationRequest req = baseRequest();
        req.setLegalized(false);

        mvc.perform(post("/api/generateRegistrationNumber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.step").value("GO-3"));
    }

    private RegistrationRequest baseRequest() {
        RegistrationRequest req = new RegistrationRequest();
        req.setName("Apartman Sunce");
        req.setCountyId("Splitsko-dalmatinska");
        req.setCityId("Split");
        req.setStreet("Ulica kralja Tomislava");
        req.setStreetNumber("14a");
        req.setPostalCode("21000 Split");
        req.setFloor(2);
        req.setMaxBeds(4);
        req.setMaxGuests(6);
        req.setOfferType(OfferType.FULL);
        req.setBuilding(false);
        req.setLegalized(true);
        return req;
    }
}
