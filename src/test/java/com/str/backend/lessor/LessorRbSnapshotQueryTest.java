package com.str.backend.lessor;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LessorRepository#findLatestRbSnapshot} nad stvarnom pohranom: RB → submission →
 * snapshot iznajmljivača, prednost RB-u istog objekta, bez tvrtki, tuđih RB-ova i opozvanih RB-ova
 * kojima je istekla retencija.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class LessorRbSnapshotQueryTest {

    private static final String OIB = "12312312316";
    private static final String OTHER_OIB = "33333333360";
    private static final LocalDate CUTOFF = LocalDate.of(2025, 4, 28);

    @Autowired private LessorRepository lessorRepository;
    @Autowired private RnRepository rnRepository;
    @Autowired private AccommodationRepository accommodationRepository;
    @Autowired private SubmissionRepository submissionRepository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void latestRb_winsWithoutObject() {
        seed("HR000000000000000011", OIB, "0910000001", null, Instant.parse("2026-01-01T10:00:00Z"));
        seed("HR000000000000000012", OIB, "0910000002", null, Instant.parse("2026-03-01T10:00:00Z"));

        assertThat(mobile(lessorRepository.findLatestRbSnapshot(OIB, null, null, CUTOFF))).isEqualTo("0910000002");
    }

    @Test
    void sameFacility_winsOverNewerRb() {
        seed("HR000000000000000021", OIB, "0910000001", "F-1", Instant.parse("2026-01-01T10:00:00Z"));
        seed("HR000000000000000022", OIB, "0910000002", "F-2", Instant.parse("2026-03-01T10:00:00Z"));

        assertThat(mobile(lessorRepository.findLatestRbSnapshot(OIB, "F-1", null, CUTOFF))).isEqualTo("0910000001");
    }

    @Test
    void withdrawnRbOfReissue_winsOverNewerRb() {
        seed("HR000000000000000031", OIB, "0910000001", null, Instant.parse("2026-01-01T10:00:00Z"));
        seed("HR000000000000000032", OIB, "0910000002", null, Instant.parse("2026-03-01T10:00:00Z"));

        assertThat(mobile(lessorRepository.findLatestRbSnapshot(OIB, null, "HR000000000000000031", CUTOFF)))
                .isEqualTo("0910000001");
    }

    /** Tuđi objekt ili RB je samo redoslijed: ne izlazi, a vlastiti zadnji RB ostaje. */
    @Test
    void foreignObjectOrRb_neverLeaks() {
        seed("HR000000000000000041", OTHER_OIB, "0919999999", "F-9", Instant.parse("2026-05-01T10:00:00Z"));
        seed("HR000000000000000042", OIB, "0910000001", null, Instant.parse("2026-01-01T10:00:00Z"));

        assertThat(mobile(lessorRepository.findLatestRbSnapshot(OIB, "F-9", "HR000000000000000041", CUTOFF)))
                .isEqualTo("0910000001");
    }

    /** Snapshot tvrtke nosi kontakt tadašnjeg zastupnika — ne nudi se. */
    @Test
    void legalEntitySnapshot_isSkipped() {
        LessorEntity company = LessorEntity.create("Ivo", "Ivić", "Korzo", "2", "Rijeka", "Primorsko-goranska", null);
        company.setLessorOib(OTHER_OIB);
        company.applyNiasLegalEntity("TESTNA TVRTKA d.o.o.", "080123456", "12312312316", "Ivo Ivić", null);
        company.applyContact("ivo@tvrtka.hr", "Ivo Ivić", null, "0981111111");
        seedRn("HR000000000000000051", lessorRepository.saveAndFlush(company), null, Instant.now());

        assertThat(lessorRepository.findLatestRbSnapshot(OTHER_OIB, null, null, CUTOFF)).isEmpty();
    }

    /** Opozvan RB unutar retencije nosi korisnikov zadnji kontakt; rok je isti kao za ponovni zahtjev. */
    @Test
    void terminalRbWithinRetention_isUsed() {
        seed("HR000000000000000061", OIB, "0910000001", null, Instant.parse("2026-01-01T10:00:00Z"));
        seed("HR000000000000000062", OIB, "0910000002", null, Instant.parse("2026-03-01T10:00:00Z"));
        terminate("HR000000000000000062", "REVOKED", CUTOFF);

        assertThat(mobile(lessorRepository.findLatestRbSnapshot(OIB, null, null, CUTOFF))).isEqualTo("0910000002");
    }

    /** Čl. 5. st. 5: nakon retencije se podaci opozvanog ili povučenog RB-a više ne koriste, ni bez datuma. */
    @Test
    void terminalRbPastRetentionOrWithoutDate_isSkipped() {
        seed("HR000000000000000071", OIB, "0910000001", null, Instant.parse("2024-01-01T10:00:00Z"));
        seed("HR000000000000000072", OIB, "0910000002", "F-7", Instant.parse("2024-06-01T10:00:00Z"));
        seed("HR000000000000000073", OIB, "0910000003", null, Instant.parse("2024-09-01T10:00:00Z"));
        terminate("HR000000000000000072", "WITHDRAWN", CUTOFF.minusDays(1));
        terminate("HR000000000000000073", "REVOKED", null);

        // Ni prednost objekta (F-7) ni ponovnog zahtjeva ne vraća istekli RB.
        assertThat(mobile(lessorRepository.findLatestRbSnapshot(OIB, "F-7", "HR000000000000000073", CUTOFF)))
                .isEqualTo("0910000001");
    }

    @Test
    void noRb_isEmpty() {
        assertThat(lessorRepository.findLatestRbSnapshot("98765432106", null, null, CUTOFF)).isEmpty();
    }

    private static String mobile(Optional<LessorEntity> l) {
        return l.map(LessorEntity::getMobileNumber).orElse(null);
    }

    private void terminate(String rn, String status, LocalDate validTo) {
        jdbc.update("UPDATE str_rn.registration_number SET status = ?, valid_to = ? WHERE rn = ?",
                status, validTo, rn);
    }

    private void seed(String rn, String oib, String mobile, String facilityId, Instant issuedAt) {
        LessorEntity l = LessorEntity.create("Ana", "Anić", "Ilica", "1", "Zagreb", "Grad Zagreb", null);
        l.setLessorOib(oib);
        l.applyContact("ana@example.hr", null, null, mobile);
        seedRn(rn, lessorRepository.saveAndFlush(l), facilityId, issuedAt);
    }

    private void seedRn(String rn, LessorEntity lessor, String facilityId, Instant issuedAt) {
        SubmissionEntity submission = submissionRepository.saveAndFlush(
                SubmissionEntity.create("FN-" + rn, lessor.getLessorId(), 1L, Instant.now(), null, null));
        AccommodationEntity acc = AccommodationEntity.create(submission.getSubmissionId(),
                "Grad Zagreb", "Zagreb", "Vlaška", "5", 4, 4, OfferType.PRIMARY_RESIDENCE, Offering.WHOLE,
                false, false, false);
        acc.setFacilityId(facilityId);
        accommodationRepository.saveAndFlush(acc);
        rnRepository.saveAndFlush(RnEntity.issue(rn, submission.getSubmissionId(), acc.getAccommodationId(),
                LocalDate.now()));
        // created_at postavlja issue() i nije izmjenjiv kroz entitet — redoslijed se namješta izravno.
        jdbc.update("UPDATE str_rn.registration_number SET created_at = ? WHERE rn = ?", Timestamp.from(issuedAt), rn);
    }
}
