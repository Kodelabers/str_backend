package com.str.backend.draft;

import com.str.backend.draft.dto.DraftListItemResponse;
import com.str.backend.draft.dto.DraftRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Jedan nacrt po objektu: spremanje za objekt koji već ima nacrt ažurira taj nacrt. */
class SubmissionDraftServiceTest {

    private static final DraftOwner OWNER = new DraftOwner(DraftOwnerType.NIAS_OIB, "99999999990");
    private static final String FACILITY_ID = "4711";
    private static final int MAX = 2;

    private SubmissionDraftRepository repository;
    private SubmissionDraftService service;

    @BeforeEach
    void setUp() {
        repository = mock(SubmissionDraftRepository.class);
        DraftEncryptionService encryption = mock(DraftEncryptionService.class);
        when(encryption.encrypt(any())).thenAnswer(inv -> ((String) inv.getArgument(0)).getBytes());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new SubmissionDraftService(repository, encryption, MAX);
    }

    @Test
    void create_forFacilityWithExistingDraft_updatesItAndSkipsLimit() {
        SubmissionDraftEntity existing = SubmissionDraftEntity.create(OWNER, "Staro", "{}".getBytes(), FACILITY_ID);
        when(repository.findFirstByOwnerTypeAndOwnerKeyAndFacilityIdOrderByUpdatedAtDesc(
                OWNER.type(), OWNER.key(), FACILITY_ID)).thenReturn(Optional.of(existing));

        SubmissionDraftService.SaveResult result = service.create(OWNER, new DraftRequest("Novo", "{\"a\":1}", FACILITY_ID));

        assertThat(result.created()).isFalse();
        assertThat(result.draft().draftId()).isEqualTo(existing.getDraftId());
        assertThat(existing.getTitle()).isEqualTo("Novo");
        verify(repository, never()).countByOwnerTypeAndOwnerKey(any(), any());
    }

    @Test
    void create_forFacilityWithoutDraft_createsNewWithFacility() {
        when(repository.findFirstByOwnerTypeAndOwnerKeyAndFacilityIdOrderByUpdatedAtDesc(any(), any(), any()))
                .thenReturn(Optional.empty());

        SubmissionDraftService.SaveResult result = service.create(OWNER, new DraftRequest("Novo", "{}", " 4711 "));

        assertThat(result.created()).isTrue();
        assertThat(result.draft().facilityId()).isEqualTo(FACILITY_ID);
    }

    /** Novi objekt (bez facilityId): kao prije — novi nacrt, uz limit. */
    @Test
    void create_withoutFacility_alwaysNew_andRespectsLimit() {
        when(repository.countByOwnerTypeAndOwnerKey(OWNER.type(), OWNER.key())).thenReturn((long) MAX);

        assertThatThrownBy(() -> service.create(OWNER, new DraftRequest("Novo", "{}", "")))
                .isInstanceOf(ResponseStatusException.class);
        verify(repository, never()).findFirstByOwnerTypeAndOwnerKeyAndFacilityIdOrderByUpdatedAtDesc(any(), any(), any());
    }

    /** Forma očisti objekt kad ga claim ne nađe — nacrt tada postaje nacrt novog objekta. */
    @Test
    void update_clearsFacilityWhenRequestHasNone() {
        SubmissionDraftEntity existing = SubmissionDraftEntity.create(OWNER, "Staro", "{}".getBytes(), FACILITY_ID);
        when(repository.findByDraftIdAndOwnerTypeAndOwnerKey(existing.getDraftId(), OWNER.type(), OWNER.key()))
                .thenReturn(Optional.of(existing));

        service.update(existing.getDraftId(), OWNER, new DraftRequest("Staro", "{}", null));

        ArgumentCaptor<SubmissionDraftEntity> saved = ArgumentCaptor.forClass(SubmissionDraftEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getFacilityId()).isNull();
    }

    @Test
    void list_withFacility_returnsOnlyThatFacility() {
        SubmissionDraftEntity draft = SubmissionDraftEntity.create(OWNER, "A", "{}".getBytes(), FACILITY_ID);
        when(repository.findByOwnerTypeAndOwnerKeyAndFacilityIdOrderByUpdatedAtDesc(
                OWNER.type(), OWNER.key(), FACILITY_ID)).thenReturn(List.of(draft));

        List<DraftListItemResponse> list = service.list(OWNER, FACILITY_ID);

        assertThat(list).extracting(DraftListItemResponse::draftId).containsExactly(draft.getDraftId());
        verify(repository, never()).findByOwnerTypeAndOwnerKeyOrderByUpdatedAtDesc(any(), any());
    }

    @Test
    void discardForFacility_deletesAllDraftsOfFacility() {
        when(repository.deleteByFacilityId(FACILITY_ID)).thenReturn(3);

        assertThat(service.discardForFacility(FACILITY_ID)).isEqualTo(3);
    }

    @Test
    void discardForFacility_blank_isNoop() {
        assertThat(service.discardForFacility(" ")).isZero();
        verify(repository, never()).deleteByFacilityId(any());
    }
}
