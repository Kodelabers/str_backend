package com.str.backend.rn;

import com.str.backend.auth.LessorPrincipal;
import com.str.backend.document.StrDocumentService;
import com.str.backend.document.StrDocumentType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Filteri su isključeni ({@code addFilters = false}), pa {@code SecurityConfig} ovdje ne
 * sudjeluje — provjera prijave je pokrivena u {@code RnDocumentAccessTest}. Ovdje se testira
 * ono što radi sam kontroler: razrješenje sluga i provjera vlasništva.
 */
@ActiveProfiles("test")
@WebMvcTest(RnController.class)
@AutoConfigureMockMvc(addFilters = false)
class RnDocumentControllerTest {

    private static final String RN = "HR180000123456789001";

    @Autowired
    private MockMvc mvc;

    @MockBean private RnService service;
    @MockBean private RnMapper mapper;
    @MockBean private StrDocumentService documentService;
    @MockBean private RnRepository rnRepository;

    @Test
    void document_returnsPdf() throws Exception {
        when(documentService.render(eq(StrDocumentType.SUSPENZIJA), eq(RN), any()))
                .thenReturn(new byte[]{1, 2, 3});

        mvc.perform(get("/api/rn/{rn}/documents/{tip}", RN, "suspenzija")
                        .principal(internalUser()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"));
    }

    /** Stari slugovi iz Knjige testiranja moraju i dalje raditi — linkovi su već podijeljeni. */
    @Test
    void document_legacySlug_stillResolves() throws Exception {
        when(documentService.render(eq(StrDocumentType.SUSPENZIJA), eq(RN), any()))
                .thenReturn(new byte[]{1, 2, 3});

        mvc.perform(get("/api/rn/{rn}/documents/{tip}", RN, "nalog-suspenzija")
                        .principal(internalUser()))
                .andExpect(status().isOk());
    }

    /** Iznajmljivač ne smije doći do akta o tuđem RB-u; postojanje se ne otkriva. */
    @Test
    void document_foreignRn_returns404_andDoesNotRender() throws Exception {
        LessorPrincipal principal = mock(LessorPrincipal.class);
        UUID lessorId = UUID.randomUUID();
        when(principal.getLessorId()).thenReturn(lessorId);
        when(rnRepository.isOwnedByLessor(RN, lessorId)).thenReturn(false);

        mvc.perform(get("/api/rn/{rn}/documents/{tip}", RN, "suspenzija")
                        .principal(new UsernamePasswordAuthenticationToken(principal, null)))
                .andExpect(status().isNotFound());

        verify(documentService, never()).render(any(), any(), any());
    }

    @Test
    void document_ownRn_isAllowedForLessor() throws Exception {
        LessorPrincipal principal = mock(LessorPrincipal.class);
        UUID lessorId = UUID.randomUUID();
        when(principal.getLessorId()).thenReturn(lessorId);
        when(rnRepository.isOwnedByLessor(RN, lessorId)).thenReturn(true);
        when(documentService.render(eq(StrDocumentType.SUSPENZIJA), eq(RN), any()))
                .thenReturn(new byte[]{1});

        mvc.perform(get("/api/rn/{rn}/documents/{tip}", RN, "suspenzija")
                        .principal(new UsernamePasswordAuthenticationToken(principal, null)))
                .andExpect(status().isOk());
    }

    @Test
    void document_unknownType_returns400() throws Exception {
        mvc.perform(get("/api/rn/{rn}/documents/{tip}", RN, "nepostojeci-akt")
                        .principal(internalUser()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void document_invalidRnFormat_returns400() throws Exception {
        mvc.perform(get("/api/rn/{rn}/documents/{tip}", "INVALID", "suspenzija")
                        .principal(internalUser()))
                .andExpect(status().isBadRequest());
    }

    /** Prijavljen, ali nije iznajmljivač — do dolaska internih rola (BX0) prolazi bez ograničenja. */
    private static UsernamePasswordAuthenticationToken internalUser() {
        return new UsernamePasswordAuthenticationToken("referent", null);
    }
}
