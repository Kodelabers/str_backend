package com.str.backend.activity;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.activity.AccommodationActivityService.IngestResult;
import com.str.backend.activity.dto.SdepIngestRequest;
import com.str.backend.admin.AdminAuditService;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.RnStatus;
import com.str.backend.domain.Offering;
import com.str.backend.guest.GuestCountryNames;
import com.str.backend.guest.GuestEntity;
import com.str.backend.guest.GuestRepository;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Predaja aktivnosti iz SDEP-a nad stvarnom pohranom (T6, T7, T9): ispravci, otkazivanja, gosti po
 * državama i oznaka valjanosti broja.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AccommodationActivityService.class, GuestCountryNames.class})
class AccommodationActivityIngestTest {

    private static final long AIRBNB = 1L;
    private static final long BOOKING = 2L;
    private static final String RN = "HR000000000000000021";
    private static final String ACTIVITY = "550e8400-e29b-41d4-a716-446655440000";
    private static final OffsetDateTime V1 = OffsetDateTime.of(2026, 9, 1, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime V2 = V1.plusDays(2);

    @Autowired private AccommodationActivityService service;
    @Autowired private AccommodationActivityRepository activities;
    @Autowired private GuestRepository guests;
    @Autowired private RnRepository rnRepository;
    @Autowired private AccommodationRepository accommodationRepository;
    @Autowired private SubmissionRepository submissionRepository;
    @Autowired private EntityManager em;

    @MockBean private AdminAuditService auditService;

    @BeforeEach
    void seed() {
        SubmissionEntity submission = submissionRepository.saveAndFlush(
                SubmissionEntity.create("FN-" + RN, UUID.randomUUID(), 1L, Instant.now(), null, null));
        AccommodationEntity acc = AccommodationEntity.create(submission.getSubmissionId(), "Grad Zagreb", "Zagreb",
                "Ilica", "24", 4, 4, OfferType.PRIMARY_RESIDENCE, Offering.WHOLE, false, false, false);
        accommodationRepository.saveAndFlush(acc);
        rnRepository.saveAndFlush(RnEntity.issue(RN, submission.getSubmissionId(), acc.getAccommodationId(),
                LocalDate.now()));
    }

    @Test
    void firstVersion_isInsertedWithGuestsAndListingData() {
        IngestResult result = service.ingest(AIRBNB, List.of(entry(V1, "finished", 3, guest("DEU", 2), guest("N/A", 1))));

        assertThat(result).isEqualTo(new IngestResult(1, 1, 0, 0));
        AccommodationActivityEntity saved = only();
        assertThat(saved.getSdepActivityId()).isEqualTo(ACTIVITY);
        assertThat(saved.getSdepVersion()).isEqualTo(V1.toInstant());
        assertThat(saved.isCancelled()).isFalse();
        assertThat(saved.getRnCheck()).isEqualTo(RnCheck.VALID);
        assertThat(saved.getUrl()).isEqualTo("https://www.airbnb.com/rooms/1");
        assertThat(saved.getFullAddress()).isEqualTo("Ilica 24, 10000 Zagreb");
        assertThat(guests.findByActivityId(saved.getActivityId()))
                .extracting(GuestEntity::getCountryCode, GuestEntity::getCountry, GuestEntity::getGuestCount)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("DEU", "Njemačka", 2),
                        org.assertj.core.groups.Tuple.tuple("N/A", GuestCountryNames.UNKNOWN_NAME, 1));
    }

    @Test
    void newerVersion_replacesRowAndGuests() {
        service.ingest(AIRBNB, List.of(entry(V1, "finished", 3, guest("DEU", 2))));
        flushAndClear();

        IngestResult result = service.ingest(AIRBNB, List.of(entry(V2, "finished", 5, guest("ITA", 4))));
        flushAndClear();

        assertThat(result).isEqualTo(new IngestResult(1, 0, 1, 0));
        AccommodationActivityEntity saved = only();
        assertThat(saved.getNumberOfNights()).isEqualTo(5);
        assertThat(saved.getSdepVersion()).isEqualTo(V2.toInstant());
        assertThat(guests.findByActivityId(saved.getActivityId()))
                .extracting(GuestEntity::getCountryCode).containsExactly("ITA");
    }

    @Test
    void sameOrOlderVersion_changesNothing() {
        service.ingest(AIRBNB, List.of(entry(V2, "finished", 3, guest("DEU", 2))));
        flushAndClear();

        IngestResult same = service.ingest(AIRBNB, List.of(entry(V2, "finished", 9)));
        IngestResult older = service.ingest(AIRBNB, List.of(entry(V1, "finished", 9)));
        flushAndClear();

        assertThat(same).isEqualTo(new IngestResult(1, 0, 0, 1));
        assertThat(older).isEqualTo(new IngestResult(1, 0, 0, 1));
        assertThat(only().getNumberOfNights()).isEqualTo(3);
    }

