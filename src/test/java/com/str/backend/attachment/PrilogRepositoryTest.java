package com.str.backend.attachment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.unit.DataSize;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Upiti {@link PrilogRepository} i CHECK ograničenja iz changeseta 148 nad pravom pohranom. Test profil
 * gradi shemu iz entiteta, pa se ograničenja primjenjuju <b>iz samog changeseta</b>
 * (kao {@code LessorLegalEntityCheckConstraintTest}).
 *
 * <p>Bez obuhvatne transakcije testa ({@code NOT_SUPPORTED}): čitanja i upisi koje eGOP tok zove iz
 * {@code AFTER_COMMIT} listenera su {@code REQUIRES_NEW} i ne bi vidjeli nepotvrđene retke testa.
 * Radnja ({@code bind}, {@code MANDATORY}) ide u vlastitoj transakciji, kao u produkciji.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PrilogRepositoryTest {

    private static final String CHANGESET = "db/changelog/changes/148-pismeno-prilog.xml";
    private static final String ANA = Ucitao.nias("12345678903");
    private static final String IVO = Ucitao.lessor(UUID.randomUUID());
    private static final byte[] PDF = "%PDF-1.7".getBytes();

    @Autowired
    private PrilogRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private PrilogService service;

    @BeforeEach
    void applyConstraintsFromChangeset() throws Exception {
        repository.deleteAll();
        jdbc.execute("ALTER TABLE str_rn.pismeno_prilog DROP CONSTRAINT IF EXISTS ck_pismeno_prilog_vrsta_vlasnika");
        jdbc.execute("ALTER TABLE str_rn.pismeno_prilog DROP CONSTRAINT IF EXISTS ck_pismeno_prilog_vlasnik");
        for (String sql : addConstraintSql()) {
            jdbc.execute(sql);
        }
        service = new PrilogService(repository, new PrilogStagingLock(jdbc), 5, 6, DataSize.ofMegabytes(10), 20,
                DataSize.ofMegabytes(100), DataSize.ofMegabytes(15));
    }

    @Test
    void stageThenBind_bindsOnlyOwnUnbound_andListsByOwner() {
        Long a = stage(ANA);
        Long b = stage(ANA);
        Long tudi = stage(IVO);
        UUID submissionId = UUID.randomUUID();

        bind(List.of(a, b), PrilogVlasnik.zahtjev(submissionId));

        assertThat(repository.findSazeciVlasnika(VrstaVlasnika.SUBMISSION, submissionId))
                .extracting(PrilogRepository.PrilogSazetak::getId).containsExactly(a, b);
        assertThat(repository.countByUcitaoAndVlasnikIdIsNull(ANA)).isZero();
        assertThat(repository.countByUcitaoAndVlasnikIdIsNull(IVO)).isEqualTo(1);
        // Uvjetni UPDATE ne dira tuđe ni već vezane.
        Integer vezano = new TransactionTemplate(transactionManager).execute(s -> repository.bind(List.of(a, tudi),
                ANA, VrstaVlasnika.RN_LOG, UUID.randomUUID(), Instant.now()));
        assertThat(vezano).isZero();
        assertThat(repository.findSadrzaj(a)).isEqualTo(PDF);
    }

    @Test
    void cleanup_deletesOnlyOldUnbound() {
        Long star = stage(ANA);
        Long vezan = stage(ANA);
        bind(List.of(vezan), PrilogVlasnik.prijelaz(UUID.randomUUID()));
        jdbc.update("UPDATE str_rn.pismeno_prilog SET ucitano_u = ? WHERE id IN (?, ?)",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofHours(25))), star, vezan);
        Long svjez = stage(ANA);

        new PrilogCleanupJob(repository, Duration.ofHours(24)).purgeStaged();

        assertThat(repository.findAll()).extracting(PismenoPrilogEntity::getId).containsExactlyInAnyOrder(vezan, svjez);
    }

    @Test
    void deleteStaged_onlyOwnUnbound() {
        Long a = stage(ANA);

        assertThat(repository.deleteStaged(a, IVO)).isZero();
        assertThat(repository.deleteStaged(a, ANA)).isEqualTo(1);
        assertThat(repository.findSazeci(List.of(a))).isEmpty();
    }

    @Test
    void markEgopPrilozeno_isVisibleInSummary() {
        Long a = stage(ANA);
        UUID logId = UUID.randomUUID();
        bind(List.of(a), PrilogVlasnik.prijelaz(logId));

        repository.markEgopPrilozeno(a, 1003, 1, Instant.now());

        PrilogRepository.PrilogSazetak s = repository.findSazeciVlasnika(VrstaVlasnika.RN_LOG, List.of(logId)).get(0);
        assertThat(s.getEgopJop()).isEqualTo(1003);
        assertThat(s.getEgopRbr()).isEqualTo(1);
    }

    /** CHECK iz changeseta: vrsta i vlasnik idu zajedno — poluvezan red je greška u kodu. */
    @Test
    void halfBoundRow_isRejectedByCheck() {
        Long a = stage(ANA);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE str_rn.pismeno_prilog SET vrsta_vlasnika = 'SUBMISSION' WHERE id = ?", a))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE str_rn.pismeno_prilog SET vrsta_vlasnika = 'NESTO', vlasnik_id = ?, vezano_u = ? WHERE id = ?",
                UUID.randomUUID(), java.sql.Timestamp.from(Instant.now()), a))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * C1: oznaka prilaganja i neuspjeh upisuju se i bez transakcije pozivatelja (AFTER_COMMIT), a
     * neuspjeh ne dira već priloženi prilog.
     */
    @Test
    void egopMarks_workWithoutCallerTransaction() {
        Long a = stage(ANA);
        UUID logId = UUID.randomUUID();
        bind(List.of(a), PrilogVlasnik.prijelaz(logId));
        Instant sljedeci = Instant.now().plusSeconds(120);

        assertThat(repository.markEgopNeuspjeh(a, "timeout", sljedeci)).isEqualTo(1);
        PrilogRepository.PrilogSazetak pao = repository.findSazeciVlasnika(VrstaVlasnika.RN_LOG, logId).get(0);
        assertThat(pao.getEgopPokusaji()).isEqualTo(1);
        assertThat(pao.getEgopSljedeciPokusajU()).isNotNull();

        assertThat(repository.markEgopPrilozeno(a, 1003, 1, Instant.now())).isEqualTo(1);
        assertThat(repository.markEgopNeuspjeh(a, "kasni pad", sljedeci)).isZero();
        PismenoPrilogEntity prilozen = repository.findById(a).orElseThrow();
        assertThat(prilozen.getEgopJop()).isEqualTo(1003);
        assertThat(prilozen.getEgopGreska()).isNull();
        assertThat(prilozen.getEgopSljedeciPokusajU()).isNull();
    }

    /** M-BE5: kvota ukupne veličine nevezanih po učitavatelju (409 ATTACHMENT_STAGED_LIMIT). */
    @Test
    void stage_overTotalUnboundBytes_isRefused() {
        service = new PrilogService(repository, new PrilogStagingLock(jdbc), 5, 6, DataSize.ofMegabytes(10), 20,
                DataSize.ofBytes(PDF.length * 2L), DataSize.ofMegabytes(15));
        stage(ANA);
        stage(ANA);

        assertThatThrownBy(() -> stage(ANA))
                .isInstanceOfSatisfying(PrilogException.class,
                        e -> assertThat(e.getCode()).isEqualTo(PrilogException.STAGED_LIMIT));
        assertThat(repository.countByUcitaoAndVlasnikIdIsNull(ANA)).isEqualTo(2);
        // Kvota je po učitavatelju.
        stage(IVO);
    }

    private Long stage(String ucitao) {
        return service.stage(ucitao, new MockMultipartFile("datoteka", "a.pdf", null, PDF)).id();
    }

    private void bind(List<Long> ids, PrilogVlasnik vlasnik) {
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> service.bind(ANA, ids, vlasnik));
    }

    /** {@code ADD CONSTRAINT} naredbe iz {@code <sql>} bloka changeseta (parcijalni indeks H2 ne zna). */
    private static List<String> addConstraintSql() throws Exception {
        List<String> out = new ArrayList<>();
        try (InputStream in = new ClassPathResource(CHANGESET).getInputStream()) {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            NodeList sqls = factory.newDocumentBuilder().parse(in).getElementsByTagNameNS("*", "sql");
            for (int i = 0; i < sqls.getLength(); i++) {
                for (String statement : sqls.item(i).getTextContent().split(";")) {
                    if (statement.contains("ADD CONSTRAINT")) {
                        out.add(statement.strip());
                    }
                }
            }
        }
        assertThat(out).hasSize(2);
        return out;
    }
}
