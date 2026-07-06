package com.str.backend.rn;

import com.str.backend.domain.RnStatus;
import com.str.backend.rn.dto.RnPublicView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@WebMvcTest(VerifyController.class)
@AutoConfigureMockMvc(addFilters = false)
class VerifyControllerTest {

    private static final String WELL_FORMED_RN = "HR180000123456789001";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private RnRepository rnRepository;

    @Test
    void returns_active_with_public_data_when_active() throws Exception {
        RnEntity entity = rnWithStatus(RnStatus.ACTIVE);
        when(rnRepository.findById(WELL_FORMED_RN)).thenReturn(Optional.of(entity));
        when(rnRepository.findPublicView(WELL_FORMED_RN)).thenReturn(Optional.of(new RnPublicView(
                WELL_FORMED_RN, "Apartman More", "3 zvjezdice", "Korzo", "2", "Rijeka",
                "Objekti u domaćinstvu", "Apartman")));

        mvc.perform(get("/api/verify/{rn}", WELL_FORMED_RN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.registrationNumber").value(WELL_FORMED_RN))
                .andExpect(jsonPath("$.accommodationName").value("Apartman More"))
                .andExpect(jsonPath("$.category").value("3 zvjezdice"))
                .andExpect(jsonPath("$.address").value("Korzo 2, Rijeka"))
                .andExpect(jsonPath("$.group").value("Objekti u domaćinstvu"))
                .andExpect(jsonPath("$.type").value("Apartman"));
    }

    @Test
    void returns_suspended_status_without_object_data() throws Exception {
        RnEntity entity = rnWithStatus(RnStatus.SUSPENDED);
        when(rnRepository.findById(WELL_FORMED_RN)).thenReturn(Optional.of(entity));

        mvc.perform(get("/api/verify/{rn}", WELL_FORMED_RN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.registrationNumber").doesNotExist())
                .andExpect(jsonPath("$.accommodationName").doesNotExist());
    }

    @ParameterizedTest
    @EnumSource(value = RnStatus.class, names = {"WITHDRAWN", "IN_PROCESSING"})
    void returns_valid_false_for_withdrawn_or_in_processing(RnStatus status) throws Exception {
        RnEntity entity = rnWithStatus(status);
        when(rnRepository.findById(WELL_FORMED_RN)).thenReturn(Optional.of(entity));

        // A withdrawn RN must be indistinguishable from a non-existent one (čl. 4. st. 5.):
        // valid=false and no status field leaked.
        mvc.perform(get("/api/verify/{rn}", WELL_FORMED_RN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.status").doesNotExist());
    }

    @Test
    void returns_valid_false_when_rn_does_not_exist() throws Exception {
        when(rnRepository.findById(WELL_FORMED_RN)).thenReturn(Optional.empty());

        mvc.perform(get("/api/verify/{rn}", WELL_FORMED_RN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.status").doesNotExist());
    }

    @Test
    void returns_400_when_rn_format_is_invalid() throws Exception {
        mvc.perform(get("/api/verify/{rn}", "INVALID"))
                .andExpect(status().isBadRequest());
    }

    private static RnEntity rnWithStatus(RnStatus status) {
        RnEntity entity = mock(RnEntity.class);
        when(entity.getStatus()).thenReturn(status);
        return entity;
    }
}
