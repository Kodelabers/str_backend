package com.str.backend.registration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.str.backend.domain.County;
import com.str.backend.domain.OfferType;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.exception.ValidationRejectedException;
import com.str.backend.registration.dto.RegistrationRequest;
import com.str.backend.registration.dto.RegistrationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
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
    @Test
    void post_returns_201_with_assigned_rb() throws Exception {
        UUID lessorId = UUID.randomUUID();
        UUID accommodationId = UUID.randomUUID();
        RegistrationResponse resp = new RegistrationResponse(
                lessorId,
                List.of(new RegistrationResponse.AssignedRb(accommodationId, "HR120001000000000001")));
        when(service.generateRegistrationNumber(any())).thenReturn(resp);

        mvc.perform(post("/api/generateRegistrationNumber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lessorId").value(lessorId.toString()))
                .andExpect(jsonPath("$.assignedRbs[0].rn").value("HR120001000000000001"));
    }

    @Test
    void post_returns_400_when_payload_invalid() throws Exception {
        RegistrationRequest invalid = validRequest();
        invalid.setMaxBeds(0); // violates @Min(1)

        mvc.perform(post("/api/generateRegistrationNumber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(invalid)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void post_returns_422_when_validation_rejected() throws Exception {
        when(service.generateRegistrationNumber(any()))
                .thenThrow(new ValidationRejectedException("GO-3", "objekt nije legaliziran"));

        mvc.perform(post("/api/generateRegistrationNumber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(validRequest())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.step").value("GO-3"));
    }

    @Test
    void get_pdf_streams_stored_bytes() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] pdf = "%PDF-1.4 fake".getBytes();
        when(service.getPdfContent(id)).thenReturn(
                ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"submission-334-01_26-01_1001.pdf\"")
                        .contentType(MediaType.APPLICATION_PDF)
                        .body(pdf));

        mvc.perform(get("/api/generateRegistrationNumber/{id}/pdf", id))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(".pdf")))
                .andExpect(content().bytes(pdf));
    }

    @Test
    void get_pdf_returns_404_when_submission_missing() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getPdfContent(id)).thenThrow(new ResourceNotFoundException("submission not found"));

        mvc.perform(get("/api/generateRegistrationNumber/{id}/pdf", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_pdf_returns_404_when_pdf_not_stored() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getPdfContent(id)).thenThrow(new ResourceNotFoundException("error.pdf.not.stored"));

        mvc.perform(get("/api/generateRegistrationNumber/{id}/pdf", id))
                .andExpect(status().isNotFound());
    }

    private RegistrationRequest validRequest() {
        RegistrationRequest req = new RegistrationRequest();
        req.setOib("12312312316");
        req.setName("Apartman Sunce");
        req.setCounty(County.SPLITSKO_DALMATINSKA);
        req.setCityId("Split");
        req.setStreet("Ulica kralja Tomislava");
        req.setStreetNumber("14a");
        req.setMaxBeds(4);
        req.setMaxGuests(6);
        req.setOfferType(OfferType.RESIDENCE);
        return req;
    }
}
