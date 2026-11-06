package com.str.backend.rn;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.dto.RnAccommodationStatsDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link RnRepository#findAccommodationStats} (T4) nad stvarnom pohranom. */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AccommodationStatsQueryTest {

    @Autowired private RnRepository rnRepository;
    @Autowired private AccommodationRepository accommodationRepository;
    @Autowired private SubmissionRepository submissionRepository;

    @Test
    void returnsMunicipalityAndBedsForKnownNumbersOnly() {
        seedRn("HR000000000000000011", "Split", 6);
        seedRn("HR000000000000000012", "Pula", 2);

        List<RnAccommodationStatsDto> stats = rnRepository.findAccommodationStats(
                List.of("HR000000000000000011", "HR000000000000000012", "HR999999999999999999"));

        assertThat(stats).containsExactlyInAnyOrder(
                new RnAccommodationStatsDto("HR000000000000000011", "Split", 6),
                new RnAccommodationStatsDto("HR000000000000000012", "Pula", 2));
    }

    private void seedRn(String rn, String city, int beds) {
        SubmissionEntity submission = submissionRepository.saveAndFlush(
                SubmissionEntity.create("FN-" + rn, UUID.randomUUID(), 1L, Instant.now(), null, null));
        AccommodationEntity acc = AccommodationEntity.create(submission.getSubmissionId(),
                "Splitsko-dalmatinska županija", city, "Riva", "1", beds, beds, OfferType.PRIMARY_RESIDENCE,
                Offering.WHOLE, false, false, false);
        accommodationRepository.saveAndFlush(acc);
        rnRepository.saveAndFlush(RnEntity.issue(rn, submission.getSubmissionId(), acc.getAccommodationId(),
                LocalDate.now()));
    }
}
