package com.str.backend.rn;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@WebMvcTest(RnController.class)
@AutoConfigureMockMvc(addFilters = false)
class RnDocumentControllerTest {

    private static final String RN = "HR180000123456789001";

    @Autowired
    private MockMvc mvc;

    @MockBean private RnService service;
    @MockBean private RnMapper mapper;
    @MockBean private RnDocumentService documentService;

    @Test
    void document_returnsPdf() throws Exception {
        when(documentService.generate(eq(RN), eq(RnDocumentType.NALOG_SUSPENZIJA), any()))
                .thenReturn(new byte[]{1, 2, 3});

        mvc.perform(get("/api/rn/{rn}/documents/{tip}", RN, "nalog-suspenzija"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"));
    }

    @Test
    void document_unknownType_returns400() throws Exception {
        mvc.perform(get("/api/rn/{rn}/documents/{tip}", RN, "nepostojeci-akt"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void document_invalidRnFormat_returns400() throws Exception {
        mvc.perform(get("/api/rn/{rn}/documents/{tip}", "INVALID", "nalog-suspenzija"))
                .andExpect(status().isBadRequest());
    }
}
