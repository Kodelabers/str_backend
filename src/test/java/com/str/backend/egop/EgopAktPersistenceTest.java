package com.str.backend.egop;

import com.str.backend.domain.EgopSyncStatus;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Provjerava shemu i JPQL akata protiv prave baze (H2). Jedinični testovi mockaju repozitorij,
 * pa bi im promašen unique ključ ili tipfeler u {@code @Query} prošli nezapaženo — a upravo je
 * ključ ono što se u changesetu 054 mijenjalo.
 */
@SpringBootTest
@ActiveProfiles("test")
class EgopAktPersistenceTest {

    private static final String RN = "HR180000123456789001";

    @Autowired
    private EgopPismenoRepository pismenoRepository;

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private EgopFilingStore filingStore;

    /** Prvi tok zamrzava svoje bajtove i ključ; drugi dobiva taj isti par, a ne svoj. */
    @Test
    void freezeForSealing_secondCallerGetsFirstCallersKeyAndBytes() {
        EgopPismenoEntity akt = pismenoRepository.saveAndFlush(akt(submission(), UUID.randomUUID().toString()));

        EgopFilingStore.SealRequest prvi = filingStore.freezeForSealing(akt.getId(), "prvi".getBytes(), "kljuc-prvi-000001");
        EgopFilingStore.SealRequest drugi = filingStore.freezeForSealing(akt.getId(), "drugi".getBytes(), "kljuc-drugi-00001");

        assertThat(prvi.key()).isEqualTo("kljuc-prvi-000001");
        assertThat(drugi.key()).isEqualTo("kljuc-prvi-000001");
        assertThat(drugi.pdf()).isEqualTo("prvi".getBytes());
        assertThat(pismenoRepository.findById(akt.getId()).orElseThrow().getPdfContent()).isEqualTo("prvi".getBytes());
    }

    /** Brisanje ključa samo za ključ s kojim je zahtjev odbijen, i nikad nakon pečata. */
    @Test
    void clearSealKey_onlyForSameKeyAndUnsealed() {
        EgopPismenoEntity akt = pismenoRepository.saveAndFlush(akt(submission(), UUID.randomUUID().toString()));
        filingStore.freezeForSealing(akt.getId(), "pdf".getBytes(), "kljuc-0000000001");

        filingStore.clearSealKey(akt.getId(), "neki-drugi-kljuc");
        assertThat(pismenoRepository.findById(akt.getId()).orElseThrow().isSealFrozen()).isTrue();

        filingStore.applySeal(akt.getId(), "%PDF-pecat".getBytes(), UUID.randomUUID(), "7K3M-Q9RT-X2VW", "B-LT",
                "sha-256=:x:");
        filingStore.clearSealKey(akt.getId(), "kljuc-0000000001");
        EgopPismenoEntity pecaceni = pismenoRepository.findById(akt.getId()).orElseThrow();
        assertThat(pecaceni.isSealFrozen()).isTrue();
        assertThat(pecaceni.isSealed()).isTrue();
        assertThat(pecaceni.getPdfContent()).isEqualTo("%PDF-pecat".getBytes());
    }

    @Test
    void clearSealKey_unsealed_allowsNewFreeze() {
        EgopPismenoEntity akt = pismenoRepository.saveAndFlush(akt(submission(), UUID.randomUUID().toString()));
        filingStore.freezeForSealing(akt.getId(), "stari".getBytes(), "kljuc-stari-00001");

        filingStore.clearSealKey(akt.getId(), "kljuc-stari-00001");
        EgopFilingStore.SealRequest novi = filingStore.freezeForSealing(akt.getId(), "novi".getBytes(), "kljuc-novi-000001");

        assertThat(novi.key()).isEqualTo("kljuc-novi-000001");
        assertThat(novi.pdf()).isEqualTo("novi".getBytes());
    }

