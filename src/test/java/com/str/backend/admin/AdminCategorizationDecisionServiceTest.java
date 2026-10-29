package com.str.backend.admin;

import com.str.backend.auth.internal.CountyScope;
import com.str.backend.auth.internal.InternalAccessService;
import com.str.backend.categorization.CategorizationDecisionAdminDto;
import com.str.backend.categorization.CategorizationDecisionEntity;
import com.str.backend.categorization.CategorizationDecisionEntity.CategorizationDecisionMetadata;
import com.str.backend.categorization.CategorizationDecisionRepository;
import com.str.backend.categorization.CategorizationDecisionStatus;
import com.str.backend.categorization.CategorizationFileDto;
import com.str.backend.exception.IllegalStatusTransitionException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.rn.RnRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminCategorizationDecisionServiceTest {

    private static final String OIB = "12312312316";
    private static final byte[] PDF = "%PDF-1.7\n...".getBytes(StandardCharsets.US_ASCII);
    private static final CountyScope ALL = CountyScope.all(CountyScope.Outcome.NOT_OFFICIAL);
    private static final CountyScope SPLIT =
            CountyScope.county("Splitsko-dalmatinska županija", 17L, 17, "Splitsko-dalmatinska županija");

    private final CategorizationDecisionRepository repository = mock(CategorizationDecisionRepository.class);
    private final AdminAuditService auditService = mock(AdminAuditService.class);
    private final InternalAccessService internalAccess = mock(InternalAccessService.class);
    private final LessorRepository lessorRepository = mock(LessorRepository.class);
    private final RnRepository rnRepository = mock(RnRepository.class);
    private final AdminCategorizationDecisionService service = new AdminCategorizationDecisionService(
            repository, auditService, internalAccess, lessorRepository, rnRepository);

    @Test
    void list_byStatus_mapsToDto() {
        Pageable pageable = PageRequest.of(0, 20);
        CategorizationDecisionEntity e = submitted();
        when(repository.findForReviewByStatus(CategorizationDecisionStatus.SUBMITTED, pageable))
                .thenReturn(new PageImpl<>(List.of(e), pageable, 1));

        Page<CategorizationDecisionAdminDto> page = service.list(CategorizationDecisionStatus.SUBMITTED, null, pageable);

        assertThat(page.getContent()).singleElement()
                .satisfies(dto -> {
                    assertThat(dto.decisionId()).isEqualTo(e.getDecisionId());
                    assertThat(dto.status()).isEqualTo(CategorizationDecisionStatus.SUBMITTED);
                    assertThat(dto.lessorOib()).isEqualTo(OIB);
                });
    }

    /** Non-EU iznajmljivač nema OIB — u redu se vidi njegov strani porezni broj. */
    @Test
    void list_nonEuDecision_carriesTaxNumber() {
        Pageable pageable = PageRequest.of(0, 20);
        UUID lessorId = UUID.randomUUID();
        CategorizationDecisionEntity nonEu = CategorizationDecisionEntity.createForLessor(lessorId, "HR00000011",
                "rjesenje.pdf", "application/pdf", PDF,
                new CategorizationDecisionMetadata(null, null, null, null, null, null, null));
        LessorEntity lessor = mock(LessorEntity.class);
        when(lessor.getLessorId()).thenReturn(lessorId);
        when(lessor.getTaxNumber()).thenReturn("DE123456789");
        when(lessorRepository.findAllById(any())).thenReturn(List.of(lessor));
        when(repository.findForReview(pageable)).thenReturn(new PageImpl<>(List.of(nonEu, submitted()), pageable, 2));

        List<CategorizationDecisionAdminDto> rows = service.list(null, null, pageable).getContent();

        assertThat(rows.get(0).lessorOib()).isNull();
        assertThat(rows.get(0).lessorTaxNumber()).isEqualTo("DE123456789");
        assertThat(rows.get(1).lessorOib()).isEqualTo(OIB);
        assertThat(rows.get(1).lessorTaxNumber()).isNull();
    }

    @Test
    void list_withoutStatus_usesReviewQueryWithoutStatus() {
        Pageable pageable = PageRequest.of(0, 20);
        when(repository.findForReview(pageable)).thenReturn(new PageImpl<>(List.of(submitted()), pageable, 1));

        assertThat(service.list(null, null, pageable).getContent()).hasSize(1);
        verify(repository, never()).findForReviewByStatus(any(), any());
        verify(repository, never()).findAll(any(Pageable.class));
    }

    @Test
    void file_returnsContentAndType() {
        CategorizationDecisionEntity e = submitted();
        when(repository.findById(e.getDecisionId())).thenReturn(Optional.of(e));

        CategorizationFileDto f = service.file(e.getDecisionId(), ALL);

        assertThat(f.fileName()).isEqualTo("rjesenje.pdf");
        assertThat(f.contentType()).isEqualTo("application/pdf");
        assertThat(f.content()).isEqualTo(PDF);
    }

    @Test
    void detail_notFound_throws() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(id, ALL)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void verify_fromSubmitted_setsVerifiedAndAudits() {
        CategorizationDecisionEntity e = submitted();
        when(repository.findById(e.getDecisionId())).thenReturn(Optional.of(e));

        service.verify(e.getDecisionId(), ALL, "actor-1");

        assertThat(e.getStatus()).isEqualTo(CategorizationDecisionStatus.VERIFIED);
        assertThat(e.getVerifiedBy()).isEqualTo("actor-1");
        assertThat(e.getVerifiedAt()).isNotNull();
        assertThat(e.getFacilityId()).isNull(); // eTurizam upis je odvojen korak (TODO-šav)
        verify(auditService).record("actor-1", "CATEGORIZATION_VERIFY",
                "CATEGORIZATION_DECISION", e.getDecisionId().toString(), null);
    }

    @Test
    void reject_fromSubmitted_setsRejectedAndAuditsReason() {
        CategorizationDecisionEntity e = submitted();
        when(repository.findById(e.getDecisionId())).thenReturn(Optional.of(e));

        service.reject(e.getDecisionId(), ALL, "actor-2", "sken nečitak");

        assertThat(e.getStatus()).isEqualTo(CategorizationDecisionStatus.REJECTED);
        assertThat(e.getVerifiedBy()).isEqualTo("actor-2");
        verify(auditService).record("actor-2", "CATEGORIZATION_REJECT",
                "CATEGORIZATION_DECISION", e.getDecisionId().toString(), "sken nečitak");
    }

    @Test
    void verify_whenNotSubmitted_throwsAndDoesNotAudit() {
        CategorizationDecisionEntity e = submitted();
        e.verify("first"); // već VERIFIED
        when(repository.findById(e.getDecisionId())).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> service.verify(e.getDecisionId(), ALL, "second"))
                .isInstanceOf(IllegalStatusTransitionException.class);
        verify(auditService, never()).record(any(), eq("CATEGORIZATION_VERIFY"), any(), any(), any());
    }

    @Test
    void reject_whenNotSubmitted_throwsAndDoesNotAudit() {
        CategorizationDecisionEntity e = submitted();
        e.reject("first"); // već REJECTED
        when(repository.findById(e.getDecisionId())).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> service.reject(e.getDecisionId(), ALL, "second", "razlog"))
                .isInstanceOf(IllegalStatusTransitionException.class);
        verify(auditService, never()).record(any(), eq("CATEGORIZATION_REJECT"), any(), any(), any());
    }

    /** Županijski službenik: red čitaju upiti s opsegom, ne oni za cijelu RH. */
    @Test
    void list_restricted_usesInCountiesQueries() {
        Pageable pageable = PageRequest.of(0, 20);
        List<String> counties = List.of("Splitsko-dalmatinska županija", "Splitsko-dalmatinska");
        when(repository.findForReviewInCounties(counties, pageable))
                .thenReturn(new PageImpl<>(List.of(submitted()), pageable, 1));
        when(repository.findForReviewByStatusInCounties(CategorizationDecisionStatus.SUBMITTED, counties, pageable))
                .thenReturn(new PageImpl<>(List.of(submitted(), submitted()), pageable, 2));

        assertThat(service.list(null, counties, pageable).getContent()).hasSize(1);
        assertThat(service.list(CategorizationDecisionStatus.SUBMITTED, counties, pageable).getContent()).hasSize(2);
        verify(repository, never()).findForReview(any());
        verify(repository, never()).findForReviewByStatus(any(), any());
    }

    @Test
    void detail_restricted_rnInOtherCounty_isNotFound() {
        CategorizationDecisionEntity e = withRn("HR120001000000000001");
        when(repository.findById(e.getDecisionId())).thenReturn(Optional.of(e));
        when(rnRepository.findCountyByRn("HR120001000000000001")).thenReturn(Optional.of("Grad Zagreb"));

        assertThatThrownBy(() -> service.detail(e.getDecisionId(), SPLIT))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.verify(e.getDecisionId(), SPLIT, "actor-1"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(e.getStatus()).isEqualTo(CategorizationDecisionStatus.SUBMITTED);
    }

    /** Rješenje bez RB-a ne pripada nijednoj županiji. */
    @Test
    void detail_restricted_withoutRn_isNotFound() {
        CategorizationDecisionEntity e = submitted();
        when(repository.findById(e.getDecisionId())).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> service.detail(e.getDecisionId(), SPLIT))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(rnRepository, never()).findCountyByRn(any());
    }

    /** Naziv na smještaju u drugom obliku (bez „županija", velika slova) i dalje je ista županija. */
    @Test
    void detail_restricted_rnInSameCounty_returnsDecision() {
        CategorizationDecisionEntity e = withRn("HR120001000000000002");
        when(repository.findById(e.getDecisionId())).thenReturn(Optional.of(e));
        when(rnRepository.findCountyByRn("HR120001000000000002")).thenReturn(Optional.of("SPLITSKO-DALMATINSKA"));

        assertThat(service.detail(e.getDecisionId(), SPLIT).decisionId()).isEqualTo(e.getDecisionId());
    }

    private static CategorizationDecisionEntity withRn(String rn) {
        return CategorizationDecisionEntity.create(OIB, rn, "rjesenje.pdf", "application/pdf", PDF,
                new CategorizationDecisionMetadata("Soba Marija", "FS_SOBA", "Sjenjak 19, Osijek",
                        "UP/I-123", LocalDate.of(2020, 1, 1), 3, "napomena"));
    }

    private static CategorizationDecisionEntity submitted() {
        return CategorizationDecisionEntity.create(OIB, null, "rjesenje.pdf", "application/pdf", PDF,
                new CategorizationDecisionMetadata("Soba Marija", "FS_SOBA", "Sjenjak 19, Osijek",
                        "UP/I-123", LocalDate.of(2020, 1, 1), 3, "napomena"));
    }

    /** U sučelju stoji ime službenika, ne INTERNAL:<oib> iz revizijskog zapisa. */
    @Test
    void detail_showsOfficialName_notRawActor() {
        CategorizationDecisionEntity e = submitted();
        e.verify("INTERNAL:12345678903");
        when(repository.findById(e.getDecisionId())).thenReturn(java.util.Optional.of(e));
        when(internalAccess.displayNames(List.of("INTERNAL:12345678903")))
                .thenReturn(Map.of("INTERNAL:12345678903", "Ana Službenica"));

        assertThat(service.detail(e.getDecisionId(), ALL).verifiedBy()).isEqualTo("Ana Službenica");
    }
}
