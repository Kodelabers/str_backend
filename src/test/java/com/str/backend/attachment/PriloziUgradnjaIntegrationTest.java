package com.str.backend.attachment;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.document.StrDocumentType;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.domain.WithdrawalReason;
import com.str.backend.egop.EgopClient;
import com.str.backend.egop.EgopPismenoEntity;
import com.str.backend.egop.EgopPismenoRepository;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.lessor.LessorRnActionService;
import com.str.backend.objection.SuspensionObjectionAdminService;
import com.str.backend.objection.SuspensionObjectionDto;
import com.str.backend.objection.SuspensionObjectionRepository;
import com.str.backend.objection.SuspensionObjectionRequest;
import com.str.backend.objection.SuspensionObjectionService;
import com.str.backend.registration.RnOwner;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.RegistrationNumberLogEntity;
import com.str.backend.rn.RegistrationNumberLogRepository;
import com.str.backend.rn.RnDocumentsService;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RnService;
import com.str.backend.rn.dto.RnDocumentDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import hr.infodom.egov.pismeno.DohvatiListuPrilogaPismena;
import hr.infodom.egov.pismeno.PriloziPismenaInfo;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T17/B-2 nad stvarnom pohranom i stvarnim transakcijama: prilozi se vežu u transakciji radnje
 * ({@code PrilogService#bind} je {@code MANDATORY}), neispravan prilog poništava radnju, prilozi
 * opoziva idu uz ulazno pismeno „Zahtjev za opoziv", a prigovor nosi više datoteka.
 *
 * <p>Mock eGOP-a ovdje ima šifru vrste predmeta akata, pa akti prolaze <b>stvarni</b> AFTER_COMMIT tok
 * ({@code RnLifecycleFilingListener} → {@code EgopAktDispatcher} → {@code EgopFilingService.fileAct} →
 * {@code PrilogEgopService}) — bez transakcije pozivatelja, kao u produkciji.
 */
@SpringBootTest(properties = "str.egop.vrste.sifre.predmet-akti=9300")
@ActiveProfiles("test")
class PriloziUgradnjaIntegrationTest {

    private static final String OIB = "99999999990";
    private static final String STRANKA = Ucitao.nias(OIB);
    private static final String SLUZBENIK = "INTERNAL:12345678903";
    private static final String RN = "HR00000031";
    private static final byte[] PDF = "%PDF-1.7 prilog".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};

    @Autowired private PrilogService prilogService;
    @Autowired private PrilogRepository prilogRepository;
    @Autowired private RnService rnService;
    @Autowired private LessorRnActionService lessorRnActionService;
    @Autowired private SuspensionObjectionService objectionService;
    @Autowired private SuspensionObjectionAdminService objectionAdminService;
    @Autowired private SuspensionObjectionRepository objectionRepository;
    @Autowired private RnDocumentsService documentsService;
    @Autowired private RegistrationNumberLogRepository rnLogRepository;
    @Autowired private EgopPismenoRepository egopPismenoRepository;
    @Autowired private RnRepository rnRepository;
    @Autowired private AccommodationRepository accommodationRepository;
    @Autowired private SubmissionRepository submissionRepository;
    @Autowired private LessorRepository lessorRepository;
    @Autowired private EgopClient egopClient;
    @Autowired private PrilogEgopService prilogEgopService;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        prilogRepository.deleteAll();
        objectionRepository.deleteAll();
        egopPismenoRepository.deleteAll();
        rnLogRepository.deleteAll();
        rnRepository.deleteAll();
        accommodationRepository.deleteAll();
        submissionRepository.deleteAll();
        lessorRepository.deleteAll();
        seedRn(lessor());
    }

    @Test
    void suspend_bindsOfficialAttachments_toNewLogEntry_inSameTransaction() {
        Long id = prilogService.stage(SLUZBENIK, pdf("zapisnik.pdf")).id();

        rnService.suspend(RN, RnTrigger.INSPECTION, LocalDate.now().plusDays(15), null, SLUZBENIK, List.of(id));

        UUID logId = log(RnStatus.SUSPENSION_PROPOSED).getLogId();
        PismenoPrilogEntity prilog = prilogRepository.findById(id).orElseThrow();
        assertThat(prilog.getVrstaVlasnika()).isEqualTo(VrstaVlasnika.RN_LOG);
        assertThat(prilog.getVlasnikId()).isEqualTo(logId);
    }

    /** Prilog stranke ne može ići uz akt službenika: 404 (tuđi id se ne otkriva), a prijedlog se poništava. */
    @Test
    void suspend_foreignAttachment_rollsBackTheAction() {
        Long tudi = prilogService.stage(STRANKA, pdf("tudi.pdf")).id();

        assertThatThrownBy(() -> rnService.suspend(RN, RnTrigger.INSPECTION, LocalDate.now().plusDays(15), null,
                SLUZBENIK, List.of(tudi)))
                .isInstanceOfSatisfying(PrilogException.class,
                        e -> assertThat(e.getCode()).isEqualTo(PrilogException.NOT_FOUND));

        assertThat(rnRepository.findById(RN).orElseThrow().getStatus()).isEqualTo(RnStatus.ACTIVE);
        assertThat(rnLogRepository.findFirstByRnAndToStatusOrderByOccurredAtDesc(RN, RnStatus.SUSPENSION_PROPOSED.name()))
                .isEmpty();
        assertThat(prilogRepository.findById(tudi).orElseThrow().isVezan()).isFalse();
    }

    /** Istekao (obrisan noćnim čišćenjem) prilog: 404, prijedlog povlačenja (T12) se ne događa. */
    @Test
    void proposeWithdrawal_expiredAttachment_rollsBackTheAction() {
        assertThatThrownBy(() -> rnService.proposeWithdrawal(RN, WithdrawalReason.DATA_NOT_CORRECTED,
                LocalDate.now().plusDays(15), null, SLUZBENIK, List.of(987_654L)))
                .isInstanceOfSatisfying(PrilogException.class,
                        e -> assertThat(e.getCode()).isEqualTo(PrilogException.NOT_FOUND));

        assertThat(rnRepository.findById(RN).orElseThrow().getStatus()).isEqualTo(RnStatus.ACTIVE);
    }

    /**
     * Opoziv s prilogom stranke: prilog je vezan uz zapis prijelaza REVOCATION, a od dva pismena
     * tog prijelaza (T15) ide samo uz ulazni „Zahtjev za opoziv" — u popisu dokumenata i u eGOP-u.
     */
    @Test
    void revocation_partyAttachments_goToZahtjevZaOpoziv() {
        Long id = prilogService.stage(STRANKA, pdf("izjava.pdf")).id();

        lessorRnActionService.withdrawOwnByOib(RN, OIB, null, List.of(id));

        UUID logId = log(RnStatus.REVOKED).getLogId();
        assertThat(prilogRepository.findById(id).orElseThrow().getVlasnikId()).isEqualTo(logId);

        List<RnDocumentDto> docs = documentsService.listForRn(RN);
        String zahtjev = StrDocumentType.ZAHTJEV_OPOZIVA.vrstaPismenaNaziv();
        String obavijest = StrDocumentType.OPOZIV.vrstaPismenaNaziv();
        assertThat(docs).filteredOn(d -> zahtjev.equals(d.naziv())).singleElement()
                .satisfies(d -> assertThat(d.prilozi()).extracting(PrilogDto::naziv).containsExactly("izjava.pdf"));
        assertThat(docs).filteredOn(d -> obavijest.equals(d.naziv())).singleElement()
                .satisfies(d -> assertThat(d.prilozi()).isEmpty());
        // Stvarni AFTER_COMMIT tok: prilog stranke priložen je uz ulazni zahtjev, ne uz obavijest.
        EgopPismenoEntity zahtjevPismeno = egopPismenoRepository.findByRnOrderByCreatedAtAsc(RN).stream()
                .filter(p -> zahtjev.equals(p.getVrstaPismenaNaziv())).findFirst().orElseThrow();
        assertThat(zahtjevPismeno.getSmjer()).isEqualTo(EgopPismenoEntity.Smjer.ULAZNO);
        assertThat(zahtjevPismeno.isSynced()).isTrue();
        assertThat(prilogRepository.findById(id).orElseThrow().getEgopJop()).isEqualTo(zahtjevPismeno.getJop());
    }

    /**
     * C1: prilaganje ide iz AFTER_COMMIT listenera, bez transakcije pozivatelja. Oznaka {@code egop_jop}
     * mora se upisati (REQUIRES_NEW) — ranije je upis pao nakon uspješnog SOAP poziva, pa bi retry prilog
     * poslao drugi put. Svaki prilog je u eGOP-u točno jednom, i nakon ponovljenih retryja.
     */
    @Test
    void suspend_afterCommit_filesAct_andAttachesEachAttachmentExactlyOnce() {
        Long zapisnik = prilogService.stage(SLUZBENIK, pdf("zapisnik.pdf")).id();
        Long foto = prilogService.stage(SLUZBENIK, new MockMultipartFile("datoteka", "foto.png", null, PNG)).id();

        rnService.suspend(RN, RnTrigger.INSPECTION, LocalDate.now().plusDays(15), null, SLUZBENIK,
                List.of(zapisnik, foto));

        EgopPismenoEntity akt = egopPismenoRepository.findByRnOrderByCreatedAtAsc(RN).stream()
                .filter(p -> StrDocumentType.PRIJEDLOG_SUSPENZIJE.vrstaPismenaNaziv().equals(p.getVrstaPismenaNaziv()))
                .findFirst().orElseThrow();
        assertThat(akt.isSynced()).isTrue();
        assertThat(akt.getJop()).isNotNull();
        PismenoPrilogEntity prvi = prilogRepository.findById(zapisnik).orElseThrow();
        PismenoPrilogEntity drugi = prilogRepository.findById(foto).orElseThrow();
        assertThat(prvi.getEgopJop()).isEqualTo(akt.getJop());
        assertThat(prvi.getEgopRbr()).isEqualTo(1);
        assertThat(drugi.getEgopJop()).isEqualTo(akt.getJop());
        assertThat(drugi.getEgopRbr()).isEqualTo(2);
        assertThat(uEgopu(akt.getJop())).extracting(PriloziPismenaInfo::getRbrPriloga).containsExactly(1, 2);

        // Retry bez ičega za poslati ne šalje ništa.
        prilogEgopService.ponovi(Instant.now().plusSeconds(3600));
        assertThat(uEgopu(akt.getJop())).hasSize(2);

        // Izgubljena oznaka (poziv je prošao, upis nije): retry job prilog preuzima iz eGOP-a, ne šalje ga
        // drugi put. Ujedno prolazi upit kandidata (findPismenaZaPonovnoPrilaganje) nad stvarnom bazom.
        jdbc.update("UPDATE str_rn.pismeno_prilog SET egop_jop = NULL, egop_rbr = NULL, egop_pokusaji = 1,"
                + " egop_sljedeci_pokusaj_u = ? WHERE id = ?", Timestamp.from(Instant.now().minusSeconds(60)), zapisnik);
        prilogEgopService.ponovi(Instant.now());

        assertThat(prilogRepository.findById(zapisnik).orElseThrow().getEgopJop()).isEqualTo(akt.getJop());
        assertThat(uEgopu(akt.getJop())).extracting(PriloziPismenaInfo::getRbrPriloga).containsExactly(1, 2);
    }

    private List<PriloziPismenaInfo> uEgopu(int jop) {
        DohvatiListuPrilogaPismena request = new DohvatiListuPrilogaPismena();
        request.setJop(jop);
        try {
            return egopClient.dohvatiListuPrilogaPismena(request);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Prigovor s 3 datoteke: prva u prigovoru, dvije u prilozima prigovora; službenik vidi sve. */
    @Test
    void objection_withThreeFiles_storesAllAndListsThemForOfficial() {
        rnService.suspend(RN, RnTrigger.INSPECTION, LocalDate.now().plusDays(15), null, SLUZBENIK, List.of());
        SuspensionObjectionRequest req = objectionRequest(2);

        UUID objectionId = objectionService.submit(RnOwner.byOib(OIB), "NIAS:" + OIB, req).objectionId();

        assertThat(objectionRepository.findById(objectionId).orElseThrow().getFileName()).isEqualTo("prigovor.pdf");
        List<SuspensionObjectionDto> list = objectionAdminService.list(RN);
        assertThat(list).singleElement().satisfies(o -> {
            assertThat(o.fileName()).isEqualTo("prigovor.pdf");
            assertThat(o.prilozi()).extracting(PrilogDto::naziv).containsExactly("dodatak-0.png", "dodatak-1.png");
            assertThat(o.prilozi()).extracting(PrilogDto::contentType).containsOnly("image/png");
        });
        // Prilog prigovora preuzima se uz RB, kao i ostali prilozi.
        Long prviPrilog = list.get(0).prilozi().get(0).id();
        assertThat(prilogService.zaPreuzimanje(RN, prviPrilog).naziv()).isEqualTo("dodatak-0.png");
    }

    /** Dokument + 5 dodatnih = 6 datoteka: prigovor se ne sprema. */
    @Test
    void objection_withSixFiles_isRejected_andNothingStored() {
        rnService.suspend(RN, RnTrigger.INSPECTION, LocalDate.now().plusDays(15), null, SLUZBENIK, List.of());

        assertThatThrownBy(() -> objectionService.submit(RnOwner.byOib(OIB), "NIAS:" + OIB, objectionRequest(5)))
                .isInstanceOfSatisfying(PrilogException.class,
                        e -> assertThat(e.getCode()).isEqualTo(PrilogException.COUNT));

        assertThat(objectionRepository.count()).isZero();
        assertThat(prilogRepository.count()).isZero();
    }

    /** Neispravna dodatna datoteka poništava i prigovor (ista transakcija). */
    @Test
    void objection_withInvalidExtraFile_rollsBackObjection() {
        rnService.suspend(RN, RnTrigger.INSPECTION, LocalDate.now().plusDays(15), null, SLUZBENIK, List.of());
        SuspensionObjectionRequest req = objectionRequest(0);
        req.setDatoteke(List.of(new MockMultipartFile("datoteke", "lazni.pdf", null, "nije pdf".getBytes())));

        assertThatThrownBy(() -> objectionService.submit(RnOwner.byOib(OIB), "NIAS:" + OIB, req))
                .isInstanceOfSatisfying(PrilogException.class,
                        e -> assertThat(e.getCode()).isEqualTo(PrilogException.TYPE));

        assertThat(objectionRepository.count()).isZero();
    }

    private RegistrationNumberLogEntity log(RnStatus to) {
        return rnLogRepository.findFirstByRnAndToStatusOrderByOccurredAtDesc(RN, to.name()).orElseThrow();
    }

    private static SuspensionObjectionRequest objectionRequest(int dodatnih) {
        SuspensionObjectionRequest req = new SuspensionObjectionRequest();
        req.setRegistrationNumber(RN);
        req.setDatoteka(new MockMultipartFile("datoteka", "prigovor.pdf", null, PDF));
        req.setDatoteke(IntStream.range(0, dodatnih)
                .mapToObj(i -> (org.springframework.web.multipart.MultipartFile)
                        new MockMultipartFile("datoteke", "dodatak-" + i + ".png", null, PNG))
                .toList());
        return req;
    }

    private static MockMultipartFile pdf(String naziv) {
        return new MockMultipartFile("datoteka", naziv, "application/pdf", PDF);
    }

    private UUID lessor() {
        LessorEntity l = LessorEntity.create("Ivan", "Ivić", "Korzo", "2", "Rijeka",
                "Primorsko-goranska županija", OIB + "@example.hr");
        l.setLessorOib(OIB);
        return lessorRepository.save(l).getLessorId();
    }

    private void seedRn(UUID lessorId) {
        SubmissionEntity submission = submissionRepository.save(
                SubmissionEntity.create("FN-" + RN, lessorId, 1L, Instant.now(), null, null));
        AccommodationEntity acc = AccommodationEntity.create(submission.getSubmissionId(),
                "Primorsko-goranska županija", "Rijeka", "Korzo", "31",
                4, 8, OfferType.PRIMARY_RESIDENCE, Offering.WHOLE, true, false, true);
        acc.setName("Objekt " + RN);
        accommodationRepository.save(acc);
        rnRepository.save(RnEntity.issue(RN, submission.getSubmissionId(), acc.getAccommodationId(), LocalDate.now()));
    }
}
