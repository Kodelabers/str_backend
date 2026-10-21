package com.str.backend.kp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CHECK-ovi iz changeseta 140 nad stvarnom pohranom otpreme. Test profil gradi shemu iz entiteta,
 * pa se ograničenja primjenjuju iz samog changeseta — novi {@link KpOtpremaEntity.Status} ili
 * primatelj bez CHECK-a pao bi ovdje, a ne tek na PostgreSQL-u kao 500.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class KpOtpremaCheckConstraintTest {

    private static final String CHANGESET = "db/changelog/changes/140-kp-izravno.xml";
    private static final List<String> CONSTRAINTS = List.of(
            "chk_kp_otprema_status", "chk_kp_otprema_primatelj_tip", "chk_kp_otprema_jips");

    private static final KpPrimatelj FO = new KpPrimatelj(KpPrimatelj.Tip.FO, "12345678903", null);
    private static final KpPrimatelj PS = new KpPrimatelj(KpPrimatelj.Tip.PS, "98765432106", "001");

    @Autowired
    private KpOtpremaRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void applyConstraintsFromChangeset() throws Exception {
        for (String constraint : CONSTRAINTS) {
            jdbc.execute("ALTER TABLE str_rn.kp_otprema DROP CONSTRAINT IF EXISTS " + constraint);
            jdbc.execute(addConstraintSql(constraint));
        }
    }

    @ParameterizedTest
    @EnumSource(KpOtpremaEntity.Status.class)
    void everyStatus_isAccepted(KpOtpremaEntity.Status status) {
        KpOtpremaEntity otprema = KpOtpremaEntity.pending(UUID.randomUUID(), FO, Instant.now());
        switch (status) {
            case SENT -> otprema.markSent(Instant.now(), 101, true, null);
            case FAILED -> otprema.markFailed("M008", "nedostupno", Instant.now(), null);
            case SKIPPED -> otprema.markSkipped("zastario");
            case NEMA_PRETINCA -> otprema.markNemaPretinca("K006", "nema KP", null);
            case PENDING -> { }
        }

        repository.saveAndFlush(otprema);

        assertThat(repository.findById(otprema.getId())).get()
                .extracting(KpOtpremaEntity::getStatus).isEqualTo(status);
    }

    @Test
    void businessRecipient_isAccepted() {
        KpOtpremaEntity otprema = repository.saveAndFlush(KpOtpremaEntity.pending(UUID.randomUUID(), PS, Instant.now()));

        assertThat(repository.findById(otprema.getId())).get()
                .extracting(KpOtpremaEntity::primatelj).isEqualTo(PS);
    }

    @Test
    void unknownStatus_isRejected() {
        KpOtpremaEntity otprema = repository.saveAndFlush(KpOtpremaEntity.pending(UUID.randomUUID(), FO, Instant.now()));

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE str_rn.kp_otprema SET status = 'DELIVERED' WHERE id = ?", otprema.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void businessRecipientWithoutRegistry_isRejected() {
        KpOtpremaEntity otprema = repository.saveAndFlush(KpOtpremaEntity.pending(UUID.randomUUID(), PS, Instant.now()));

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE str_rn.kp_otprema SET izvor_registar = NULL WHERE id = ?", otprema.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void secondOtpremaForSamePismeno_isRejected() {
        UUID pismenoId = UUID.randomUUID();
        repository.saveAndFlush(KpOtpremaEntity.pending(pismenoId, FO, Instant.now()));

        assertThatThrownBy(() -> repository.saveAndFlush(KpOtpremaEntity.pending(pismenoId, FO, Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static String addConstraintSql(String constraint) throws Exception {
        try (InputStream in = new ClassPathResource(CHANGESET).getInputStream()) {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            NodeList sqls = factory.newDocumentBuilder().parse(in).getElementsByTagNameNS("*", "sql");
            for (int i = 0; i < sqls.getLength(); i++) {
                String text = sqls.item(i).getTextContent();
                if (text.contains("ADD CONSTRAINT " + constraint + " ")) {
                    return text.trim();
                }
            }
        }
        throw new IllegalStateException(CHANGESET + " nema ADD CONSTRAINT " + constraint);
    }
}
