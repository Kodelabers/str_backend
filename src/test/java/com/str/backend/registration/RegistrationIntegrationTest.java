package com.str.backend.registration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.str.backend.address.CountyEntity;
import com.str.backend.address.CountyRepository;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.registration.dto.RegistrationRequest;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.str.StrLessorLookupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

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

    @MockBean private StrLessorLookupService strLessorLookupService;
    @MockBean private CountyRepository countyRepository;

    @BeforeEach
    void setupMocks() {
        when(strLessorLookupService.resolveLessor(anyString()))
                .thenAnswer(inv -> LessorEntity.create("PERO", "PERIĆ",
                        "Ilica", "1", "Zagreb", "Grad Zagreb", "pero.peric@example.hr"));

        CountyEntity county = buildCountyEntity(2L, "Splitsko-dalmatinska županija");
        when(countyRepository.findById(2L)).thenReturn(Optional.of(county));
    }

    @Test
    void generates_rn_and_stores_submission_with_pdf() throws Exception {
        MvcResult result = mvc.perform(post("/api/generateRegistrationNumber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(baseRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.registrationNumber").exists())
                .andExpect(jsonPath("$.submissionId").exists())
                .andReturn();

        ObjectNode body = (ObjectNode) om.readTree(result.getResponse().getContentAsByteArray());
        String rn = body.get("registrationNumber").asText();
        UUID submissionId = UUID.fromString(body.get("submissionId").asText());

        assertThat(rn).matches("HR[0-9A-Fa-f]{18}");

        SubmissionEntity submission = submissionRepository.findById(submissionId).orElseThrow();
        assertThat(lessorRepository.findById(submission.getLessorId())).isPresent();
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
        UUID submissionId = UUID.fromString(body.get("submissionId").asText());
        SubmissionEntity submission = submissionRepository.findById(submissionId).orElseThrow();

        mvc.perform(get("/api/generateRegistrationNumber/{id}/pdf", submissionId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(submission.getPdfContent()));
    }



    private CountyEntity buildCountyEntity(Long id, String name) {
        try {
            var ctor = CountyEntity.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            CountyEntity c = ctor.newInstance();
            setField(c, "id", id);
            setField(c, "name", name);
            setField(c, "zuRb", id.intValue());
            return c;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private RegistrationRequest baseRequest() {
        return new RegistrationRequest(
                "12312312316", "Apartman Sunce", null,
                2L, "Split", null,
                "Ulica kralja Tomislava", "14a", "21000",
                4, 6,
                OfferType.PRIMARY_RESIDENCE, Offering.WHOLE,
                false, null, false, true,
                null, null, null, null, null);
    }
}
