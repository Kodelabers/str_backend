package com.str.backend.rn;

import com.str.backend.auth.LessorPrincipal;
import com.str.backend.document.FilingReference;
import com.str.backend.document.StrDocumentService;
import com.str.backend.document.StrDocumentType;
import com.str.backend.rn.dto.RnDocumentDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
    @MockBean private RnDocumentsService documentsService;
    @MockBean private RnRepository rnRepository;
    // RnController od /api/rn/export/* endpointa ovisi i o exportu; slice ga mora imati.
    @MockBean private com.str.backend.statistics.StatisticsExportService exportService;

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

    // --- Popis dokumenata po RB-u ---

    @Test
    void documents_listing_ownerReturnsList() throws Exception {
        LessorPrincipal principal = mock(LessorPrincipal.class);
        UUID lessorId = UUID.randomUUID();
        when(principal.getLessorId()).thenReturn(lessorId);
        when(rnRepository.isOwnedByLessor(RN, lessorId)).thenReturn(true);
        when(documentsService.listForRn(RN)).thenReturn(List.of(
                new RnDocumentDto(null, "dodjela", "Obavijest o dodjeli registracijskog broja",
                        "IZLAZNO", LocalDate.of(2026, 7, 1), "/api/rn/" + RN + "/documents/dodjela")));

        mvc.perform(get("/api/rn/{rn}/documents", RN)
                        .principal(new UsernamePasswordAuthenticationToken(principal, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("dodjela"))
                .andExpect(jsonPath("$[0].href").value("/api/rn/" + RN + "/documents/dodjela"));
    }

    @Test
    void documents_listing_foreignRn_returns404() throws Exception {
        LessorPrincipal principal = mock(LessorPrincipal.class);
        UUID lessorId = UUID.randomUUID();
        when(principal.getLessorId()).thenReturn(lessorId);
        when(rnRepository.isOwnedByLessor(RN, lessorId)).thenReturn(false);

        mvc.perform(get("/api/rn/{rn}/documents", RN)
                        .principal(new UsernamePasswordAuthenticationToken(principal, null)))
                .andExpect(status().isNotFound());

        verify(documentsService, never()).listForRn(any());
    }

    /** NIAS korisnik: vlasništvo se provjerava po OIB-u iz SAML assertiona, ne po lessorId. */
    @Test
    void documents_listing_niasOwnerByOib_ok() throws Exception {
        when(rnRepository.isOwnedByOib(RN, "12345678901")).thenReturn(true);
        when(documentsService.listForRn(RN)).thenReturn(List.of());

        mvc.perform(get("/api/rn/{rn}/documents", RN)
                        .principal(samlAuth("12345678901")))
                .andExpect(status().isOk());
    }

    @Test
    void documents_listing_niasForeignRn_returns404() throws Exception {
        when(rnRepository.isOwnedByOib(RN, "99999999999")).thenReturn(false);

        mvc.perform(get("/api/rn/{rn}/documents", RN)
                        .principal(samlAuth("99999999999")))
                .andExpect(status().isNotFound());

        verify(documentsService, never()).listForRn(any());
    }

    @Test
    void documents_listing_invalidRnFormat_returns400() throws Exception {
        mvc.perform(get("/api/rn/{rn}/documents", "INVALID")
                        .principal(internalUser()))
                .andExpect(status().isBadRequest());

        verify(documentsService, never()).listForRn(any());
    }

    @Test
    void storedDocument_ownerReturnsPdf_withReadableFilename() throws Exception {
        UUID aktId = UUID.randomUUID();
        when(documentsService.storedAktPdf(RN, aktId))
                .thenReturn(new RnDocumentsService.StoredDocument("suspenzija-" + RN + ".pdf", new byte[]{1, 2, 3}));

        mvc.perform(get("/api/rn/{rn}/documents/pohranjeno/{aktId}", RN, aktId)
                        .principal(internalUser()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"suspenzija-" + RN + ".pdf\""));
    }

    @Test
    void document_zahtjev_servesSubmissionPdf_notTemplateRender() throws Exception {
        when(documentsService.zahtjevPdf(RN)).thenReturn(new byte[]{9});

        mvc.perform(get("/api/rn/{rn}/documents/{tip}", RN, "zahtjev")
                        .principal(internalUser()))
                .andExpect(status().isOk());

        verify(documentService, never()).render(any(), any(), any());
    }

    /** Dodjela se renderira s pravim URBROJ-em iz izlaznog pismena, a ne klasa-only. */
    @Test
    void document_dodjela_rendersWithResolvedFiling() throws Exception {
        FilingReference filing = new FilingReference("334-01/26-01/55", "529-06/26-2");
        when(documentsService.dodjelaFiling(RN)).thenReturn(filing);
        when(documentService.render(eq(StrDocumentType.DODJELA), eq(RN), any(), eq(filing)))
                .thenReturn(new byte[]{5});

        mvc.perform(get("/api/rn/{rn}/documents/{tip}", RN, "dodjela")
                        .principal(internalUser()))
                .andExpect(status().isOk());

        verify(documentService).render(eq(StrDocumentType.DODJELA), eq(RN), any(), eq(filing));
    }

    /** Prijavljen, ali nije iznajmljivač — do dolaska internih rola (BX0) prolazi bez ograničenja. */
    private static UsernamePasswordAuthenticationToken internalUser() {
        return new UsernamePasswordAuthenticationToken("referent", null);
    }

    private static Saml2Authentication samlAuth(String oib) {
        DefaultSaml2AuthenticatedPrincipal principal = new DefaultSaml2AuthenticatedPrincipal(
                "persistent-nameid", Map.of("oib", List.<Object>of(oib)));
        return new Saml2Authentication(principal, "<saml2p:Response/>", List.of());
    }
}
