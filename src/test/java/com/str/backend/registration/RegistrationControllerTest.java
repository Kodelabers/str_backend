package com.str.backend.registration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Scenario;
import com.str.backend.exception.ValidationRejectedException;
import com.str.backend.registration.dto.AccommodationRequest;
import com.str.backend.registration.dto.LessorRequest;
import com.str.backend.registration.dto.RegistrationRequest;
import com.str.backend.registration.dto.RegistrationResponse;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RegistrationController.class)
class RegistrationControllerTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;

    @MockBean private RegistrationService service;
    @MockBean private SubmissionRepository submissionRepository;

    @Test
    void post_returns_201_with_assigned_rb() throws Exception {
        UUID lessorId = UUID.randomUUID();
        UUID accommodationId = UUID.randomUUID();
        RegistrationResponse resp = new RegistrationResponse(
                Scenario.S2_NEW_UNIT_EXTERNAL, lessorId,
                List.of(new RegistrationResponse.AssignedRb(accommodationId, "HR12345678")));
        when(service.register(any())).thenReturn(resp);

        mvc.perform(post("/api/registracija")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scenario").value("S2_NEW_UNIT_EXTERNAL"))
                .andExpect(jsonPath("$.lessorId").value(lessorId.toString()))
                .andExpect(jsonPath("$.assignedRbs[0].rn").value("HR12345678"));
    }

    @Test
    void post_returns_400_when_payload_invalid() throws Exception {
        RegistrationRequest invalid = validRequest();
        invalid.getLessor().setEmail("not-an-email");

        mvc.perform(post("/api/registracija")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(invalid)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void post_returns_422_when_validation_rejected() throws Exception {
        when(service.register(any()))
                .thenThrow(new ValidationRejectedException("GO-3", "objekt nije legaliziran"));

        mvc.perform(post("/api/registracija")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(validRequest())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.step").value("GO-3"));
    }

    @Test
    void get_pdf_streams_stored_bytes() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] pdf = "%PDF-1.4 fake".getBytes();
        SubmissionEntity s = submissionWithPdf(id, "334-01/26-01/1001 / 529-06/26-1", pdf);
        when(submissionRepository.findById(id)).thenReturn(Optional.of(s));

        mvc.perform(get("/api/registracija/{id}/pdf", id))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(".pdf")))
                .andExpect(content().bytes(pdf));
    }

    @Test
    void get_pdf_returns_404_when_submission_missing() throws Exception {
        UUID id = UUID.randomUUID();
        when(submissionRepository.findById(id)).thenReturn(Optional.empty());

        mvc.perform(get("/api/registracija/{id}/pdf", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_pdf_returns_404_when_pdf_not_stored() throws Exception {
        UUID id = UUID.randomUUID();
        SubmissionEntity s = submissionWithPdf(id, "334-01/26-01/1001", null);
        when(submissionRepository.findById(id)).thenReturn(Optional.of(s));

        mvc.perform(get("/api/registracija/{id}/pdf", id))
                .andExpect(status().isNotFound());
    }

    private RegistrationRequest validRequest() {
        RegistrationRequest req = new RegistrationRequest();
        req.setScenario(Scenario.S2_NEW_UNIT_EXTERNAL);
        req.setCompetentAuthorityId(1L);

        LessorRequest lr = new LessorRequest();
        lr.setFirstName("Marko");
        lr.setLastName("Maric");
        lr.setEmail("marko@example.com");
        lr.setStreet("Ilica");
        lr.setStreetNumber("1");
        lr.setPlace("Zagreb");
        lr.setCounty("Grad Zagreb");
        req.setLessor(lr);

        AccommodationRequest ar = new AccommodationRequest();
        ar.setCounty("Grad Zagreb");
        ar.setCity("Zagreb");
        ar.setStreet("Ilica");
        ar.setStreetNumber("1");
        ar.setMaxBeds(2);
        ar.setMaxGuests(4);
        ar.setOfferType(OfferType.FULL);
        ar.setBuilding(true);
        ar.setApartments(true);
        ar.setLegalized(true);
        req.setAccommodations(List.of(ar));
        return req;
    }

    private SubmissionEntity submissionWithPdf(UUID id, String filingNumber, byte[] pdf) {
        try {
            java.lang.reflect.Constructor<SubmissionEntity> ctor =
                    SubmissionEntity.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            SubmissionEntity s = ctor.newInstance();
            setField(s, "submissionId", id);
            setField(s, "filingNumber", filingNumber);
            setField(s, "createdAt", Instant.now());
            setField(s, "updatedAt", Instant.now());
            if (pdf != null) {
                s.setPdfContent(pdf);
            }
            return s;
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
}
