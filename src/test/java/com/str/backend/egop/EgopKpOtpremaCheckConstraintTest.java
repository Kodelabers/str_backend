package com.str.backend.egop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code chk_egop_kp_otprema_status} nad stvarnom pohranom otpreme. Test profil gradi shemu iz
 * entiteta, pa se ograničenje primjenjuje iz samog changeseta — novi {@link EgopKpOtpremaEntity.Status}
 * bez proširenja CHECK-a pao bi ovdje, a ne tek na PostgreSQL-u kao 500.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EgopKpOtpremaCheckConstraintTest {

    private static final String CHANGESET = "db/changelog/changes/139-egop-kp-otprema.xml";
    private static final String CONSTRAINT = "chk_egop_kp_otprema_status";

    @Autowired
    private EgopKpOtpremaRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void applyConstraintFromChangeset() throws Exception {
        jdbc.execute("ALTER TABLE str_rn.egop_kp_otprema DROP CONSTRAINT IF EXISTS " + CONSTRAINT);
        jdbc.execute(addConstraintSql());
    }

    @ParameterizedTest
    @EnumSource(EgopKpOtpremaEntity.Status.class)
    void everyStatus_isAccepted(EgopKpOtpremaEntity.Status status) {
        EgopKpOtpremaEntity otprema = EgopKpOtpremaEntity.pending(UUID.randomUUID(), "12345678903", Instant.now());
        switch (status) {
            case SENT -> otprema.markSent(Instant.now());
            case FAILED -> otprema.markFailed("boom", Instant.now());
            case SKIPPED -> otprema.markSkipped("zastario");
            case PENDING -> { }
        }

        repository.saveAndFlush(otprema);

        assertThat(repository.findById(otprema.getId())).get()
                .extracting(EgopKpOtpremaEntity::getStatus).isEqualTo(status);
    }

    @Test
    void unknownStatus_isRejected() {
        EgopKpOtpremaEntity otprema = repository.saveAndFlush(
                EgopKpOtpremaEntity.pending(UUID.randomUUID(), "12345678903", Instant.now()));

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE str_rn.egop_kp_otprema SET status = 'DELIVERED' WHERE id = ?", otprema.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void secondOtpremaForSamePismeno_isRejected() {
        UUID pismenoId = UUID.randomUUID();
        repository.saveAndFlush(EgopKpOtpremaEntity.pending(pismenoId, "12345678903", Instant.now()));

        assertThatThrownBy(() -> repository.saveAndFlush(EgopKpOtpremaEntity.pending(pismenoId, "12345678903", Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static String addConstraintSql() throws Exception {
        try (InputStream in = new ClassPathResource(CHANGESET).getInputStream()) {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            NodeList sqls = factory.newDocumentBuilder().parse(in).getElementsByTagNameNS("*", "sql");
            for (int i = 0; i < sqls.getLength(); i++) {
                String text = sqls.item(i).getTextContent();
                if (text.contains("ADD CONSTRAINT " + CONSTRAINT)) {
                    return text.trim();
                }
            }
        }
        throw new IllegalStateException(CHANGESET + " nema ADD CONSTRAINT " + CONSTRAINT);
    }
}