    /** Zakašnjeli pad paralelnog toka ne smije dovršeni (SYNCED) akt vratiti na FAILED. */
    @Test
    void markAktFailed_onSyncedAct_isNoOp() {
        EgopPismenoEntity akt = akt(submission(), UUID.randomUUID().toString());
        akt.applyPismeno(1, "urbroj", "103", false);
        akt.markDocumentAttached();
        pismenoRepository.saveAndFlush(akt);

        filingStore.markAktFailed(akt.getId(), "kasni pad", Instant.now());

        EgopPismenoEntity nakon = pismenoRepository.findById(akt.getId()).orElseThrow();
        assertThat(nakon.getStatus()).isEqualTo(EgopSyncStatus.SYNCED);
        assertThat(nakon.getSyncError()).isNull();
    }

    /** Pečat koji stigne nakon što je drugi tok akt priložio ne dira priloženi dokument. */
    @Test
    void applySeal_afterAttach_isNoOp() {
        EgopPismenoEntity akt = akt(submission(), UUID.randomUUID().toString());
        akt.applyPismeno(1, "urbroj", "103", false);
        akt.markDocumentAttached();
        pismenoRepository.saveAndFlush(akt);

        boolean applied = filingStore.applySeal(akt.getId(), "%PDF-pecat".getBytes(), UUID.randomUUID(),
                "7K3M-Q9RT-X2VW", "B-LT", "sha-256=:x:");

        assertThat(applied).isFalse();
        assertThat(pismenoRepository.findById(akt.getId()).orElseThrow().isSealed()).isFalse();
    }

    /**
     * Ciklus suspenzija → reaktivacija → suspenzija daje dvije obavijesti iste vrste nad istim
     * submissionom. Pod ključem iz changeseta 053 drugi upis bi pao.
     */
    @Test
    void repeatedActOfSameKind_isAllowedWithDistinctActRef() {
        UUID submissionId = submission();

        pismenoRepository.saveAndFlush(akt(submissionId, "prva-suspenzija"));
        pismenoRepository.saveAndFlush(akt(submissionId, "druga-suspenzija"));

        assertThat(pismenoRepository.findBySubmissionId(submissionId)).hasSize(2);
    }

