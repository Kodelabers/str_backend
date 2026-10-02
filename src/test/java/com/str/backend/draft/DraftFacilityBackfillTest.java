package com.str.backend.draft;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Stari nacrti dobivaju objekt iz šifriranog payloada; pokvaren red ne ruši start. */
class DraftFacilityBackfillTest {

    private static final DraftOwner OWNER = new DraftOwner(DraftOwnerType.NIAS_OIB, "99999999990");

    private SubmissionDraftRepository repository;
    private DraftEncryptionService encryption;
    private DraftFacilityBackfill backfill;

    @BeforeEach
    void setUp() {
        repository = mock(SubmissionDraftRepository.class);
        encryption = new DraftEncryptionService(Base64.getEncoder().encodeToString(new byte[32]));
        backfill = new DraftFacilityBackfill(repository, encryption, new ObjectMapper());
    }

    @Test
    void assignsFacilityFromPayload_skipsNewObjectsAndBrokenRows() {
        SubmissionDraftEntity existing = draft("{\"origin\":\"NIAS\",\"formValues\":{\"facilityId\":\"4711\"}}");
        SubmissionDraftEntity newObject = draft("{\"origin\":\"NIAS\",\"formValues\":{\"naziv\":\"Apartman\"}}");
        SubmissionDraftEntity blank = draft("{\"origin\":\"NIAS\",\"formValues\":{\"facilityId\":\"\"}}");
        SubmissionDraftEntity broken = SubmissionDraftEntity.create(OWNER, "x",
                "nije sifrirano".getBytes(StandardCharsets.UTF_8), null);
        when(repository.findByFacilityIdIsNull()).thenReturn(List.of(existing, newObject, blank, broken));

        backfill.backfill();

        assertThat(existing.getFacilityId()).isEqualTo("4711");
        assertThat(newObject.getFacilityId()).isNull();
        assertThat(blank.getFacilityId()).isNull();
        assertThat(broken.getFacilityId()).isNull();
    }

    private SubmissionDraftEntity draft(String json) {
        return SubmissionDraftEntity.create(OWNER, "Nacrt", encryption.encrypt(json), null);
    }
}
