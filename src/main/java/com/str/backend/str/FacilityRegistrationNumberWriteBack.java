package com.str.backend.str;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.rn.RnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Drži registracijski broj u eTurizam registru ({@code str.facility.registration_number})
 * usklađen s STR-om za objekt koji je došao kroz tuStart handoff: upisuje dodijeljeni broj
 * ({@link #writeBack}) i briše povučeni ({@link #clear}), da objekt nakon povlačenja može
 * dobiti novi broj.
 *
 * <p>Objekt se pogađa po {@code accommodation.facility_id} — vrijednosti URL parametra
 * {@code facilityId} koju je tuStart poslao na STR frontend i koju je frontend vratio u
 * tijelu registracije. Registracije koje ne dolaze iz tuStarta nemaju taj id i preskaču se.
 *
 * <p><strong>Nikad ne ruši registraciju ni povlačenje.</strong> RB je izdan (ili povučen) i
 * valjan neovisno o tome je li se upis u tuđi registar uspio izvršiti — isto načelo kao kod
 * eGOP dostave. Svaki neuspjeh se logira i ostavlja za ručnu intervenciju; automatskog retryja
 * nema jer eTurizam nema idempotentni endpoint na koji bi se naslonio. Neuspjelo brisanje ne
 * blokira novi broj: popis objekata i upis novog broja povučeni broj prepoznaju i sami.
 */
@Service
public class FacilityRegistrationNumberWriteBack {

    private static final Logger log = LoggerFactory.getLogger(FacilityRegistrationNumberWriteBack.class);

    private final AccommodationRepository accommodationRepository;
    private final StrFacilityRepository facilityRepository;
    private final RnRepository rnRepository;

    public FacilityRegistrationNumberWriteBack(AccommodationRepository accommodationRepository,
                                               StrFacilityRepository facilityRepository,
                                               RnRepository rnRepository) {
        this.accommodationRepository = accommodationRepository;
        this.facilityRepository = facilityRepository;
        this.rnRepository = rnRepository;
    }

    public void writeBack(UUID submissionId, String rn) {
        String facilityId = accommodationRepository.findBySubmissionId(submissionId).stream()
                .findFirst()
                .map(AccommodationEntity::getFacilityId)
                .orElse(null);
        Long id = numericId("writeback", facilityId, rn);
        if (id == null) {
            return;
        }

        try {
            int updated = facilityRepository.writeBackRegistrationNumber(id, rn);
            if (updated == 0) {
                log.warn("facility_writeback_no_row facility={} rn={} submission={} "
                                + "— objekt ne postoji u str.facility ili već ima RB koji nije povučen",
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

    /** Povučeni RB više nije broj objekta — briše se iz eTurizma, ako je ondje upravo on. */
    public void clear(String rn) {
        Long id;
        try {
            id = numericId("clear", rnRepository.findFacilityIdByRn(rn).orElse(null), rn);
        } catch (RuntimeException e) {
            log.error("facility_clear_failed rn={} — dohvat objekta RB-a nije uspio, povlačenje ostaje valjano", rn, e);
            return;
        }
        if (id == null) {
            return;
        }

        try {
            int updated = facilityRepository.clearRegistrationNumber(id, rn);
            if (updated == 0) {
                log.info("facility_clear_no_row facility={} rn={} — u str.facility nije taj broj", id, rn);
            } else {
                log.info("facility_clear_ok facility={} rn={}", id, rn);
            }
        } catch (RuntimeException e) {
            // Namjerno se guta: povlačenje je već commitano, a ovo je upis u tuđi registar.
            log.error("facility_clear_failed facility={} rn={} — povlačenje ostaje valjano", id, rn, e);
        }
    }

    /** {@code null} kad RB nije došao kroz tuStart (nema id-a) ili id nije eTurizamov (nije broj). */
    private static Long numericId(String operation, String facilityId, String rn) {
        if (facilityId == null || facilityId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(facilityId.trim());
        } catch (NumberFormatException e) {
            log.warn("facility_{}_skipped rn={} razlog=facilityId '{}' nije numerički", operation, rn, facilityId);
            return null;
        }
    }
}
