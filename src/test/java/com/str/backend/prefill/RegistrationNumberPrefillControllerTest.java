package com.str.backend.prefill;

import com.str.backend.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@WebMvcTest(RegistrationNumberPrefillController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "app.frontend.base-url=http://localhost:3000")
class RegistrationNumberPrefillControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private RegistrationNumberPrefillService service;

    @Test
    void handoff_redirectsToFrontend_withPrefillIdQuery() throws Exception {
        UUID prefillId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(service.store(any(), any(), any(), any(), any(), any())).thenReturn(prefillId);

        mvc.perform(get("/api/registration-number-prefill")
                        .param("oib", "12345678901")
                        .param("firstName", "Ana")
                        .param("lastName", "Anić")
                        .param("addressCode", "42")
                        .param("maxBedCount", "3")
                        .param("maxGuestCount", "6"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "http://localhost:3000/registration-number?prefill=" + prefillId));
    }

    @Test
    void handoff_succeedsWithoutOptionalParams() throws Exception {
        UUID prefillId = UUID.randomUUID();
        when(service.store(any(), any(), any(), any(), any(), any())).thenReturn(prefillId);

        mvc.perform(get("/api/registration-number-prefill")
                        .param("oib", "12345678901")
                        .param("firstName", "Ana")
                        .param("lastName", "Anić"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "http://localhost:3000/registration-number?prefill=" + prefillId));
    }

    @Test
    void handoff_returns400_whenRequiredParamMissing() throws Exception {
        mvc.perform(get("/api/registration-number-prefill")
                        .param("firstName", "Ana")
                        .param("lastName", "Anić"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void get_returnsPrefillResponseJson() throws Exception {
        UUID prefillId = UUID.randomUUID();
        when(service.resolve(prefillId)).thenReturn(new RegistrationNumberPrefillResponse(
                "12345678901", "Ana", "Anić", 3, 6,
                "Grad Zagreb", "Zagreb", "Zagreb", "Ilica", "1"));

        mvc.perform(get("/api/registration-number-prefill/{id}", prefillId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oib").value("12345678901"))
                .andExpect(jsonPath("$.firstName").value("Ana"))
                .andExpect(jsonPath("$.lastName").value("Anić"))
                .andExpect(jsonPath("$.maxBedCount").value(3))
                .andExpect(jsonPath("$.maxGuestCount").value(6))
                .andExpect(jsonPath("$.countyName").value("Grad Zagreb"))
                .andExpect(jsonPath("$.municipalityName").value("Zagreb"))
                .andExpect(jsonPath("$.settlementName").value("Zagreb"))
                .andExpect(jsonPath("$.streetName").value("Ilica"))
                .andExpect(jsonPath("$.streetNumber").value("1"));
    }

    @Test
    void get_returns404_whenPrefillMissing() throws Exception {
        UUID prefillId = UUID.randomUUID();
        when(service.resolve(prefillId))
                .thenThrow(new ResourceNotFoundException("Prefill payload not found"));

        mvc.perform(get("/api/registration-number-prefill/{id}", prefillId))
                .andExpect(status().isNotFound());
    }
}
