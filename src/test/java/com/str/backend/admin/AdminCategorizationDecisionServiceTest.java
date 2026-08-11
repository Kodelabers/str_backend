package com.str.backend.admin;

import com.str.backend.categorization.CategorizationDecisionAdminDto;
import com.str.backend.categorization.CategorizationDecisionEntity;
import com.str.backend.categorization.CategorizationDecisionEntity.CategorizationDecisionMetadata;
import com.str.backend.categorization.CategorizationDecisionRepository;
import com.str.backend.categorization.CategorizationDecisionStatus;
import com.str.backend.categorization.CategorizationFileDto;
import com.str.backend.exception.IllegalStatusTransitionException;
import com.str.backend.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
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

    private final CategorizationDecisionRepository repository = mock(CategorizationDecisionRepository.class);
    private final AdminAuditService auditService = mock(AdminAuditService.class);
    private final AdminCategorizationDecisionService service =
            new AdminCategorizationDecisionService(repository, auditService);

    @Test
    void list_byStatus_mapsToDto() {
        Pageable pageable = PageRequest.of(0, 20);
        CategorizationDecisionEntity e = submitted();
        when(repository.findByStatus(CategorizationDecisionStatus.SUBMITTED, pageable))
                .thenReturn(new PageImpl<>(List.of(e), pageable, 1));

        Page<CategorizationDecisionAdminDto> page = service.list(CategorizationDecisionStatus.SUBMITTED, pageable);

        assertThat(page.getContent()).singleElement()
                .satisfies(dto -> {
                    assertThat(dto.decisionId()).isEqualTo(e.getDecisionId());
                    assertThat(dto.status()).isEqualTo(CategorizationDecisionStatus.SUBMITTED);
                    assertThat(dto.lessorOib()).isEqualTo(OIB);
                });
    }

    @Test
    void list_withoutStatus_usesFindAll() {
        Pageable pageable = PageRequest.of(0, 20);
        when(repository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(submitted()), pageable, 1));

        assertThat(service.list(null, pageable).getContent()).hasSize(1);
        verify(repository, never()).findByStatus(any(), any());
    }

    @Test
    void file_returnsContentAndType() {
        CategorizationDecisionEntity e = submitted();
        when(repository.findById(e.getDecisionId())).thenReturn(Optional.of(e));

        CategorizationFileDto f = service.file(e.getDecisionId());

        assertThat(f.fileName()).isEqualTo("rjesenje.pdf");
        assertThat(f.contentType()).isEqualTo("application/pdf");
        assertThat(f.content()).isEqualTo(PDF);
    }

    @Test
    void detail_notFound_throws() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(id)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void verify_fromSubmitted_setsVerifiedAndAudits() {
        CategorizationDecisionEntity e = submitted();
        when(repository.findById(e.getDecisionId())).thenReturn(Optional.of(e));

        service.verify(e.getDecisionId(), "actor-1");

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

        service.reject(e.getDecisionId(), "actor-2", "sken nečitak");

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

        assertThatThrownBy(() -> service.verify(e.getDecisionId(), "second"))
                .isInstanceOf(IllegalStatusTransitionException.class);
        verify(auditService, never()).record(any(), eq("CATEGORIZATION_VERIFY"), any(), any(), any());
    }

    @Test
    void reject_whenNotSubmitted_throwsAndDoesNotAudit() {
        CategorizationDecisionEntity e = submitted();
        e.reject("first"); // već REJECTED
        when(repository.findById(e.getDecisionId())).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> service.reject(e.getDecisionId(), "second", "razlog"))
                .isInstanceOf(IllegalStatusTransitionException.class);
        verify(auditService, never()).record(any(), eq("CATEGORIZATION_REJECT"), any(), any(), any());
    }

    private static CategorizationDecisionEntity submitted() {
        return CategorizationDecisionEntity.create(OIB, "rjesenje.pdf", "application/pdf", PDF,
                new CategorizationDecisionMetadata("Soba Marija", "FS_SOBA", "Sjenjak 19, Osijek",
                        "UP/I-123", LocalDate.of(2020, 1, 1), 3, "napomena"));
    }
}
