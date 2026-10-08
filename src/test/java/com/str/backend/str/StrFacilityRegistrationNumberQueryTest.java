package com.str.backend.str;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.rn.RnRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pisanje broja u eTurizam ({@code str.facility.registration_number}) na pravoj bazi (H2): objekt
 * nakon povlačenja mora moći dobiti novi broj, a tuđi ili stojeći broj se nikad ne dira.
 */
@SpringBootTest
@ActiveProfiles("test")
class StrFacilityRegistrationNumberQueryTest {

    private static final String WITHDRAWN = "HR120001000000000801";
    private static final String ACTIVE = "HR120001000000000802";
    private static final String NEW = "HR120001000000000803";
    private static final String FOREIGN = "HR999999000000000001";

    @Autowired private StrFacilityRepository facilityRepository;
    @Autowired private RnRepository rnRepository;
    @Autowired private AccommodationRepository accommodationRepository;
    @Autowired private JdbcTemplate jdbc;

    /** Smještaj uz povučeni RB — eTurizam objekt 8007, kao da je došao kroz tuStart handoff. */
    private UUID withdrawnAccommodation;

    @BeforeEach
    void setUp() {
        jdbc.execute("ALTER TABLE str.facility ADD COLUMN IF NOT EXISTS registration_number VARCHAR(64)");
        cleanUp();
        AccommodationEntity accommodation = AccommodationEntity.create(
                null, "Grad Zagreb", "Zagreb", "Ilica", "1", 2, 2, OfferType.OTHER, Offering.WHOLE,
                false, false, true);
        accommodation.setFacilityId("8007");
        withdrawnAccommodation = accommodationRepository.save(accommodation).getAccommodationId();
        rn(WITHDRAWN, "WITHDRAWN", withdrawnAccommodation);
        rn(ACTIVE, "ACTIVE", UUID.randomUUID());
    }

    /** Dijeljena H2 baza: REQUIRES_NEW upisi se ne poništavaju, pa se čiste ručno. */
    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM str.facility WHERE id BETWEEN 8000 AND 8999");
        jdbc.update("DELETE FROM str_rn.registration_number WHERE rn IN (?, ?, ?)", WITHDRAWN, ACTIVE, NEW);
        if (withdrawnAccommodation != null) {
            accommodationRepository.deleteById(withdrawnAccommodation);
            withdrawnAccommodation = null;
        }
    }

    private void rn(String rn, String status, UUID accommodationId) {
        jdbc.update("""
                INSERT INTO str_rn.registration_number
                  (rn, accommodation_id, status, issue_date, valid_from, created_at, updated_at)
                VALUES (?, ?, ?, CURRENT_DATE, CURRENT_DATE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, rn, accommodationId, status);
    }

    private void facility(long id, String registrationNumber) {
        jdbc.update("INSERT INTO str.facility (id, active, registration_number) VALUES (?, true, ?)",
                id, registrationNumber);
    }

    private String stored(long id) {
        return jdbc.queryForObject("SELECT registration_number FROM str.facility WHERE id = ?", String.class, id);
    }

    @Test
    void writeBack_fillsEmptyField() {
        facility(8001, null);

        assertThat(facilityRepository.writeBackRegistrationNumber(8001, NEW)).isEqualTo(1);
        assertThat(stored(8001)).isEqualTo(NEW);
    }

    /** Ponovno izdavanje nakon povlačenja: novi broj zamjenjuje povučeni (ako brisanje nije prošlo). */
    @Test
    void writeBack_replacesWithdrawnRn() {
        facility(8002, WITHDRAWN);

        assertThat(facilityRepository.writeBackRegistrationNumber(8002, NEW)).isEqualTo(1);
        assertThat(stored(8002)).isEqualTo(NEW);
    }

    @Test
    void writeBack_keepsStandingOrUnknownRn() {
        facility(8003, ACTIVE);
        facility(8004, FOREIGN);

        assertThat(facilityRepository.writeBackRegistrationNumber(8003, NEW)).isZero();
        assertThat(facilityRepository.writeBackRegistrationNumber(8004, NEW)).isZero();
        assertThat(stored(8003)).isEqualTo(ACTIVE);
        assertThat(stored(8004)).isEqualTo(FOREIGN);
    }

    /** Povlačenje briše samo upravo taj broj — tuđi ili noviji ostaje. */
    @Test
    void clear_removesOnlyThatRn() {
        facility(8005, WITHDRAWN);
        facility(8006, FOREIGN);

        assertThat(facilityRepository.clearRegistrationNumber(8005, WITHDRAWN)).isEqualTo(1);
        assertThat(facilityRepository.clearRegistrationNumber(8006, WITHDRAWN)).isZero();
        assertThat(stored(8005)).isNull();
        assertThat(stored(8006)).isEqualTo(FOREIGN);
    }

    /** Brisanje pri povlačenju pogađa objekt preko RB → smještaj → facility_id. */
    @Test
    void findFacilityIdByRn_followsRnToFacility() {
        assertThat(rnRepository.findFacilityIdByRn(WITHDRAWN)).contains("8007");
        assertThat(rnRepository.findFacilityIdByRn(ACTIVE)).isEmpty();
        assertThat(rnRepository.findFacilityIdByRn(FOREIGN)).isEmpty();
    }

    @Test
    void findWithdrawnRns_returnsOnlyOwnWithdrawn() {
        assertThat(rnRepository.findWithdrawnRns(List.of(WITHDRAWN, ACTIVE, FOREIGN)))
                .containsExactly(WITHDRAWN);
    }
}