    @Test
    void repeatedActivityInOnePackage_newestWins() {
        IngestResult result = service.ingest(AIRBNB, List.of(
                entry(V2, "finished", 5), entry(V1, "finished", 3)));
        flushAndClear();

        assertThat(result).isEqualTo(new IngestResult(2, 1, 0, 1));
        assertThat(only().getNumberOfNights()).isEqualTo(5);
    }

    @Test
    void cancellationBeforeOriginal_staysCancelled() {
        service.ingest(AIRBNB, List.of(entry(V2, "cancelled", 3)));
        flushAndClear();

        IngestResult late = service.ingest(AIRBNB, List.of(entry(V1, "finished", 3)));
        flushAndClear();

        assertThat(late.unchanged()).isEqualTo(1);
        assertThat(only().isCancelled()).isTrue();
    }

    @Test
    void sameSdepIdOnAnotherPlatform_isAnotherActivity() {
        service.ingest(AIRBNB, List.of(entry(V1, "finished", 3)));
        IngestResult result = service.ingest(BOOKING, List.of(entry(V1, "finished", 3)));

        assertThat(result.inserted()).isEqualTo(1);
        assertThat(activities.findAll()).hasSize(2);
    }

    /** Broj koji nije naš ne ruši paket, nego se sprema s oznakom (T7). */
    @Test
    void foreignNumbers_areStoredAndMarked() {
        String unknown = "HR999999999999999999";
        String malformed = "NL-0363-0000-1234-5678-90AB-CDEF";
        service.ingest(AIRBNB, List.of(
                entry("a-1", V1, unknown), entry("a-2", V1, malformed), entry("a-3", V1, RN)));

        assertThat(activities.findAll())
                .extracting(AccommodationActivityEntity::getRn, AccommodationActivityEntity::getRnCheck)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(unknown, RnCheck.UNKNOWN),
                        org.assertj.core.groups.Tuple.tuple(malformed, RnCheck.INVALID_FORMAT),
                        org.assertj.core.groups.Tuple.tuple(RN, RnCheck.VALID));
    }

    @Test
    void suspendedNumber_isMarkedNotActive() {
        RnEntity rn = rnRepository.findById(RN).orElseThrow();
        ReflectionTestUtils.setField(rn, "status", RnStatus.SUSPENDED);
        rnRepository.saveAndFlush(rn);

        service.ingest(AIRBNB, List.of(entry(V1, "finished", 3)));

        assertThat(only().getRnCheck()).isEqualTo(RnCheck.NOT_ACTIVE);
    }

    /** Unos bez ID-ja iz SDEP-a (ručni ingest) dodaje se kao i dosad, svaki put. */
    @Test
    void entryWithoutSdepId_isAlwaysInserted() {
        SdepIngestRequest.Entry plain = entry(null, null, RN);
        service.ingest(AIRBNB, List.of(plain));
        service.ingest(AIRBNB, List.of(plain));

        assertThat(activities.findAll()).hasSize(2);
    }

    private AccommodationActivityEntity only() {
        List<AccommodationActivityEntity> all = activities.findAll();
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    private static SdepIngestRequest.Entry entry(OffsetDateTime version, String status, int nights,
                                                 SdepIngestRequest.Guest... guests) {
        SdepIngestRequest.Entry e = entry(ACTIVITY, version, RN);
        e.setStatus(status);
        e.setNumberOfNights(nights);
        e.setGuests(List.of(guests));
        return e;
    }

    private static SdepIngestRequest.Entry entry(String sdepId, OffsetDateTime version, String rn) {
        SdepIngestRequest.Entry e = new SdepIngestRequest.Entry();
        e.setSdepActivityId(sdepId);
        e.setSdepVersion(version);
        e.setStatus("finished");
        e.setRn(rn);
        e.setPeriodFrom(LocalDate.of(2026, 9, 5));
        e.setPeriodTo(LocalDate.of(2026, 9, 8));
        e.setNumberOfNights(3);
        e.setNumberOfGuests(2);
        e.setUrl("https://www.airbnb.com/rooms/1");
        e.setFullAddress("Ilica 24, 10000 Zagreb");
        return e;
    }

    private static SdepIngestRequest.Guest guest(String country, int count) {
        SdepIngestRequest.Guest g = new SdepIngestRequest.Guest();
        g.setCountry(country);
        g.setCount(count);
        return g;
    }
}
