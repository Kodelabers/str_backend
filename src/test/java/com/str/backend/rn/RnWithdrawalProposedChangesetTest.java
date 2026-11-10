package com.str.backend.rn;

import com.str.backend.domain.RnStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Changeset 155 (T12) nad stvarnom pohranom, po uzoru na {@code RnStatusRevokedChangesetTest}: CHECK
 * {@code chk_rn_status} pušta {@code WITHDRAWAL_PROPOSED}, a dva nova stupca nose rok i status prije
 * prijedloga. SQL se izvršava <b>iz same datoteke</b>. Test profil gradi shemu iz entiteta, pa stupci
 * već postoje: changeset se zato provjerava na tablici kakva je bila prije njega (stupci se prvo brišu).
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RnWithdrawalProposedChangesetTest {

    private static final String CHANGESET = "db/changelog/changes/155-rn-withdrawal-proposed.xml";

    @Autowired
    private RnRepository rnRepository;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * DDL u H2 commita, a baza je zajednička cijelom kontekstu: shema mora ostati kakvu je Hibernate
     * izgradio (stupac roka, bez {@code chk_rn_status}), da ostali testovi u istom kontekstu rade.
     */
    @AfterEach
    void restoreHibernateSchema() {
        // DDL u H2 commita i retke testa — brišu se, da ne ostanu drugim testovima u kontekstu.
        jdbc.update("DELETE FROM str_rn.registration_number WHERE rn LIKE 'HR155%'");
        jdbc.execute("ALTER TABLE str_rn.registration_number DROP CONSTRAINT IF EXISTS chk_rn_status");
        jdbc.execute("ALTER TABLE str_rn.registration_number ADD COLUMN IF NOT EXISTS withdrawal_deadline DATE");
        jdbc.execute("ALTER TABLE str_rn.registration_number ADD COLUMN IF NOT EXISTS withdrawal_from_suspended"
                + " BOOLEAN NOT NULL DEFAULT false");
    }

    @Test
    void changeset_allowsWithdrawalProposed_andAddsColumns() throws Exception {
        String active = rn("HR155000000000000001");
        dropNewColumns();

        runSql(sql(false));

        LocalDate deadline = LocalDate.of(2026, 12, 1);
        jdbc.update("UPDATE str_rn.registration_number SET status = 'WITHDRAWAL_PROPOSED', withdrawal_deadline = ?"
                + " WHERE rn = ?", deadline, active);

        assertThat(jdbc.queryForObject("SELECT status FROM str_rn.registration_number WHERE rn = ?",
                String.class, active)).isEqualTo("WITHDRAWAL_PROPOSED");
        assertThat(jdbc.queryForObject("SELECT withdrawal_deadline FROM str_rn.registration_number WHERE rn = ?",
                LocalDate.class, active)).isEqualTo(deadline);
        // Postojeći retci dobivaju false (DEFAULT), a stupac ne prima NULL.
        assertThat(jdbc.queryForObject("SELECT withdrawal_from_suspended FROM str_rn.registration_number WHERE rn = ?",
                Boolean.class, active)).isFalse();
        assertThatThrownBy(() -> jdbc.update("UPDATE str_rn.registration_number SET withdrawal_from_suspended = NULL"
                + " WHERE rn = ?", active)).isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Svaki postojeći status i dalje prolazi CHECK; nepoznat ne prolazi. */
    @Test
    void constraint_keepsEveryStatus_andRejectsUnknown() throws Exception {
        String rn = rn("HR155000000000000002");
        dropNewColumns();
        runSql(sql(false));

        for (RnStatus status : RnStatus.values()) {
            jdbc.update("UPDATE str_rn.registration_number SET status = ? WHERE rn = ?", status.name(), rn);
        }
        assertThatThrownBy(() -> jdbc.update("UPDATE str_rn.registration_number SET status = 'IN_PROCESSING'"
                + " WHERE rn = ?", rn)).isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Rollback vraća CHECK bez WITHDRAWAL_PROPOSED i briše stupac. */
    @Test
    void rollback_restoresPreviousConstraint_andDropsColumns() throws Exception {
        String rn = rn("HR155000000000000003");
        dropNewColumns();
        runSql(sql(false));

        // Preduvjet rollbacka (komentar changeseta): nijedan broj nije u WITHDRAWAL_PROPOSED. DDL u H2
        // commita, pa ovdje mogu biti i retci ranijih testova ove klase.
        jdbc.update("UPDATE str_rn.registration_number SET status = 'ACTIVE' WHERE status = 'WITHDRAWAL_PROPOSED'");

        runSql(sql(true));

        assertThat(columns()).doesNotContain("WITHDRAWAL_DEADLINE", "WITHDRAWAL_FROM_SUSPENDED");
        assertThatThrownBy(() -> jdbc.update("UPDATE str_rn.registration_number SET status = 'WITHDRAWAL_PROPOSED'"
                + " WHERE rn = ?", rn)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE str_rn.registration_number SET status = 'REVOKED' WHERE rn = ?", rn);
    }

    private String rn(String rn) {
        rnRepository.saveAndFlush(RnEntity.issue(rn, UUID.randomUUID(), UUID.randomUUID(), LocalDate.now()));
        return rn;
    }

    private void dropNewColumns() {
        jdbc.execute("ALTER TABLE str_rn.registration_number DROP COLUMN IF EXISTS withdrawal_deadline");
        jdbc.execute("ALTER TABLE str_rn.registration_number DROP COLUMN IF EXISTS withdrawal_from_suspended");
        assertThat(columns()).doesNotContain("WITHDRAWAL_DEADLINE", "WITHDRAWAL_FROM_SUSPENDED");
    }

    private List<String> columns() {
        return jdbc.queryForList("SELECT UPPER(column_name) FROM information_schema.columns"
                + " WHERE UPPER(table_schema) = 'STR_RN' AND UPPER(table_name) = 'REGISTRATION_NUMBER'", String.class);
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
