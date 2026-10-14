package com.str.backend.rn;

import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Changeset 137 nad stvarnom pohranom: proširuje {@code chk_rn_status} za {@code REVOKED} i
 * postojeće opozive iznajmljivača (ulaz u {@code WITHDRAWN} s akterom {@code LESSOR:} ili
 * {@code NIAS:}) prevodi u {@code REVOKED}, zajedno s njihovim zapisom u revizijskom tragu. SQL se
 * izvršava <b>iz same datoteke</b>, kao u {@code NiasLessorCountryOfResidenceTest}.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RnStatusRevokedChangesetTest {

    private static final String CHANGESET = "db/changelog/changes/137-rn-status-revoked.xml";

    @Autowired
    private RnRepository rnRepository;

    @Autowired
    private RegistrationNumberLogRepository logRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void lessorWithdrawals_becomeRevoked_officialOnesStayWithdrawn() throws Exception {
        String lessor = withdrawn("HR100000000000000001", "LESSOR:" + UUID.randomUUID());
        String nias = withdrawn("HR100000000000000002", "NIAS:12312312316");
        String official = withdrawn("HR100000000000000003", null);
        String active = rn("HR100000000000000004", RnStatus.ACTIVE);
        // Akter iznajmljivača na prijelazu koji nije povlačenje ne smije ništa promijeniti.
        log(active, "ACTIVE", "SUSPENSION_PROPOSED", "INSPECTION", "LESSOR:" + UUID.randomUUID());

        runChangeset();

        assertThat(status(lessor)).isEqualTo("REVOKED");
        assertThat(status(nias)).isEqualTo("REVOKED");
        assertThat(status(official)).isEqualTo("WITHDRAWN");
        assertThat(status(active)).isEqualTo("ACTIVE");

        assertThat(lastLog(lessor)).isEqualTo("REVOKED/REVOCATION");
        assertThat(lastLog(nias)).isEqualTo("REVOKED/REVOCATION");
        assertThat(lastLog(official)).isEqualTo("WITHDRAWN/WITHDRAWAL");
        assertThat(lastLog(active)).isEqualTo("SUSPENSION_PROPOSED/INSPECTION");
        // Dan opoziva ostaje — od njega teku retencija i rok ponovnog zahtjeva.
        assertThat(jdbc.queryForObject("SELECT valid_to FROM str_rn.registration_number WHERE rn = ?",
                LocalDate.class, lessor)).isNotNull();
    }

    @Test
    void changeset_isIdempotent() throws Exception {
        String lessor = withdrawn("HR100000000000000005", "NIAS:12312312316");

        runChangeset();
        runChangeset();

        assertThat(status(lessor)).isEqualTo("REVOKED");
        assertThat(lastLog(lessor)).isEqualTo("REVOKED/REVOCATION");
    }

    /**
     * Odlučuje zadnji ulaz u WITHDRAWN. Dva takva zapisa stanje stroja ne dopušta, ali ručni zahvat
     * u bazi može ih ostaviti — tada vrijedi onaj kojim je RB stvarno završio.
     */
    @Test
    void onlyTheLastTransitionIntoWithdrawn_decides() throws Exception {
        String officialLast = rn("HR100000000000000007", RnStatus.WITHDRAWN);
        logAt(officialLast, "WITHDRAWN", "NIAS:12312312316", Instant.parse("2026-01-01T10:00:00Z"));
        logAt(officialLast, "WITHDRAWN", null, Instant.parse("2026-01-02T10:00:00Z"));
        String lessorLast = rn("HR100000000000000008", RnStatus.WITHDRAWN);
        logAt(lessorLast, "WITHDRAWN", null, Instant.parse("2026-01-01T10:00:00Z"));
        logAt(lessorLast, "WITHDRAWN", "LESSOR:" + UUID.randomUUID(), Instant.parse("2026-01-02T10:00:00Z"));

        runChangeset();

        assertThat(status(officialLast)).isEqualTo("WITHDRAWN");
        assertThat(logs(officialLast)).containsExactly("WITHDRAWN/WITHDRAWAL", "WITHDRAWN/WITHDRAWAL");
        assertThat(status(lessorLast)).isEqualTo("REVOKED");
        // Raniji (službeni) zapis ostaje kakav jest; prevodi se samo onaj koji je odlučio.
        assertThat(logs(lessorLast)).containsExactly("WITHDRAWN/WITHDRAWAL", "REVOKED/REVOCATION");
    }

    /** Bez traga ili s akterom koji nije iznajmljivač nema dokaza o opozivu — ostaje povlačenje. */
    @Test
    void withdrawnWithoutLessorEvidence_staysWithdrawn() throws Exception {
        String noLog = rn("HR100000000000000009", RnStatus.WITHDRAWN);
        String system = withdrawn("HR100000000000000010", "SYSTEM");
        // Prefiks je kod uvijek pisao velikim slovima; drugačiji zapis nije njegov.
        String lowerCase = withdrawn("HR100000000000000011", "nias:12312312316");

        runChangeset();

        assertThat(status(noLog)).isEqualTo("WITHDRAWN");
        assertThat(status(system)).isEqualTo("WITHDRAWN");
        assertThat(status(lowerCase)).isEqualTo("WITHDRAWN");
    }

    /**
     * Rollback vraća sve opozive u stari model (WITHDRAWN/WITHDRAWAL), i one nastale nakon migracije,
     * a akter ostaje — stara verzija po njemu opet bira akt OPOZIV.
     */
    @Test
    void rollback_restoresOldModelAndKeepsActor() throws Exception {
        String actor = "NIAS:12312312316";
        String migrated = withdrawn("HR100000000000000012", actor);
        runChangeset();
        String revokedLater = rn("HR100000000000000013", RnStatus.REVOKED);
        log(revokedLater, "ACTIVE", "REVOKED", RnTrigger.REVOCATION.name(), actor);

        try {
            runSql(rollbackSql());

            assertThat(status(migrated)).isEqualTo("WITHDRAWN");
            assertThat(status(revokedLater)).isEqualTo("WITHDRAWN");
            assertThat(lastLog(migrated)).isEqualTo("WITHDRAWN/WITHDRAWAL");
            assertThat(lastLog(revokedLater)).isEqualTo("WITHDRAWN/WITHDRAWAL");
            assertThat(jdbc.queryForObject("SELECT actor FROM str_rn.registration_number_log WHERE rn = ?",
                    String.class, revokedLater)).isEqualTo(actor);
        } finally {
            // DDL u H2 commita, a baza je zajednička cijelom kontekstu: CHECK bez REVOKED ne smije
            // ostati za testove koji upisuju opoziv.
            runChangeset();
        }
    }

    /** Novi opoziv kroz servis upisuje REVOKED — CHECK iz changeseta ga mora pustiti. */
    @Test
    void constraint_allowsRevoked() throws Exception {
        runChangeset();
        String rn = rn("HR100000000000000006", RnStatus.ACTIVE);

        jdbc.update("UPDATE str_rn.registration_number SET status = 'REVOKED' WHERE rn = ?", rn);

        assertThat(status(rn)).isEqualTo("REVOKED");
    }

    private String withdrawn(String rn, String actor) {
        rn(rn, RnStatus.WITHDRAWN);
        log(rn, "ACTIVE", "WITHDRAWN", RnTrigger.WITHDRAWAL.name(), actor);
        return rn;
    }

    private String rn(String rn, RnStatus status) {
        RnEntity entity = RnEntity.issue(rn, UUID.randomUUID(), UUID.randomUUID(), LocalDate.now().minusYears(1));
        if (status != RnStatus.ACTIVE) {
            entity.applyStatus(status);
        }
        rnRepository.saveAndFlush(entity);
        return rn;
    }

    private void log(String rn, String from, String to, String trigger, String actor) {
        logRepository.saveAndFlush(RegistrationNumberLogEntity.transition(rn, from, to, trigger, actor, null));
    }

    private void logAt(String rn, String to, String actor, Instant at) {
        jdbc.update("INSERT INTO str_rn.registration_number_log (log_id, rn, from_status, to_status, "
                        + "trigger_name, actor, occurred_at) VALUES (?, ?, 'ACTIVE', ?, ?, ?, ?)",
                UUID.randomUUID(), rn, to, RnTrigger.WITHDRAWAL.name(), actor, Timestamp.from(at));
    }

    private List<String> logs(String rn) {
        return jdbc.queryForList("SELECT to_status || '/' || trigger_name FROM str_rn.registration_number_log "
                + "WHERE rn = ? ORDER BY occurred_at", String.class, rn);
    }

    private String status(String rn) {
        return jdbc.queryForObject("SELECT status FROM str_rn.registration_number WHERE rn = ?", String.class, rn);
    }

    private String lastLog(String rn) {
        return jdbc.queryForObject("SELECT to_status || '/' || trigger_name FROM str_rn.registration_number_log "
                + "WHERE rn = ? ORDER BY occurred_at DESC LIMIT 1", String.class, rn);
    }

    private void runChangeset() throws Exception {
        runSql(sql(false));
    }

    private static String rollbackSql() throws Exception {
        return sql(true);
    }

    /** Naredbe jedna po jedna — JDBC ih ne prima sve odjednom na svakoj bazi. */
    private void runSql(String sql) {
        Arrays.stream(sql.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(jdbc::execute);
    }

    /** Prvi {@code <sql>} izvan odnosno unutar {@code <rollback>}. */
    private static String sql(boolean rollback) throws Exception {
        try (InputStream in = new ClassPathResource(CHANGESET).getInputStream()) {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            NodeList nodes = factory.newDocumentBuilder().parse(in).getElementsByTagNameNS("*", "sql");
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                if (rollback == "rollback".equals(node.getParentNode().getLocalName())) {
                    return node.getTextContent().trim();
                }
            }
        }
        throw new IllegalStateException(CHANGESET + " nema <sql>" + (rollback ? " u <rollback>" : ""));
    }
}
