package com.str.backend.rn;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.dto.RnDetailExtras;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RnRepository#findDetailExtras} nad stvarnom pohranom: konstruktorski izraz, LEFT JOIN
 * na iznajmljivača preko submissiona i na državu prebivališta.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RnDetailExtrasQueryTest {

    private static final int CROATIA_ID = 191;

    @Autowired private RnRepository rnRepository;
    @Autowired private AccommodationRepository accommodationRepository;
    @Autowired private SubmissionRepository submissionRepository;
    @Autowired private LessorRepository lessorRepository;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void countries() {
        jdbc.update("DELETE FROM str.country");
        jdbc.update("INSERT INTO str.country (id, name, iso2_alpha, active) VALUES (?,?,?,?)",
                CROATIA_ID, "Hrvatska", "HR", true);
    }

    @Test
    void naturalPerson_carriesOfferingPurposeContactAndResidence() {
        LessorEntity l = LessorEntity.create("Ana", "Anić", "Ilica", "1", "Zagreb", "Grad Zagreb", null);
        l.setLessorOib("12312312316");
        l.applyContact("ana@example.hr", "Ana Anić", "01234567", "0911234567");
        l.applyCountryOfResidence(CROATIA_ID);
        String rn = seedRn("HR000000000000000001", lessorRepository.saveAndFlush(l).getLessorId(),
                OfferType.SECONDARY_RESIDENCE, Offering.PART);

        RnDetailExtras extras = rnRepository.findDetailExtras(rn).orElseThrow();

        assertThat(extras).isEqualTo(new RnDetailExtras(Offering.PART, OfferType.SECONDARY_RESIDENCE,
                "Ana Anić", "01234567", "0911234567",
                "Ilica", "1", "Zagreb", "Grad Zagreb", "Hrvatska"));
    }

    /** Za pravnu osobu su isti stupci sjedište, a kontakt je onaj zastupnika koji je podnio zahtjev. */
    @Test
    void legalEntity_carriesSeatAndRepresentativeContact() {
        LessorEntity l = LessorEntity.create("Ivo", "Ivić", "Korzo", "2", "Rijeka",
                "Primorsko-goranska županija", null);
        l.setLessorOib("33333333360");
        l.applyNiasLegalEntity("TESTNA TVRTKA d.o.o.", "080123456", "12312312316", "Ivo Ivić", null);
        l.applyContact("ured@tvrtka.hr", "Ivo Ivić", null, "0981111111");
        String rn = seedRn("HR000000000000000002", lessorRepository.saveAndFlush(l).getLessorId(),
                OfferType.OTHER, Offering.WHOLE);

        RnDetailExtras extras = rnRepository.findDetailExtras(rn).orElseThrow();

        assertThat(extras.offering()).isEqualTo(Offering.WHOLE);
        assertThat(extras.offerType()).isEqualTo(OfferType.OTHER);
        assertThat(extras.lessorStreet()).isEqualTo("Korzo");
        assertThat(extras.lessorPlace()).isEqualTo("Rijeka");
        assertThat(extras.lessorMobile()).isEqualTo("0981111111");
        assertThat(extras.lessorPhone()).isNull();
        assertThat(extras.lessorCountryOfResidenceName()).isNull();
    }

    /** Submission bez iznajmljivača ne smije sakriti RB — objektni dio ostaje, ostalo je null. */
    @Test
    void missingLessor_keepsAccommodationPart() {
        String rn = seedRn("HR000000000000000003", UUID.randomUUID(), OfferType.PRIMARY_RESIDENCE, Offering.WHOLE);

        RnDetailExtras extras = rnRepository.findDetailExtras(rn).orElseThrow();

        assertThat(extras).isEqualTo(new RnDetailExtras(Offering.WHOLE, OfferType.PRIMARY_RESIDENCE,
                null, null, null, null, null, null, null, null));
    }

    @Test
    void unknownRn_isEmpty() {
        assertThat(rnRepository.findDetailExtras("HR999999999999999999")).isEmpty();
    }

    private String seedRn(String rn, UUID lessorId, OfferType offerType, Offering offering) {
        SubmissionEntity submission = submissionRepository.saveAndFlush(
                SubmissionEntity.create("FN-" + rn, lessorId, 1L, Instant.now(), null, null));
        AccommodationEntity acc = AccommodationEntity.create(submission.getSubmissionId(),
                "Grad Zagreb", "Zagreb", "Vlaška", "5", 4, 4, offerType, offering, false, false, false);
        accommodationRepository.saveAndFlush(acc);
        rnRepository.saveAndFlush(RnEntity.issue(rn, submission.getSubmissionId(), acc.getAccommodationId(),
                LocalDate.now()));
        return rn;
    }
}
