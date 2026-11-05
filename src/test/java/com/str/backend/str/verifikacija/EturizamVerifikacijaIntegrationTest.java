package com.str.backend.str.verifikacija;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.address.CountyRepository;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.SubjectProfileService;
import com.str.backend.registration.dto.RegistrationRequest;
import com.str.backend.rn.RnRepository;
import com.str.backend.str.FacilityClaimVerifier;
import com.str.backend.str.StrFacilityRepository.FacilityOwnershipRow;
import com.str.backend.str.verifikacija.EturizamVerifikacijaWriter.Pokusaj;
import com.str.backend.str.verifikacija.VerifikacijaOutboxEntity.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.orm.jpa.EntityManagerHolder;

import javax.sql.DataSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Zahtjev za žurnu verifikaciju (T8) na pravoj bazi (H2): outbox u transakciji izdavanja RB-a,
 * atomaran upis u {@code str.verification_request} + {@code str.korisnicka_obavijest}, retry i
 * odustajanje.
 *
 * <p>eTurizmove tablice test stvara sam, strukturom kao na CDU testu (bez FK-ova, da dijeljena H2
 * baza ne veže ostale testove). Rezervirani id-evi 951000–951999. {@code REQUIRES_NEW} upisi se ne
 * poništavaju, pa se čiste ručno. Provjera vlasništva objekta ({@link FacilityClaimVerifier}) je
 * zamijenjena kao u {@code RegistrationExistingFacilityIntegrationTest}.
 */
