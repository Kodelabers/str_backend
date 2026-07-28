package com.str.backend.lookup;

import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.address.CountyRepository;
import com.str.backend.address.MunicipalityRepository;
import com.str.backend.guest.GuestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@WebMvcTest(PlatformLookupController.class)
@AutoConfigureMockMvc(addFilters = false)
class PlatformLookupControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean private OnlinePlatformRepository platformRepository;
    @MockBean private AccommodationRepository accommodationRepository;
    @MockBean private GuestRepository guestRepository;
    // Backing the /municipalities lookup. Absent, the slice fails to build the controller and
    // every test in the class errors out on context load, whatever it was actually asserting.
    @MockBean private CountyRepository countyRepository;
    @MockBean private MunicipalityRepository municipalityRepository;

    /**
     * STR-3.2: the guest-country filter is unusable without these options, and they must come
     * from the reported guests — the address registry's country list is non-EU only.
     */
    @Test
    void guestCountries_returnsDistinctReportedCountries() throws Exception {
        when(guestRepository.findDistinctCountriesOrderByName())
                .thenReturn(List.of("Austrija", "Italija", "Njemačka"));

        mvc.perform(get("/api/lookups/guest-countries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value("Austrija"))
                .andExpect(jsonPath("$[0].naziv").value("Austrija"))
                .andExpect(jsonPath("$[2].naziv").value("Njemačka"));
    }

    /** No reported guests yet must render an empty dropdown, not an error. */
    @Test
    void guestCountries_returnsEmptyListWhenNoGuestsReported() throws Exception {
        when(guestRepository.findDistinctCountriesOrderByName()).thenReturn(List.of());

        mvc.perform(get("/api/lookups/guest-countries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
