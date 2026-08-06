package com.str.backend.auth.nias;

import com.str.backend.address.CountryRepository;
import com.str.backend.auth.SessionIdentityResolver;
import com.str.backend.categorization.CategorizationDecisionResponse;
import com.str.backend.categorization.CategorizationDecisionService;
import com.str.backend.categorization.CategorizationDecisionStatus;
import com.str.backend.lessor.LessorDocumentRepository;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.lessor.LessorRnActionService;
import com.str.backend.rn.RnRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ugovor dvaju endpointa NIAS dashboarda. Filteri su isključeni, pa se identitet vrti oko
 * {@link NiasOibResolver} — kad on ne razriješi OIB, oba puta moraju vratiti 401 i ne dirati servis.
 */
@ActiveProfiles("test")
@WebMvcTest(NiasController.class)
@AutoConfigureMockMvc(addFilters = false)
class NiasFacilityControllerTest {

    private static final String OIB = "99999999990";

    @Autowired MockMvc mvc;

    @MockBean NiasOibResolver oibResolver;
    @MockBean NiasFacilityService facilityService;
    @MockBean CategorizationDecisionService categorizationDecisionService;
    @MockBean RnRepository rnRepository;
    @MockBean LessorRepository lessorRepository;
    @MockBean LessorDocumentRepository lessorDocumentRepository;
    @MockBean CountryRepository countryRepository;
    @MockBean LessorRnActionService rnActionService;
    @MockBean SessionIdentityResolver identityResolver;

    @Test
    void returnsFacilityPage() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(facilityService.list(eq(OIB), any(), any())).thenReturn(new FacilityPageResponse(
                List.of(new FacilityResponse("153049", "Soba 1", "FS_SOBA", "Soba", "Tri zvjezdice",
                        "Aktivan", 2, null, "Splitsko-dalmatinska", "Makarska", "Makarska",
                        "Kraljevska", "88", "21300", "Kraljevska 88", null, FacilitySource.ETURIZAM)),
                0, 20, 1));

        mvc.perform(get("/api/nias/facilities").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value("153049"))
                .andExpect(jsonPath("$.items[0].vrstaSifra").value("FS_SOBA"))
                .andExpect(jsonPath("$.items[0].registracijskiBroj").doesNotExist())
                .andExpect(jsonPath("$.items[0].izvor").value("ETURIZAM"));
    }

    @Test
    void rejectsFacilityList_withoutNiasSession() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.empty());

        mvc.perform(get("/api/nias/facilities")).andExpect(status().isUnauthorized());
        verify(facilityService, never()).list(any(), any(), any());
    }

    @Test
    void acceptsScannedDecision() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        UUID decisionId = UUID.randomUUID();
        when(categorizationDecisionService.upload(eq(OIB), any()))
                .thenReturn(new CategorizationDecisionResponse(decisionId,
                        CategorizationDecisionStatus.SUBMITTED, "rjesenje.pdf", 12, Instant.now()));

        mvc.perform(multipart("/api/nias/categorization-decisions").file(pdf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.decisionId").value(decisionId.toString()))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    void rejectsScannedDecision_withoutNiasSession() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.empty());

        mvc.perform(multipart("/api/nias/categorization-decisions").file(pdf()))
                .andExpect(status().isUnauthorized());
        verify(categorizationDecisionService, never()).upload(any(), any());
    }

    /**
     * Datoteka iznad 10 MB: bez rukovatelja u {@code GlobalExceptionHandler} Spring bi vratio 500,
     * a frontend to ograničenje već prikazuje pa mora dobiti poruku, ne „greška na serveru".
     */
    @Test
    void rejectsScannedDecision_whenFileTooLarge() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));
        when(categorizationDecisionService.upload(eq(OIB), any()))
                .thenThrow(new MaxUploadSizeExceededException(10 * 1024 * 1024));

        mvc.perform(multipart("/api/nias/categorization-decisions").file(pdf()))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void rejectsScannedDecision_withoutFile() throws Exception {
        when(oibResolver.resolve(any())).thenReturn(Optional.of(OIB));

        mvc.perform(multipart("/api/nias/categorization-decisions"))
                .andExpect(status().isBadRequest());
        verify(categorizationDecisionService, never()).upload(any(), any());
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("datoteka", "rjesenje.pdf", "application/pdf",
                "%PDF-1.7 test".getBytes(StandardCharsets.US_ASCII));
    }
}
