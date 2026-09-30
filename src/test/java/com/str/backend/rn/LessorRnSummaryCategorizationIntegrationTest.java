package com.str.backend.rn;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.categorization.CategorizationDecisionEntity;
import com.str.backend.categorization.CategorizationDecisionEntity.CategorizationDecisionMetadata;
import com.str.backend.categorization.CategorizationDecisionRepository;
import com.str.backend.categorization.CategorizationDecisionStatus;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.domain.RnStatus;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.lessor.LessorRnSummaryDto;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stanje rješenja o kategorizaciji na „Mojim registracijskim brojevima" (NIAS): upit mora stvarno
 * proći kroz Hibernate, jer CASE/EXISTS u konstruktorskom izrazu jedinični test ne bi uhvatio.
 */
@SpringBootTest
@ActiveProfiles("test")
class LessorRnSummaryCategorizationIntegrationTest {

    private static final String OIB = "99999999990";
    private static final String OTHER_OIB = "11111111119";

    @Autowired private RnRepository rnRepository;
    @Autowired private AccommodationRepository accommodationRepository;
    @Autowired private SubmissionRepository submissionRepository;
    @Autowired private LessorRepository lessorRepository;
    @Autowired private CategorizationDecisionRepository decisionRepository;

    @BeforeEach
    void seed() {
        decisionRepository.deleteAll();
        rnRepository.deleteAll();
        accommodationRepository.deleteAll();
        submissionRepository.deleteAll();
        lessorRepository.deleteAll();

        UUID own = lessor(OIB);
        seedRn("HR00000011", own, null);        // novi objekt, bez rješenja
        seedRn("HR00000012", own, null);        // novi objekt, predano rješenje
        seedRn("HR00000013", own, null);        // novi objekt, odbijeno pa ponovo predano
        seedRn("HR00000014", own, null);        // novi objekt, samo odbijeno
        seedRn("HR00000015", own, "153049");    // objekt iz eTurizma
        seedRn("HR00000016", lessor(OTHER_OIB), null);
        seedRn("HR00000017", own, "  ");         // novi objekt (prazan facilityId), povučen
        RnEntity withdrawn = rnRepository.findById("HR00000017").orElseThrow();
        withdrawn.applyStatus(RnStatus.WITHDRAWN);
        rnRepository.save(withdrawn);

        decision("HR00000012");
        CategorizationDecisionEntity rejected = decision("HR00000013");
        rejected.reject("sluzbenik");
        decisionRepository.save(rejected);
        decision("HR00000013");
        CategorizationDecisionEntity onlyRejected = decision("HR00000014");
        onlyRejected.reject("sluzbenik");
        decisionRepository.save(onlyRejected);
    }

    @Test
    void summary_carriesCategorizationRequirementAndStatus() {
        Map<String, LessorRnSummaryDto> byRn = rnRepository.findByLessorOib(OIB).stream()
                .collect(Collectors.toMap(LessorRnSummaryDto::rn, Function.identity()));

        assertThat(byRn).containsOnlyKeys(
                "HR00000011", "HR00000012", "HR00000013", "HR00000014", "HR00000015", "HR00000017");

        assertThat(byRn.get("HR00000011").categorizationRequired()).isTrue();
        // Povučen RB rješenje ne prima (409), pa ga popis ni ne traži.
        assertThat(byRn.get("HR00000017").categorizationRequired()).isFalse();
        assertThat(byRn.get("HR00000011").categorizationStatus()).isNull();

        assertThat(byRn.get("HR00000012").categorizationStatus()).isEqualTo(CategorizationDecisionStatus.SUBMITTED);
        // Aktivno rješenje ima prednost pred ranije odbijenim.
        assertThat(byRn.get("HR00000013").categorizationStatus()).isEqualTo(CategorizationDecisionStatus.SUBMITTED);
        assertThat(byRn.get("HR00000014").categorizationStatus()).isEqualTo(CategorizationDecisionStatus.REJECTED);

        assertThat(byRn.get("HR00000015").categorizationRequired()).isFalse();
        assertThat(byRn.get("HR00000015").categorizationStatus()).isNull();
    }

    /** Non-EU popis stanje rješenja ne računa — konstruktor s osam argumenata daje zadane vrijednosti. */
    @Test
    void nonEuSummary_defaultsCategorizationFields() {
        List<LessorRnSummaryDto> rows = rnRepository.findByLessorId(
                lessorRepository.findAll().stream()
                        .filter(l -> OIB.equals(l.getLessorOib()))
                        .findFirst().orElseThrow().getLessorId());

        assertThat(rows).isNotEmpty().allSatisfy(r -> {
            assertThat(r.categorizationRequired()).isFalse();
            assertThat(r.categorizationStatus()).isNull();
        });
    }

    private UUID lessor(String oib) {
        LessorEntity l = LessorEntity.create("Ivan", "Ivić", "Korzo", "2", "Rijeka",
                "Primorsko-goranska županija", oib + "@example.hr");
        l.setLessorOib(oib);
        return lessorRepository.save(l).getLessorId();
    }

    private void seedRn(String rn, UUID lessorId, String facilityId) {
        SubmissionEntity submission = submissionRepository.save(
                SubmissionEntity.create("FN-" + rn, lessorId, 1L, Instant.now(), null, null));
        AccommodationEntity acc = AccommodationEntity.create(submission.getSubmissionId(),
                "Primorsko-goranska županija", "Rijeka", "Korzo", rn.substring(rn.length() - 2),
                4, 8, OfferType.PRIMARY_RESIDENCE, Offering.WHOLE, true, false, true);
        acc.setName("Objekt " + rn);
        acc.setFacilityId(facilityId);
        accommodationRepository.save(acc);
        rnRepository.save(RnEntity.issue(rn, submission.getSubmissionId(), acc.getAccommodationId(), LocalDate.now()));
    }

    private CategorizationDecisionEntity decision(String rn) {
        return decisionRepository.save(CategorizationDecisionEntity.create(OIB, rn, "rjesenje.pdf",
                "application/pdf", new byte[]{1, 2, 3},
                new CategorizationDecisionMetadata(null, null, null, null, null, null, null)));
    }
}
