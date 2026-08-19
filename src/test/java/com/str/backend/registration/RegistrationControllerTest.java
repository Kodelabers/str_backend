package com.str.backend.registration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.str.backend.auth.nias.NiasOibResolver;
import com.str.backend.auth.role.StrRoles;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.exception.ValidationRejectedException;
import com.str.backend.registration.dto.RegistrationRequest;
import com.str.backend.registration.dto.RegistrationResponse;
import com.str.backend.request.SubmissionEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;
import java.time.Instant;
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

@ActiveProfiles("test")
@WebMvcTest(RegistrationController.class)
@AutoConfigureMockMvc(addFilters = false)
class RegistrationControllerTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;

    @MockBean private RegistrationService service;
    @MockBean private NiasOibResolver niasOibResolver;

    private static Authentication internalAuth() {
        return new UsernamePasswordAuthenticationToken("interni", null,
                List.of(new SimpleGrantedAuthority(StrRoles.ROLE_INTERNAL)));
    }

    @Test
    void post_returns_201_with_registration_number() throws Exception {
        UUID submissionId = UUID.randomUUID();
        RegistrationResponse resp = new RegistrationResponse("HR120001000000000001", submissionId);
        when(service.generateRegistrationNumber(any())).thenReturn(resp);

        mvc.perform(post("/api/generateRegistrationNumber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.registrationNumber").value("HR120001000000000001"))
                .andExpect(jsonPath("$.submissionId").value(submissionId.toString()));
    }

    @Test
    void post_returns_400_when_payload_invalid() throws Exception {
        RegistrationRequest invalid = withMaxBeds(validRequest(), 0);

        mvc.perform(post("/api/generateRegistrationNumber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(invalid)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void post_returns_400_when_oib_invalid() throws Exception {
        RegistrationRequest invalid = withOib(validRequest(), "abc");

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
        SubmissionEntity s = submissionWithPdf(id, "334-01/26-01/1001", pdf);
        when(service.getSubmissionForPdf(id)).thenReturn(s);

        mvc.perform(get("/api/generateRegistrationNumber/{id}/pdf", id).principal(internalAuth()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(".pdf")))
                .andExpect(content().bytes(pdf));
    }

    @Test
    void get_pdf_returns_404_when_submission_missing() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getSubmissionForPdf(id)).thenThrow(new ResourceNotFoundException("submission not found"));

        mvc.perform(get("/api/generateRegistrationNumber/{id}/pdf", id).principal(internalAuth()))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_pdf_returns_404_when_pdf_not_stored() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getSubmissionForPdf(id)).thenThrow(new ResourceNotFoundException("error.pdf.not.stored"));

        mvc.perform(get("/api/generateRegistrationNumber/{id}/pdf", id).principal(internalAuth()))
                .andExpect(status().isNotFound());
    }

    private RegistrationRequest validRequest() {
        return new RegistrationRequest(
                "12312312316", "Apartman Sunce", null,
                2L, "Split", null,
                "Ulica kralja Tomislava", "14a", null, null,
                4,
                OfferType.PRIMARY_RESIDENCE, Offering.WHOLE,
                false, null, false, true,
                null, null, null, null, null, null, null);
    }

    private RegistrationRequest withMaxBeds(RegistrationRequest r, int maxBeds) {
        return new RegistrationRequest(
                r.oib(), r.name(), r.typeId(),
                r.countyId(), r.cityId(), r.settlementId(),
                r.street(), r.streetNumber(), r.houseNumberCode(), r.postalCode(),
                maxBeds,
                r.offerType(), r.offering(),
                r.building(), r.floor(), r.apartments(), r.legalized(),
                r.lessorResidence(), r.coOwnerConsent(), r.consentDate(),
                r.consentWithdrawalDate(), r.host(), r.confirmDuplicateLocation(), r.facilityId());
    }

    private RegistrationRequest withOib(RegistrationRequest r, String oib) {
        return new RegistrationRequest(
                oib, r.name(), r.typeId(),
                r.countyId(), r.cityId(), r.settlementId(),
                r.street(), r.streetNumber(), r.houseNumberCode(), r.postalCode(),
                r.maxBeds(),
                r.offerType(), r.offering(),
                r.building(), r.floor(), r.apartments(), r.legalized(),
                r.lessorResidence(), r.coOwnerConsent(), r.consentDate(),
                r.consentWithdrawalDate(), r.host(), r.confirmDuplicateLocation(), r.facilityId());
    }

    private SubmissionEntity submissionWithPdf(UUID id, String filingNumber, byte[] pdf) {
        try {
            var ctor = SubmissionEntity.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            SubmissionEntity s = ctor.newInstance();
            setField(s, "submissionId", id);
            setField(s, "filingNumber", filingNumber);
            setField(s, "createdAt", Instant.now());
            setField(s, "updatedAt", Instant.now());
            if (pdf != null) s.setPdfContent(pdf);
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