@SpringBootTest(properties = {
        "str.eturizam.verifikacija.enabled=true",
        "str.eturizam.verifikacija.obavijesti.enabled=true",
        // Job se zove ručno; raspored bi ga pokrenuo usred testa.
        "str.eturizam.verifikacija.retry.cron=-"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EturizamVerifikacijaIntegrationTest {

    private static final String OIB = "12312312316";

    /** Jedinica s dvoje aktivnih internih službenika (+ neaktivni, vanjski i službenik druge jedinice). */
    private static final long UNIT = 951500;
    private static final long OTHER_UNIT = 951501;
    /** Jedinica bez korisnika. */
    private static final long EMPTY_UNIT = 951599;
    /** Jedinica u kojoj upis obavijesti drugom službeniku pada (CHECK u testnoj tablici). */
    private static final long POISONED_UNIT = 951502;
    private static final long POISONED_USER = 951999;

    private static final ZoneId ZAGREB = ZoneId.of("Europe/Zagreb");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private VerifikacijaOutbox outbox;
    @Autowired private VerifikacijaOutboxRepository outboxRepository;
    @Autowired private EturizamVerifikacijaService service;
    @Autowired private EturizamVerifikacijaWriter writer;
    @Autowired private EturizamVerifikacijaRetryJob retryJob;
    @Autowired private EturizamVerifikacijaRepository eturizamRepository;
    @Autowired private AccommodationRepository accommodationRepository;
    @Autowired private EturizamVerifikacijaProperties properties;
    @Autowired private RnRepository rnRepository;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private Clock clock;
    @Autowired private DataSource dataSource;
    @Autowired private jakarta.persistence.EntityManagerFactory entityManagerFactory;

    @MockBean private SubjectProfileService subjectProfileService;
    @MockBean private CountyRepository countyRepository;
    @MockBean private FacilityClaimVerifier facilityClaimVerifier;

    @BeforeEach
    void setUp() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS str.document (id BIGINT PRIMARY KEY, active BOOLEAN, business_case_id BIGINT)");
        jdbc.execute("ALTER TABLE str.document ADD COLUMN IF NOT EXISTS jurisdiction_organizational_unit_id BIGINT");
        jdbc.execute("CREATE TABLE IF NOT EXISTS str.application_user (id BIGINT PRIMARY KEY, active BOOLEAN NOT NULL, "
                + "username VARCHAR(255) NOT NULL, internal BOOLEAN, first_name VARCHAR(255), last_name VARCHAR(255))");
        jdbc.execute("ALTER TABLE str.application_user ADD COLUMN IF NOT EXISTS organizational_unit_id BIGINT");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS str.verification_request (
                  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                  created_by VARCHAR(255) NOT NULL,
                  created_date TIMESTAMP NOT NULL,
                  last_modified_by VARCHAR(255) NOT NULL,
                  last_modified_date TIMESTAMP NOT NULL,
                  active BOOLEAN DEFAULT TRUE NOT NULL,
                  source_document_id BIGINT NOT NULL,
                  CONSTRAINT uq_verification_request_source_document UNIQUE (source_document_id))
                """);
        // jsonb ne postoji u H2; domena daje isti CAST(? AS jsonb) kao na PostgreSQL-u.
        jdbc.execute("CREATE DOMAIN IF NOT EXISTS jsonb AS VARCHAR(4000)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS str.korisnicka_obavijest (
                  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                  application_user_id BIGINT NOT NULL,
                  vrsta VARCHAR(64) NOT NULL,
                  tekst VARCHAR(1000) NOT NULL,
                  objekt_tip VARCHAR(64),
                  objekt_id BIGINT,
                  podaci VARCHAR(4000),
                  stvoreno TIMESTAMP NOT NULL,
                  procitano TIMESTAMP,
                  CONSTRAINT ck_test_otrovan_primatelj CHECK (application_user_id <> 951999))
                """);
        cleanUp();

        user(951001, UNIT, true, true);
        user(951002, UNIT, true, true);
        user(951003, UNIT, false, true);
        user(951004, UNIT, true, false);
        user(951005, OTHER_UNIT, true, true);
        user(951006, POISONED_UNIT, true, true);
        user(POISONED_USER, POISONED_UNIT, true, true);

        when(subjectProfileService.resolveLessor(any(), any(), any(), any())).thenAnswer(inv -> {
            LessorEntity l = LessorEntity.create("PERO", "PERIĆ",
                    "Ilica", "1", "Zagreb", "Grad Zagreb", "pero.peric@example.hr");
            l.setLessorOib((String) inv.getArgument(0));
            return l;
        });
        when(countyRepository.findAll()).thenReturn(List.of());
    }

    /** Dijeljena H2 baza: REQUIRES_NEW upisi se ne poništavaju, pa se čiste ručno (samo naši id-evi). */
    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM str.korisnicka_obavijest WHERE application_user_id BETWEEN 951000 AND 951999");
        jdbc.update("DELETE FROM str.verification_request WHERE source_document_id BETWEEN 951000 AND 951999");
        jdbc.update("DELETE FROM str.application_user WHERE id BETWEEN 951000 AND 951999");
        jdbc.update("DELETE FROM str.document WHERE id BETWEEN 951000 AND 951999");
        jdbc.update("DELETE FROM str_rn.eturizam_verifikacija WHERE document_id BETWEEN 951000 AND 951999");
    }

    // ---------------------------------------------------------------- puni tok kroz izdavanje RB-a

    @Test
    void unverifiedFacility_issuance_writesRequestAndNotifiesActiveInternalUsersOfUnit() throws Exception {
        document(951001, UNIT);
        unverifiedFacility("951101", 951001L);

        String rn = submit("951101");

        VerifikacijaOutboxEntity zapis = outboxRepository.findByRn(rn).orElseThrow();
        assertThat(zapis.getStatus()).isEqualTo(Status.ZAHTJEV_UPISAN);
        assertThat(zapis.getDocumentId()).isEqualTo(951001L);
        assertThat(zapis.getFacilityId()).isEqualTo("951101");
        assertThat(zapis.getObavijestiPoslano()).isEqualTo(2);
        assertThat(zapis.getAttempts()).isZero();
        assertThat(zapis.getNextAttemptAt()).isNull();

        Map<String, Object> zahtjev = jdbc.queryForMap(
                "SELECT * FROM str.verification_request WHERE source_document_id = 951001");
        assertThat(zahtjev.get("ID")).isEqualTo(zapis.getVerificationRequestId());
        assertThat(zahtjev.get("CREATED_BY")).isEqualTo("STR");
        assertThat(zahtjev.get("LAST_MODIFIED_BY")).isEqualTo("STR");
        assertThat(zahtjev.get("ACTIVE")).isEqualTo(true);
        assertThat(((java.sql.Timestamp) zahtjev.get("CREATED_DATE")).toLocalDateTime())
                .isCloseTo(LocalDateTime.now(ZAGREB), within(1, java.time.temporal.ChronoUnit.MINUTES));

        List<Map<String, Object>> obavijesti = jdbc.queryForList(
                "SELECT * FROM str.korisnicka_obavijest WHERE application_user_id BETWEEN 951000 AND 951999 ORDER BY application_user_id");
        assertThat(obavijesti).extracting(o -> o.get("APPLICATION_USER_ID")).containsExactly(951001L, 951002L);
        Map<String, Object> prva = obavijesti.get(0);
        assertThat(prva.get("VRSTA")).isEqualTo(EturizamVerifikacijaProperties.Obavijesti.DEFAULT_VRSTA);
        assertThat(prva.get("OBJEKT_TIP")).isEqualTo(EturizamVerifikacijaProperties.Obavijesti.DEFAULT_OBJEKT_TIP);
        assertThat(prva.get("OBJEKT_ID")).isEqualTo(zapis.getVerificationRequestId());
        assertThat(prva.get("PROCITANO")).isNull();
        assertThat((String) prva.get("TEKST"))
                .isEqualTo("Smještajnom objektu „Apartman Marija“ (Makarska, ID 951101) dodijeljen je "
                        + "registracijski broj " + rn + ". Objekt nije verificiran – potrebna je žurna verifikacija.");
        JsonNode podaci = om.readTree((String) prva.get("PODACI"));
        assertThat(podaci.get("rn").asText()).isEqualTo(rn);
        assertThat(podaci.get("documentId").asLong()).isEqualTo(951001L);
        assertThat(podaci.get("facilityId").asLong()).isEqualTo(951101L);
        assertThat(podaci.get("verificationRequestId").asLong()).isEqualTo(zapis.getVerificationRequestId());
    }

    /** Upis u eTurizam padne (obavijest) — RB je izdan i commitan, pokušaj zabilježen za retry. */
    @Test
    void eturizamWriteFails_issuanceUnaffected_failureRecorded() throws Exception {
        document(951002, POISONED_UNIT);
        unverifiedFacility("951102", 951002L);

        String rn = submit("951102");

        assertThat(rnRepository.findById(rn)).isPresent();
        VerifikacijaOutboxEntity zapis = outboxRepository.findByRn(rn).orElseThrow();
        assertThat(zapis.getStatus()).isEqualTo(Status.PENDING);
        assertThat(zapis.getAttempts()).isEqualTo(1);
        assertThat(zapis.getLastError()).isNotBlank();
        assertThat(requestCount(951002)).isZero();
    }

    @Test
    void verifiedFacility_issuance_leavesNoTrace() throws Exception {
        document(951003, UNIT);
        FacilityOwnershipRow facility = facilityRow(951003L);
        when(facility.getVerified()).thenReturn(true);
        when(facilityClaimVerifier.verify(eq(OIB), eq("951103"), any())).thenReturn(Optional.of(facility));

        String rn = submit("951103");

        assertThat(outboxRepository.findByRn(rn)).isEmpty();
        assertThat(requestCount(951003)).isZero();
        assertThat(notificationCount()).isZero();
    }

    // ---------------------------------------------------------------- bilježenje (outbox)

    @Test
    void zabiljezi_skipsNewFacilityVerifiedAndMissingDocument() {
        FacilityOwnershipRow verified = mock(FacilityOwnershipRow.class);
        when(verified.getVerified()).thenReturn(true);
        when(verified.getDocumentId()).thenReturn(951004L);
        FacilityOwnershipRow withoutDocument = mock(FacilityOwnershipRow.class);
        when(withoutDocument.getVerified()).thenReturn(false);
        when(withoutDocument.getDocumentId()).thenReturn(null);
        FacilityOwnershipRow unknownAuthor = mock(FacilityOwnershipRow.class);
        when(unknownAuthor.getVerified()).thenReturn(null);
        when(unknownAuthor.getDocumentId()).thenReturn(951004L);

        requiresNew().executeWithoutResult(tx -> {
            outbox.zabiljezi("HR000000000000951001", UUID.randomUUID(), null, null);
            outbox.zabiljezi("HR000000000000951002", UUID.randomUUID(), "951104", verified);
            outbox.zabiljezi("HR000000000000951003", UUID.randomUUID(), "951104", withoutDocument);
            outbox.zabiljezi("HR000000000000951004", UUID.randomUUID(), "951104", unknownAuthor);
        });

        assertThat(outboxRepository.findAll()).noneMatch(e -> e.getRn().startsWith("HR000000000000951"));
    }

    /** Redak nastaje samo u transakciji koja izdaje RB — sam za sebe je programerska greška. */
    @Test
    void zabiljezi_outsideTransaction_fails() {
        FacilityOwnershipRow facility = mock(FacilityOwnershipRow.class);
        when(facility.getVerified()).thenReturn(false);
        when(facility.getDocumentId()).thenReturn(951004L);

        assertThatThrownBy(() -> outbox.zabiljezi("HR000000000000951014", UUID.randomUUID(), "951114", facility))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    /** Upis u eTurizam iz bilo koje transakcije osim pokušaja (npr. one koja izdaje RB) odbija se. */
    @Test
    void eturizamRepository_rejectsForeignTransaction() {
        assertThatThrownBy(() -> requiresNew().executeWithoutResult(tx -> eturizamRepository.findRequestId(951001)))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EturizamVerifikacijaWriter");
    }

    // ---------------------------------------------------------------- upis u eTurizam

    /** Zatečeni zahtjev (i neaktivan) se ne dira i službenike ne obavještava STR. */
    @Test
    void existingRequestForDocument_isNotTouched_noNotifications() {
        document(951005, UNIT);
        LocalDateTime staro = LocalDateTime.of(2026, 10, 1, 8, 0);
        jdbc.update("INSERT INTO str.verification_request (created_by, created_date, last_modified_by, "
                + "last_modified_date, active, source_document_id) VALUES ('12345678903', ?, '12345678903', ?, FALSE, 951005)",
                staro, staro);
        long postojeci = jdbc.queryForObject(
                "SELECT id FROM str.verification_request WHERE source_document_id = 951005", Long.class);
        String rn = pending("HR000000000000951005", "951105", 951005);

        service.obradi(Pokusaj.inline(rn));

        VerifikacijaOutboxEntity zapis = outboxRepository.findByRn(rn).orElseThrow();
        assertThat(zapis.getStatus()).isEqualTo(Status.ZAHTJEV_POSTOJAO);
        assertThat(zapis.getVerificationRequestId()).isEqualTo(postojeci);
        assertThat(zapis.getObavijestiPoslano()).isZero();
        assertThat(requestCount(951005)).isEqualTo(1);
        Map<String, Object> zahtjev = jdbc.queryForMap("SELECT * FROM str.verification_request WHERE id = ?", postojeci);
        assertThat(zahtjev.get("ACTIVE")).isEqualTo(false);
        assertThat(zahtjev.get("LAST_MODIFIED_BY")).isEqualTo("12345678903");
        assertThat(notificationCount()).isZero();
    }

    @Test
    void documentWithoutUnit_orUnitWithoutUsers_requestWithoutNotifications() {
        document(951006, null);
        document(951007, EMPTY_UNIT);
        String bezJedinice = pending("HR000000000000951006", "951106", 951006);
        String praznaJedinica = pending("HR000000000000951007", "951107", 951007);

        service.obradi(Pokusaj.inline(bezJedinice));
        service.obradi(Pokusaj.inline(praznaJedinica));

        for (String rn : List.of(bezJedinice, praznaJedinica)) {
            VerifikacijaOutboxEntity zapis = outboxRepository.findByRn(rn).orElseThrow();
            assertThat(zapis.getStatus()).isEqualTo(Status.ZAHTJEV_UPISAN);
            assertThat(zapis.getVerificationRequestId()).isNotNull();
            assertThat(zapis.getObavijestiPoslano()).isZero();
        }
        assertThat(requestCount(951006)).isEqualTo(1);
        assertThat(requestCount(951007)).isEqualTo(1);
        assertThat(notificationCount()).isZero();
    }

    @Test
    void notificationsDisabled_requestOnly() {
        document(951008, UNIT);
        String rn = pending("HR000000000000951008", "951108", 951008);
        EturizamVerifikacijaWriter bezObavijesti = new EturizamVerifikacijaWriter(outboxRepository,
                eturizamRepository, accommodationRepository,
                new EturizamVerifikacijaProperties(true, null, null,
                        new EturizamVerifikacijaProperties.Obavijesti(false, null, null, null), properties.retry()),
                clock, txManager);

        Optional<Status> ishod = bezObavijesti.upisi(Pokusaj.inline(rn));

        assertThat(ishod).contains(Status.ZAHTJEV_UPISAN);
        assertThat(requestCount(951008)).isEqualTo(1);
        assertThat(notificationCount()).isZero();
        assertThat(outboxRepository.findByRn(rn).orElseThrow().getObavijestiPoslano()).isZero();
    }

    @Test
    void featureDisabled_rowStaysPending_nothingWritten() {
        document(951009, UNIT);
        String rn = pending("HR000000000000951009", "951109", 951009);
        EturizamVerifikacijaService ugasen = new EturizamVerifikacijaService(writer, outbox,
                new EturizamVerifikacijaProperties(false, null, null, properties.obavijesti(), properties.retry()));

        ugasen.obradi(Pokusaj.inline(rn));

        VerifikacijaOutboxEntity zapis = outboxRepository.findByRn(rn).orElseThrow();
        assertThat(zapis.getStatus()).isEqualTo(Status.PENDING);
        assertThat(zapis.getAttempts()).isZero();
        assertThat(requestCount(951009)).isZero();
        assertThat(notificationCount()).isZero();
    }

    /** Pad obavijesti drugom primatelju poništava i zahtjev i prvu obavijest — upis je atomaran. */
    @Test
    void notificationInsertFails_requestRolledBack_failureRecordedWithBackoff() {
        document(951010, POISONED_UNIT);
        String rn = pending("HR000000000000951010", "951110", 951010);
        Instant prije = Instant.now();

        service.obradi(Pokusaj.inline(rn));

        assertThat(requestCount(951010)).isZero();
        assertThat(notificationCount()).isZero();
        VerifikacijaOutboxEntity zapis = outboxRepository.findByRn(rn).orElseThrow();
        assertThat(zapis.getStatus()).isEqualTo(Status.PENDING);
        assertThat(zapis.getAttempts()).isEqualTo(1);
        assertThat(zapis.getLastError()).containsIgnoringCase("ck_test_otrovan_primatelj");
        assertThat(zapis.getVerificationRequestId()).isNull();
        // backoff-base 5 min nakon prvog neuspjeha (+1 ms: H2 zaokružuje na mikrosekunde)
        assertThat(zapis.getNextAttemptAt()).isBetween(prije.plus(Duration.ofMinutes(5)),
                Instant.now().plus(Duration.ofMinutes(5)).plusMillis(1));
    }

    // ---------------------------------------------------------------- retry job

    @Test
    void retryJob_backsOffThenSucceedsWhenCauseIsGone() {
        document(951011, POISONED_UNIT);
        String rn = pending("HR000000000000951011", "951111", 951011);
        service.obradi(Pokusaj.inline(rn));

        makeDue(rn);
        Instant prije = Instant.now();
        retryJob.retryDue();

        VerifikacijaOutboxEntity drugi = outboxRepository.findByRn(rn).orElseThrow();
        assertThat(drugi.getAttempts()).isEqualTo(2);
        assertThat(drugi.getNextAttemptAt()).isBetween(prije.plus(Duration.ofMinutes(10)),
                Instant.now().plus(Duration.ofMinutes(10)).plusMillis(1));

        // Uzrok nestane (službenik više nije aktivan) — sljedeći prolazak upiše sve.
        jdbc.update("UPDATE str.application_user SET active = FALSE WHERE id = ?", POISONED_USER);
        makeDue(rn);
        retryJob.retryDue();

        VerifikacijaOutboxEntity zapis = outboxRepository.findByRn(rn).orElseThrow();
        assertThat(zapis.getStatus()).isEqualTo(Status.ZAHTJEV_UPISAN);
        assertThat(zapis.getObavijestiPoslano()).isEqualTo(1);
        assertThat(requestCount(951011)).isEqualTo(1);
    }

    @Test
    void retryJob_abandonsAfterMaxAttempts() {
        document(951012, POISONED_UNIT);
        String rn = pending("HR000000000000951012", "951112", 951012);
        jdbc.update("UPDATE str_rn.eturizam_verifikacija SET attempts = ? WHERE rn = ?",
                properties.retry().maxAttempts() - 1, rn);
        makeDue(rn);

        retryJob.retryDue();

        VerifikacijaOutboxEntity zapis = outboxRepository.findByRn(rn).orElseThrow();
        assertThat(zapis.getStatus()).isEqualTo(Status.ABANDONED);
        assertThat(zapis.getAttempts()).isEqualTo(properties.retry().maxAttempts());
        assertThat(zapis.getNextAttemptAt()).isNull();
        assertThat(zapis.getLastError()).startsWith("exhausted: ");
        assertThat(requestCount(951012)).isZero();

        // Napušten redak job više ne uzima.
        retryJob.retryDue();
        assertThat(outboxRepository.findByRn(rn).orElseThrow().getAttempts()).isEqualTo(properties.retry().maxAttempts());
    }

    @Test
    void retryJob_skipsRowsNotYetDue() {
        document(951013, UNIT);
        String rn = pending("HR000000000000951013", "951113", 951013);

        retryJob.retryDue();

        assertThat(outboxRepository.findByRn(rn).orElseThrow().getStatus()).isEqualTo(Status.PENDING);
        assertThat(requestCount(951013)).isZero();
    }

    // ---------------------------------------------------------------- tko smije pokušati (L1)

    /** Pokušaj koji je vidio stariji broj pokušaja (druga instanca je redak u međuvremenu odgodila) se preskače. */
    @Test
    void staleAttempt_isSkipped_andItsFailureNotCountedTwice() {
        document(951015, UNIT);
        String rn = pending("HR000000000000951015", "951115", 951015);
        jdbc.update("UPDATE str_rn.eturizam_verifikacija SET attempts = 1 WHERE rn = ?", rn);
        makeDue(rn);

        assertThat(writer.upisi(new Pokusaj(rn, 0, false))).isEmpty();
        assertThat(writer.upisi(Pokusaj.inline(rn))).as("zakašnjeli inline nakon neuspjeha joba").isEmpty();
        assertThat(requestCount(951015)).isZero();

        // Isti neuspjeh koji su vidjela dva pokušaja (oba s attempts=1) broji se jednom.
        outbox.recordFailure(rn, 1, new IllegalStateException("prvi"));
        outbox.recordFailure(rn, 1, new IllegalStateException("drugi"));
        VerifikacijaOutboxEntity zapis = outboxRepository.findByRn(rn).orElseThrow();
        assertThat(zapis.getAttempts()).isEqualTo(2);
        assertThat(zapis.getLastError()).contains("prvi");
    }

    /** Job ne smije prije roka (inline smije, ali samo kao prvi pokušaj). */
    @Test
    void jobAttempt_beforeDue_isSkipped_inlineIsNot() {
        document(951016, UNIT);
        String rn = pending("HR000000000000951016", "951116", 951016);
        VerifikacijaOutboxEntity zapis = outboxRepository.findByRn(rn).orElseThrow();

        assertThat(writer.upisi(Pokusaj.izReda(zapis))).isEmpty();
        assertThat(writer.upisi(Pokusaj.inline(rn))).contains(Status.ZAHTJEV_UPISAN);
    }

    // ---------------------------------------------------------------- vremensko ograničenje (M1)

    /**
     * Ograničenje transakcije pokušaja stiže i do JDBC veze ({@code JdbcTemplate} → {@code setQueryTimeout})
     * i do EntityManagera (JPA upiti, {@code SELECT … FOR UPDATE} outbox retka).
     */
    @Test
    void attemptTransaction_carriesTimeoutToJdbc() {
        TransactionTemplate pokusaj = EturizamVerifikacijaWriter.transakcija(txManager, Duration.ofSeconds(3));

        pokusaj.executeWithoutResult(tx -> {
            assertThat(TransactionSynchronizationManager.getCurrentTransactionName())
                    .isEqualTo(EturizamVerifikacijaWriter.TX_NAME);
            ConnectionHolder veza = (ConnectionHolder) TransactionSynchronizationManager.getResource(dataSource);
            assertThat(veza).isNotNull();
            assertThat(veza.hasTimeout()).isTrue();
            assertThat(veza.getTimeToLiveInSeconds()).isBetween(1, 3);
            EntityManagerHolder em = (EntityManagerHolder) TransactionSynchronizationManager.getResource(entityManagerFactory);
            assertThat(em).isNotNull();
            assertThat(em.hasTimeout()).isTrue();
        });
    }

    /**
     * eTurizam drži nepotvrđen zahtjev za isti dokument: INSERT čeka na UNIQUE. Pokušaj mora završiti
     * istekom čekanja i biti zabilježen kao običan neuspjeh, ne objesiti nit. Na H2 čekanje prekida
     * H2-ov vlastiti lock timeout (~2 s, prije našeg); na PostgreSQL-u to radi ograničenje transakcije
     * ({@code setQueryTimeout}, v. {@link #attemptTransaction_carriesTimeoutToJdbc}).
     */
    @Test
    void lockWaitOnRequestInsert_endsAsRecordedFailure() throws Exception {
        document(951017, UNIT);
        String rn = pending("HR000000000000951017", "951117", 951017);
        java.util.concurrent.CountDownLatch upisano = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch pusti = new java.util.concurrent.CountDownLatch(1);
        Thread eturizam = new Thread(() -> requiresNew().executeWithoutResult(tx -> {
            jdbc.update("INSERT INTO str.verification_request (created_by, created_date, last_modified_by, "
                    + "last_modified_date, active, source_document_id) VALUES ('x', CURRENT_TIMESTAMP, 'x', "
                    + "CURRENT_TIMESTAMP, TRUE, 951017)");
            upisano.countDown();
            try {
                pusti.await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            tx.setRollbackOnly();
        }));
        eturizam.start();
        try {
            assertThat(upisano.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            long start = System.nanoTime();

            service.obradi(Pokusaj.inline(rn));

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(20));
        } finally {
            pusti.countDown();
            eturizam.join(30_000);
        }
        VerifikacijaOutboxEntity zapis = outboxRepository.findByRn(rn).orElseThrow();
        assertThat(zapis.getStatus()).isEqualTo(Status.PENDING);
        assertThat(zapis.getAttempts()).isEqualTo(1);
        assertThat(zapis.getLastError()).isNotBlank();
        assertThat(requestCount(951017)).isZero();
    }

    // ---------------------------------------------------------------- pomoćno

    private TransactionTemplate requiresNew() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }

    /** Outbox redak kakav ostavlja izdavanje RB-a za neverificiran objekt. */
    private String pending(String rn, String facilityId, long documentId) {
        FacilityOwnershipRow facility = mock(FacilityOwnershipRow.class);
        when(facility.getVerified()).thenReturn(false);
        when(facility.getDocumentId()).thenReturn(documentId);
        requiresNew().executeWithoutResult(tx -> outbox.zabiljezi(rn, UUID.randomUUID(), facilityId, facility));
        return rn;
    }

    private void makeDue(String rn) {
        jdbc.update("UPDATE str_rn.eturizam_verifikacija SET next_attempt_at = ? WHERE rn = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)), rn);
    }

    private void user(long id, long unit, boolean active, boolean internal) {
        jdbc.update("INSERT INTO str.application_user (id, active, username, internal, organizational_unit_id) "
                + "VALUES (?, ?, ?, ?, ?)", id, active, "u" + id, internal, unit);
    }

    private void document(long id, Long unit) {
        jdbc.update("INSERT INTO str.document (id, active, jurisdiction_organizational_unit_id) VALUES (?, TRUE, ?)",
                id, unit);
    }

    private int requestCount(long documentId) {
        return jdbc.queryForObject("SELECT count(*) FROM str.verification_request WHERE source_document_id = ?",
                Integer.class, documentId);
    }

    private int notificationCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM str.korisnicka_obavijest WHERE application_user_id BETWEEN 951000 AND 951999",
                Integer.class);
    }

    private FacilityOwnershipRow facilityRow(Long documentId) {
        FacilityOwnershipRow facility = mock(FacilityOwnershipRow.class);
        when(facility.getMunicipalityName()).thenReturn("Makarska");
        when(facility.getName()).thenReturn("Apartman Marija");
        when(facility.getBeds()).thenReturn(4);
        when(facility.getAuxiliaryBeds()).thenReturn(2);
        when(facility.getDocumentId()).thenReturn(documentId);
        return facility;
    }

    private void unverifiedFacility(String facilityId, Long documentId) {
        FacilityOwnershipRow facility = facilityRow(documentId);
        when(facility.getVerified()).thenReturn(false);
        when(facilityClaimVerifier.verify(eq(OIB), eq(facilityId), any())).thenReturn(Optional.of(facility));
    }

    private String submit(String facilityId) throws Exception {
        byte[] response = mvc.perform(post("/api/generateRegistrationNumber")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(new RegistrationRequest(
                                OIB, null, null, null, null, null, null, null, null, null, null,
                                OfferType.PRIMARY_RESIDENCE, Offering.WHOLE, false, "1", false, true,
                                null, null, null, null, null, null, facilityId,
                                "iznajmljivac@example.com", "0991234567", null, null, null))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray();
        return om.readTree(response).get("registrationNumber").asText();
    }
}