    /** Isti akt dvaput (retry u utrci s inline slanjem) i dalje mora pasti. */
    @Test
    void sameActTwice_violatesUniqueConstraint() {
        UUID submissionId = submission();
        String actRef = UUID.randomUUID().toString();

        pismenoRepository.saveAndFlush(akt(submissionId, actRef));

        assertThatThrownBy(() -> pismenoRepository.saveAndFlush(akt(submissionId, actRef)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Registracijski akti imaju vlastiti retry preko submissiona i ne smiju ući u ovaj red. */
    @Test
    void retryCandidates_excludeRegistrationActs() {
        UUID submissionId = submission();
        EgopPismenoEntity registracijski = EgopPismenoEntity.create(submissionId,
                "Zahtjev za registracijski broj", EgopPismenoEntity.Smjer.ULAZNO, 1, "urbroj",
                "100", true);
        registracijski.markFailed("boom", Instant.now().minus(1, ChronoUnit.MINUTES));
        pismenoRepository.saveAndFlush(registracijski);

        EgopPismenoEntity zivotniCiklus = akt(submissionId, UUID.randomUUID().toString());
        zivotniCiklus.markFailed("boom", Instant.now().minus(1, ChronoUnit.MINUTES));
        pismenoRepository.saveAndFlush(zivotniCiklus);

        List<UUID> candidates = candidates();

        assertThat(candidates).contains(zivotniCiklus.getId());
        assertThat(candidates).doesNotContain(registracijski.getId());
    }

    /**
     * Akt koji još nije ni pokušan mora odstajati grace period — inače bi ga cron preoteo
     * inline slanju koje je listener upravo pokrenuo, pa bi oba zvala KreirajPismeno2.
     */
    @Test
    void retryCandidates_skipFreshUnattemptedActs() {
        UUID submissionId = submission();
        EgopPismenoEntity svjez = akt(submissionId, UUID.randomUUID().toString());
        pismenoRepository.saveAndFlush(svjez);

        Instant now = Instant.now();
        List<UUID> candidates = pismenoRepository.findRetryCandidates(
                EgopSyncStatus.SYNCED, EgopPismenoEntity.ACT_REF_REGISTRACIJA, Set.of(""), 10,
                now.minus(5, ChronoUnit.MINUTES), now.minus(1, ChronoUnit.HOURS), now);

        assertThat(candidates).doesNotContain(svjez.getId());
    }

    /** Pao je — backoff ga pušta ranije od grace perioda. */
    @Test
    void retryCandidates_failedAct_isReleasedByBackoffNotGrace() {
        UUID submissionId = submission();
        EgopPismenoEntity pao = akt(submissionId, UUID.randomUUID().toString());
        pao.markFailed("boom", Instant.now().minus(1, ChronoUnit.MINUTES));
        pismenoRepository.saveAndFlush(pao);

        Instant now = Instant.now();
        List<UUID> candidates = pismenoRepository.findRetryCandidates(
                EgopSyncStatus.SYNCED, EgopPismenoEntity.ACT_REF_REGISTRACIJA, Set.of(""), 10,
                now.minus(5, ChronoUnit.MINUTES), now.minus(1, ChronoUnit.HOURS), now);

        assertThat(candidates).contains(pao.getId());
    }

    @Test
    void retryCandidates_skipActsInBackoffOrSynced() {
        UUID submissionId = submission();

        EgopPismenoEntity uBackoffu = akt(submissionId, UUID.randomUUID().toString());
        uBackoffu.markFailed("boom", Instant.now().plus(1, ChronoUnit.HOURS));
        pismenoRepository.saveAndFlush(uBackoffu);

        EgopPismenoEntity gotov = akt(submissionId, UUID.randomUUID().toString());
        gotov.markSynced();
        pismenoRepository.saveAndFlush(gotov);

        EgopPismenoEntity naRedu = akt(submissionId, UUID.randomUUID().toString());
        pismenoRepository.saveAndFlush(naRedu);

        List<UUID> candidates = candidates();

        assertThat(candidates).contains(naRedu.getId());
        assertThat(candidates).doesNotContain(uBackoffu.getId(), gotov.getId());
    }

    /**
     * Vrsta bez šifre u eGOP šifrarniku se zapisuje radi prikaza stranci, ali je cron ne smije
     * pokupiti — inače bi vrtio slanje koje je listener namjerno preskočio, do iscrpljenja
     * pokušaja. Ovo je jedini test koji taj uvjet provjerava nad pravim upitom, a ne mockom.
     */
    @Test
    void retryCandidates_excludeTypesWithoutCodebookEntry() {
        UUID submissionId = submission();

        EgopPismenoEntity bezSifre = EgopPismenoEntity.forAct(submissionId, RN,
                UUID.randomUUID().toString(), "Obavijest o reaktivaciji registracijskog broja",
                EgopPismenoEntity.Smjer.IZLAZNO, "pdf".getBytes());
        pismenoRepository.saveAndFlush(bezSifre);

        EgopPismenoEntity urudzbiv = akt(submissionId, UUID.randomUUID().toString());
        pismenoRepository.saveAndFlush(urudzbiv);

        Instant now = Instant.now();
        List<UUID> candidates = pismenoRepository.findRetryCandidates(
                EgopSyncStatus.SYNCED, EgopPismenoEntity.ACT_REF_REGISTRACIJA,
                Set.of("Obavijest o reaktivaciji registracijskog broja"), 10,
                now.plus(1, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS), now);

        assertThat(candidates).contains(urudzbiv.getId());
        assertThat(candidates).doesNotContain(bezSifre.getId());
    }

    private List<UUID> candidates() {
        Instant now = Instant.now();
        return pismenoRepository.findRetryCandidates(
                EgopSyncStatus.SYNCED, EgopPismenoEntity.ACT_REF_REGISTRACIJA, Set.of(""), 10,
                now.plus(1, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS), now);
    }

    private UUID submission() {
        SubmissionEntity s = SubmissionEntity.create(null, UUID.randomUUID(), null, null, null, null);
        return submissionRepository.save(s).getSubmissionId();
    }

    private static EgopPismenoEntity akt(UUID submissionId, String actRef) {
        return EgopPismenoEntity.forAct(submissionId, RN, actRef,
                "Obavijest o suspenziji registracijskog broja",
                EgopPismenoEntity.Smjer.IZLAZNO, "pdf".getBytes());
    }
}
