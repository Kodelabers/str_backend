package com.str.backend.rn;

import com.str.backend.domain.RnStatus;
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
    void returns_valid_true_when_rn_exists_and_is_active() throws Exception {
        RnEntity entity = rnWithStatus(RnStatus.ACTIVE);
        when(rnRepository.findById(WELL_FORMED_RN)).thenReturn(Optional.of(entity));

        mvc.perform(get("/api/verify/{rn}", WELL_FORMED_RN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }

    @ParameterizedTest
    @EnumSource(value = RnStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"ACTIVE"})
    void returns_valid_false_when_rn_exists_but_status_is_not_active(RnStatus status) throws Exception {
        RnEntity entity = rnWithStatus(status);
        when(rnRepository.findById(WELL_FORMED_RN)).thenReturn(Optional.of(entity));

        mvc.perform(get("/api/verify/{rn}", WELL_FORMED_RN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
    }

    @Test
    void returns_valid_false_when_rn_does_not_exist() throws Exception {
        when(rnRepository.findById(WELL_FORMED_RN)).thenReturn(Optional.empty());

        mvc.perform(get("/api/verify/{rn}", WELL_FORMED_RN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
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
