package com.str.backend.address;

import com.str.backend.address.dto.CountryResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code namjena} on {@code GET /api/address/countries}: default residence, opt-in seat. */
@ActiveProfiles("test")
@WebMvcTest(AddressLookupController.class)
@AutoConfigureMockMvc(addFilters = false)
class AddressLookupControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private AddressLookupService service;

    /** Existing callers pass no parameter and must keep getting the country-of-residence list. */
    @Test
    void countries_withoutPurpose_defaultsToResidence() throws Exception {
        when(service.findCountries(null, CountryPurpose.PREBIVALISTE))
                .thenReturn(List.of(new CountryResponse(1L, "Srbija", "RS")));

        mvc.perform(get("/api/address/countries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Srbija"));

        verify(service).findCountries(null, CountryPurpose.PREBIVALISTE);
    }

    @Test
    void countries_forSeat_passesSeatPurpose() throws Exception {
        when(service.findCountries("s", CountryPurpose.SJEDISTE)).thenReturn(List.of());

        mvc.perform(get("/api/address/countries").param("q", "s").param("namjena", "SJEDISTE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(service).findCountries("s", CountryPurpose.SJEDISTE);
    }

    @Test
    void countries_unknownPurpose_is400() throws Exception {
        mvc.perform(get("/api/address/countries").param("namjena", "NESTO"))
                .andExpect(status().isBadRequest());

        verify(service, never()).findCountries(any(), any());
    }
}
