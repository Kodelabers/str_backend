package com.str.backend.lessor;

import com.str.backend.address.CountryRepository;
import com.str.backend.auth.LessorPrincipal;
import com.str.backend.captcha.AltchaService;
import com.str.backend.categorization.CategorizationDecisionService;
import com.str.backend.categorization.CategorizationDecisionStatus;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.domain.RnStatus;
import com.str.backend.exception.ConflictException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.objection.SuspensionObjectionService;
import com.str.backend.registration.ReissueService;
import com.str.backend.registration.RnOwner;
import com.str.backend.registration.dto.ReissuePrefillResponse;
import com.str.backend.rn.RnRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ponovni zahtjev nakon povlačenja (T15) na non-EU portalu: vlasnik je {@code lessorId} iz
 * {@link LessorPrincipal}. Filteri su isključeni, pa se prijava predaje kroz {@code .principal()}.
 */
@ActiveProfiles("test")
@WebMvcTest(LessorController.class)
@AutoConfigureMockMvc(addFilters = false)
class LessorControllerReissueTest {

    private static final String RN = "HR120001000000000123";
    private static final UUID LESSOR_ID = UUID.fromString("0f6c2c9e-6a43-4d8f-9b0d-6a1f3b1b7c55");

    @Autowired MockMvc mvc;

    @MockBean LessorRegistrationService registrationService;
    @MockBean LessorRepository lessorRepository;
    @MockBean LessorDocumentRepository lessorDocumentRepository;
    @MockBean CountryRepository countryRepository;
    @MockBean RnRepository rnRepository;
    @MockBean LessorRnActionService rnActionService;
    @MockBean AltchaService altchaService;
    @MockBean ReissueService reissueService;
    @MockBean CategorizationDecisionService categorizationDecisionService;
    @MockBean SuspensionObjectionService suspensionObjectionService;

    @Test
    void prefill_returnsFormShape_forOwnLessor() throws Exception {
        when(reissueService.prefill(eq(RN), eq(RnOwner.byLessor(LESSOR_ID))))
                .thenReturn(new ReissuePrefillResponse(RN, LocalDate.of(2026, 2, 1), LocalDate.of(2027, 8, 1),
                        new ReissuePrefillResponse.Objekt("Villa", 5L, "FS_KUCA_ZA_ODMOR", "Kuća za odmor", 8, 0,
                                3L, "Istarska županija", 30L, "Poreč", 300L, "Poreč", 3000L, "Obala",
                                null, "12", "52440", null, null,
                                OfferType.OTHER, Offering.WHOLE, false, false, false, null, null, null, null, null,
                                "john@example.com", "+4312345", null, null),
                        new ReissuePrefillResponse.Rjesenje(UUID.randomUUID(), "scan.jpg",
                                CategorizationDecisionStatus.SUBMITTED)));

        mvc.perform(get("/api/lessor/registrations/{rn}/ponovni-zahtjev", RN).principal(lessorAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.povucenRb").value(RN))
                .andExpect(jsonPath("$.ponovniZahtjevDo").value("2027-08-01"))
                .andExpect(jsonPath("$.objekt.naziv").value("Villa"))
                .andExpect(jsonPath("$.objekt.zupanijaId").value(3))
                .andExpect(jsonPath("$.objekt.kucniBrojId").doesNotExist())
                .andExpect(jsonPath("$.objekt.kucniBrojNaziv").value("12"))
                .andExpect(jsonPath("$.rjesenje.status").value("SUBMITTED"));
    }

    @Test
    void prefill_foreignRn_is404() throws Exception {
        when(reissueService.prefill(any(), any())).thenThrow(new ResourceNotFoundException("error.rn.notFound"));

        mvc.perform(get("/api/lessor/registrations/{rn}/ponovni-zahtjev", RN).principal(lessorAuth()))
                .andExpect(status().isNotFound());
        verify(reissueService).prefill(RN, RnOwner.byLessor(LESSOR_ID));
    }

    @Test
    void prefill_alreadyUsed_is409WithCode() throws Exception {
        when(reissueService.prefill(any(), any()))
                .thenThrow(new ConflictException("error.reissue.alreadyUsed", "REISSUE_ALREADY_USED"));

        mvc.perform(get("/api/lessor/registrations/{rn}/ponovni-zahtjev", RN).principal(lessorAuth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.code").value("REISSUE_ALREADY_USED"));
    }

    @Test
    void prefill_withoutLessorPrincipal_is401() throws Exception {
        mvc.perform(get("/api/lessor/registrations/{rn}/ponovni-zahtjev", RN))
                .andExpect(status().isUnauthorized());
        verify(reissueService, never()).prefill(any(), any());
    }

    @Test
    void registrations_carryPonovniZahtjevDo() throws Exception {
        LessorRnSummaryDto row = new LessorRnSummaryDto(RN, RnStatus.WITHDRAWN, LocalDate.of(2025, 5, 1),
                "Villa", "Obala", "12", "Poreč", "Kuća za odmor");
        when(rnRepository.findByLessorId(LESSOR_ID)).thenReturn(List.of(row));
        when(reissueService.withReissueDeadlines(List.of(row)))
                .thenReturn(List.of(row.withPonovniZahtjevDo(LocalDate.of(2027, 8, 1))));

        mvc.perform(get("/api/lessor/registrations").principal(lessorAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rn").value(RN))
                .andExpect(jsonPath("$[0].ponovniZahtjevDo").value("2027-08-01"));
    }

    private static Authentication lessorAuth() {
        LessorEntity entity = mock(LessorEntity.class);
        when(entity.getLessorId()).thenReturn(LESSOR_ID);
        when(entity.getUsername()).thenReturn("john@example.com");
        LessorPrincipal principal = new LessorPrincipal(entity);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
