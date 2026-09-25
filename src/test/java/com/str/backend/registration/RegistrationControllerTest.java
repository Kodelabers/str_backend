package com.str.backend.registration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.str.backend.captcha.AltchaService;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.exception.ValidationRejectedException;
import com.str.backend.registration.dto.RegistrationRequest;
import com.str.backend.registration.dto.RegistrationResponse;
import com.str.backend.request.SubmissionEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;
import java.time.Instant;
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
    @MockBean private AltchaService altchaService;

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

    /** Kontakt je obvezan od 10.09.2026. — bez njega obavijest o RB-u nema kamo. */
    @Test
    void post_returns_400_when_contact_missing() throws Exception {
        RegistrationRequest bezKontakta = withContact(validRequest(), null, null);

        mvc.perform(post("/api/generateRegistrationNumber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(bezKontakta)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void post_returns_400_when_contact_email_malformed() throws Exception {
        RegistrationRequest loseMail = withContact(validRequest(), "nije-mail", "0991234567");

        mvc.perform(post("/api/generateRegistrationNumber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(loseMail)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void post_returns_400_when_payload_invalid() throws Exception {
        RegistrationRequest invalid = withMaxBeds(validRequest(), 0);

        mvc.perform(post("/api/generateRegistrationNumber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(invalid)))
                .andExpect(status().isBadRequest());
    }

    /** Kat je obavezan i isključivo cijeli broj (stavka 2) — riječi i kratice se odbijaju. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "prizemlje", "P", "1.5", "100", "-10", "--1", "+1"})
    void post_returns_400_when_floor_not_integer_in_range(String floor) throws Exception {
        mvc.perform(post("/api/generateRegistrationNumber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(withFloor(validRequest(), floor))))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "-1", "-9", "99"})
    void post_accepts_integer_floor(String floor) throws Exception {
        when(service.generateRegistrationNumber(any()))
                .thenReturn(new RegistrationResponse("HR120001000000000001", UUID.randomUUID()));

        mvc.perform(post("/api/generateRegistrationNumber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(withFloor(validRequest(), floor))))
                .andExpect(status().isCreated());
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
        when(service.getSubmissionForPdf(id)).thenThrow(new ResourceNotFoundException("submission not found"));

        mvc.perform(get("/api/generateRegistrationNumber/{id}/pdf", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_pdf_returns_404_when_pdf_not_stored() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getSubmissionForPdf(id)).thenThrow(new ResourceNotFoundException("error.pdf.not.stored"));

        mvc.perform(get("/api/generateRegistrationNumber/{id}/pdf", id))
                .andExpect(status().isNotFound());
    }

    private RegistrationRequest validRequest() {
        return new RegistrationRequest(
                "12312312316", "Apartman Sunce", null,
                2L, "Split", null,
                "Ulica kralja Tomislava", "14a", null, null,
                4,
                OfferType.PRIMARY_RESIDENCE, Offering.WHOLE,
                false, "2", false, true,
                null, null, null, null, null, null, null,
                "iznajmljivac@example.com", "0991234567", null, null, null);
    }

    private RegistrationRequest withContact(RegistrationRequest r, String email, String mobitel) {
        return new RegistrationRequest(
                r.oib(), r.name(), r.typeId(),
                r.countyId(), r.cityId(), r.settlementId(),
                r.street(), r.streetNumber(), r.kucniBrojId(), r.postalCode(),
                r.maxBeds(),
                r.offerType(), r.offering(),
                r.building(), r.floor(), r.apartments(), r.legalized(),
                r.lessorResidence(), r.coOwnerConsent(), r.consentDate(),
                r.consentWithdrawalDate(), r.host(), r.confirmDuplicateLocation(), r.facilityId(),
                email, mobitel, r.kontaktTelefon(), r.kontaktOsoba(),
                r.kcBroj());
    }

    private RegistrationRequest withMaxBeds(RegistrationRequest r, int maxBeds) {
        return new RegistrationRequest(
                r.oib(), r.name(), r.typeId(),
                r.countyId(), r.cityId(), r.settlementId(),
                r.street(), r.streetNumber(), r.kucniBrojId(), r.postalCode(),
                maxBeds,
                r.offerType(), r.offering(),
                r.building(), r.floor(), r.apartments(), r.legalized(),
                r.lessorResidence(), r.coOwnerConsent(), r.consentDate(),
                r.consentWithdrawalDate(), r.host(), r.confirmDuplicateLocation(), r.facilityId(),
                r.kontaktEmail(), r.kontaktMobitel(), r.kontaktTelefon(), r.kontaktOsoba(),
                r.kcBroj());
    }

    private RegistrationRequest withFloor(RegistrationRequest r, String floor) {
        return new RegistrationRequest(
                r.oib(), r.name(), r.typeId(),
                r.countyId(), r.cityId(), r.settlementId(),
                r.street(), r.streetNumber(), r.kucniBrojId(), r.postalCode(),
                r.maxBeds(),
                r.offerType(), r.offering(),
                r.building(), floor, r.apartments(), r.legalized(),
                r.lessorResidence(), r.coOwnerConsent(), r.consentDate(),
                r.consentWithdrawalDate(), r.host(), r.confirmDuplicateLocation(), r.facilityId(),
                r.kontaktEmail(), r.kontaktMobitel(), r.kontaktTelefon(), r.kontaktOsoba(),
                r.kcBroj());
    }

    private RegistrationRequest withOib(RegistrationRequest r, String oib) {
        return new RegistrationRequest(
                oib, r.name(), r.typeId(),
                r.countyId(), r.cityId(), r.settlementId(),
                r.street(), r.streetNumber(), r.kucniBrojId(), r.postalCode(),
                r.maxBeds(),
                r.offerType(), r.offering(),
                r.building(), r.floor(), r.apartments(), r.legalized(),
                r.lessorResidence(), r.coOwnerConsent(), r.consentDate(),
                r.consentWithdrawalDate(), r.host(), r.confirmDuplicateLocation(), r.facilityId(),
                r.kontaktEmail(), r.kontaktMobitel(), r.kontaktTelefon(), r.kontaktOsoba(),
                r.kcBroj());
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
