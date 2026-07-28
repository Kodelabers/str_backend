package com.str.backend.str;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Upisuje dodijeljeni registracijski broj natrag u eTurizam registar
 * ({@code str.facility.registration_number}) za objekt koji je došao kroz tuStart handoff.
 *
 * <p>Objekt se pogađa po {@code accommodation.facility_id} — vrijednosti URL parametra
 * {@code facilityId} koju je tuStart poslao na STR frontend i koju je frontend vratio u
 * tijelu registracije. Registracije koje ne dolaze iz tuStarta nemaju taj id i preskaču se.
 *
 * <p><strong>Nikad ne ruši registraciju.</strong> RB je izdan i valjan neovisno o tome je
 * li se upis natrag u tuđi registar uspio izvršiti — isto načelo kao kod eGOP dostave.
 * Svaki neuspjeh se logira i ostavlja za ručnu intervenciju; automatskog retryja nema
 * jer eTurizam nema idempotentni endpoint na koji bi se naslonio.
 */
@Service
public class FacilityRegistrationNumberWriteBack {

    private static final Logger log = LoggerFactory.getLogger(FacilityRegistrationNumberWriteBack.class);

    private final AccommodationRepository accommodationRepository;
    private final StrFacilityRepository facilityRepository;

    public FacilityRegistrationNumberWriteBack(AccommodationRepository accommodationRepository,
                                               StrFacilityRepository facilityRepository) {
        this.accommodationRepository = accommodationRepository;
        this.facilityRepository = facilityRepository;
    }

    public void writeBack(UUID submissionId, String rn) {
        String facilityId = accommodationRepository.findBySubmissionId(submissionId).stream()
                .findFirst()
                .map(AccommodationEntity::getFacilityId)
                .orElse(null);

        if (facilityId == null || facilityId.isBlank()) {
            return;
        }

        long id;
        try {
            id = Long.parseLong(facilityId.trim());
        } catch (NumberFormatException e) {
            log.warn("facility_writeback_skipped submission={} rn={} razlog=facilityId '{}' nije numerički",
                    submissionId, rn, facilityId);
            return;
        }

        try {
            int updated = facilityRepository.writeBackRegistrationNumber(id, rn);
            if (updated == 0) {
                log.warn("facility_writeback_no_row facility={} rn={} submission={} "
                                + "— objekt ne postoji u str.facility ili već ima RB",
                        id, rn, submissionId);
            } else {
                log.info("facility_writeback_ok facility={} rn={} submission={}", id, rn, submissionId);
            }
        } catch (RuntimeException e) {
            // Namjerno se guta: RB je već izdan i commitan, a ovo je upis u tuđi registar.
            log.error("facility_writeback_failed facility={} rn={} submission={} — RB ostaje valjan",
                    id, rn, submissionId, e);
        }
    }
}
