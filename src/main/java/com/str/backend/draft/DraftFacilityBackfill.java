package com.str.backend.draft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Nacrti spremljeni prije changeseta 128 nemaju {@code facility_id} — objekt je samo u šifriranom
 * payloadu ({@code formValues.facilityId}). Pri startu ga se odande prepiše u stupac, da i stari
 * nacrti sudjeluju u „jedan nacrt po objektu" i da ih izdavanje RB-a za objekt pobriše.
 *
 * <p>Nacrti novih objekata ostaju bez objekta i skeniraju se pri svakom startu; ograničeni su
 * TTL-om ({@code app.draft.ttl-days}), pa je to jeftino. Kad od deploya prođe TTL, starih nacrta
 * više nema i ova se klasa može obrisati.
 *
 * <p>Pokvaren red (drugi ključ, neispravan JSON) se preskače — start aplikacije ne smije pasti
 * zbog nacrta.
 */
@Component
public class DraftFacilityBackfill {

    private static final Logger log = LoggerFactory.getLogger(DraftFacilityBackfill.class);

    private final SubmissionDraftRepository repository;
    private final DraftEncryptionService encryption;
    private final ObjectMapper mapper;

    public DraftFacilityBackfill(SubmissionDraftRepository repository, DraftEncryptionService encryption,
                                 ObjectMapper mapper) {
        this.repository = repository;
        this.encryption = encryption;
        this.mapper = mapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void backfill() {
        List<SubmissionDraftEntity> drafts = repository.findByFacilityIdIsNull();
        int assigned = 0;
        for (SubmissionDraftEntity draft : drafts) {
            try {
                String facilityId = facilityIdFromPayload(encryption.decrypt(draft.getPayload()));
                if (facilityId != null) {
                    draft.assignFacilityId(facilityId);
                    assigned++;
                }
            } catch (Exception e) {
                log.warn("draft_backfill skipped draft={} reason={}", draft.getDraftId(), e.getMessage());
            }
        }
        if (assigned > 0) {
            log.info("draft_backfill facility_assigned={} scanned={}", assigned, drafts.size());
        }
    }

    String facilityIdFromPayload(String json) throws Exception {
        JsonNode node = mapper.readTree(json).path("formValues").path("facilityId");
        if (!node.isTextual() && !node.isNumber()) {
            return null;
        }
        String value = node.asText().trim();
        if (value.isEmpty() || value.length() > 64) {
            return null;
        }
        return value;
    }
}
