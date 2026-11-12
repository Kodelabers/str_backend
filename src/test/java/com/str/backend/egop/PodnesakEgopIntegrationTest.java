package com.str.backend.egop;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.attachment.PismenoPrilogEntity;
import com.str.backend.attachment.PrilogEgopService;
import com.str.backend.attachment.PrilogRepository;
import com.str.backend.attachment.VrstaVlasnika;
import com.str.backend.document.StrDocumentType;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.domain.WithdrawalReason;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.exception.ConflictException;
import com.str.backend.objection.SuspensionObjectionAdminService;
import com.str.backend.objection.SuspensionObjectionDto;
import com.str.backend.objection.SuspensionObjectionRepository;
import com.str.backend.objection.SuspensionObjectionRequest;
import com.str.backend.objection.SuspensionObjectionService;
import com.str.backend.registration.RnOwner;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.RegistrationNumberLogRepository;
import com.str.backend.rn.RnDocumentsService;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RnService;
import com.str.backend.rn.RnStatusTransitionService;
import com.str.backend.rn.RucniAkt;
import com.str.backend.rn.dto.RnDocumentDto;
import hr.infodom.egov.pismeno.DohvatiListuPrilogaPismena;
import hr.infodom.egov.pismeno.PriloziPismenaInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bug s testiranja MINTS-a, nad stvarnom bazom i stvarnim AFTER_COMMIT tokom (eGOP mock, kao uz
 * {@code EGOP_ENABLED=false}): predaja očitovanja ili prigovora daje ulazno pismeno u predmetu postupka, iza
 * akta na koji odgovara, s učitanim dokumentom i ostalim datotekama kao prilozima.
 */
@SpringBootTest(properties = "str.egop.vrste.sifre.predmet-akti=9300")
@ActiveProfiles("test")
class PodnesakEgopIntegrationTest {

    private static final String OIB = "99999999990";
    private static final String SLUZBENIK = "INTERNAL:12345678903";
    private static final String RN = "HR00000032";
    private static final byte[] PDF = "%PDF-1.7 prigovor".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DOCX = {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x06, 0x00};
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};

    @Autowired private SuspensionObjectionService objectionService;
    @Autowired private SuspensionObjectionAdminService adminService;
    @Autowired private SuspensionObjectionRepository objectionRepository;
    @Autowired private RnService rnService;
    @Autowired private RnStatusTransitionService transitionService;
    @Autowired private RnDocumentsService documentsService;
    @Autowired private EgopPismenoRepository egopPismenoRepository;
    @Autowired private PrilogRepository prilogRepository;
    @Autowired private PrilogEgopService prilogEgopService;
    @Autowired private RegistrationNumberLogRepository rnLogRepository;
    @Autowired private RnRepository rnRepository;
    @Autowired private AccommodationRepository accommodationRepository;
    @Autowired private SubmissionRepository submissionRepository;
    @Autowired private LessorRepository lessorRepository;
    @Autowired private EgopClient egopClient;
    @Autowired private PlatformTransactionManager transactionManager;
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

    /**
     * Sva četiri statusa: vrsta po statusu, ulazno pismeno urudžbirano u istom predmetu kao akt na koji odgovara
     * i iza njega (veći JOP i urbroj). Glavni dokument je PDF podneska (zaglavlje Ministarstva, oznake iz eGOP-a),
     * spremljen na pismeno; dokument stranke je prilog 1, ostale njezine datoteke iza njega. Čim ima URBROJ,
     * zadnje je u „Dokumentima" RB-a, s pohranjenim PDF-om i dokumentima stranke kao prilozima.
     */
    @ParameterizedTest
    @EnumSource(value = RnStatus.class, names = {"SUSPENSION_PROPOSED", "SUSPENDED", "WITHDRAWAL_PROPOSED", "WITHDRAWN"})
    void objection_becomesIncomingPismeno_afterTheAct(RnStatus status) {
        dovedi(status);
        StrDocumentType vrsta = StrDocumentType.podnesakNaAkt(status).orElseThrow();
        EgopPismenoEntity akt = pismeno(vrsta.pobijaniAkt().orElseThrow());

        UUID objectionId = objectionService.submit(RnOwner.byOib(OIB), "NIAS:" + OIB,
                request("prigovor.pdf", PDF, 2)).objectionId();

        EgopPismenoEntity podnesak = pismeno(vrsta);
        assertThat(podnesak.getActRef()).isEqualTo("PRIGOVOR:" + objectionId);
        assertThat(podnesak.getSmjer()).isEqualTo(EgopPismenoEntity.Smjer.ULAZNO);
        assertThat(podnesak.isSynced()).isTrue();
        assertThat(podnesak.isDocumentAttached()).isTrue();
        String pdf = tekst(podnesak.getPdfContent());
        assertThat(pdf).contains(vrsta.naslov()).contains("Ministarstvo").contains(podnesak.getUrBroj())
                .contains("prigovor.pdf");
        assertThat(podnesak.isSealed()).isFalse();
        // Akt na koji podnesak odgovara urudžbiran je prije njega (redoslijed drže jedinični testovi dispatchera).
        assertThat(akt.isSynced()).isTrue();
        assertThat(podnesak.getUpdatedAt()).isAfterOrEqualTo(akt.getUpdatedAt());

        List<PismenoPrilogEntity> prilozi = prilogRepository.findAll().stream()
                .filter(p -> p.getVrstaVlasnika() == VrstaVlasnika.OBJECTION).toList();
        assertThat(prilozi).hasSize(2).allSatisfy(p -> assertThat(p.getEgopJop()).isEqualTo(podnesak.getJop()));
        assertThat(uEgopu(podnesak.getJop())).extracting(PriloziPismenaInfo::getRbrPriloga).containsExactly(1, 2, 3);
        assertThat(uEgopu(podnesak.getJop()).get(0).getNazivPriloga()).isEqualTo("prigovor.pdf");

        List<RnDocumentDto> dokumenti = documentsService.listForRn(RN);
        assertThat(dokumenti.get(dokumenti.size() - 1)).satisfies(d -> {
            assertThat(d.naziv()).isEqualTo(vrsta.vrstaPismenaNaziv());
            assertThat(d.slug()).isEqualTo(vrsta.slug());
            assertThat(d.href()).isEqualTo("/api/rn/" + RN + "/documents/pohranjeno/" + podnesak.getId());
            assertThat(d.prilozi()).hasSize(3);
            assertThat(d.prilozi().get(0).href()).isEqualTo("/api/rn/" + RN + "/documents/podnesak/" + objectionId);
        });
        assertThat(documentsService.storedAktPdf(RN, podnesak.getId()).pdf()).isEqualTo(podnesak.getPdfContent());
        assertThat(adminService.list(RN)).singleElement().satisfies(o -> {
            assertThat(o.zupanijskiPostupak()).isFalse();
            assertThat(o.urudzbiranje().urBroj()).isEqualTo(podnesak.getUrBroj());
        });
    }

    /** DOCX dokument prolazi isti tok (ekstenziju za eGOP pokrivaju jedinični testovi). */
    @Test
    void docxObjection_isFiled() {
        dovedi(RnStatus.SUSPENSION_PROPOSED);

        objectionService.submit(RnOwner.byOib(OIB), "NIAS:" + OIB, request("ocitovanje.docx", DOCX, 0));

        assertThat(pismeno(StrDocumentType.OCITOVANJE_SUSPENZIJE).isSynced()).isTrue();
    }

    /**
     * Prilog prigovora kojem se izgubila oznaka (poziv je prošao, upis nije): retry job ga preuzima preko
     * upita kandidata nad stvarnom bazom (grana OBJECTION) i ne šalje drugi put.
     */
    @Test
    void objectionAttachment_retryCandidateQuery_adoptsLostMark() {
        dovedi(RnStatus.SUSPENDED);
        objectionService.submit(RnOwner.byOib(OIB), "NIAS:" + OIB, request("prigovor.pdf", PDF, 1));
        EgopPismenoEntity podnesak = pismeno(StrDocumentType.PRIGOVOR_SUSPENZIJE);
        Long prilogId = prilogRepository.findAll().get(0).getId();
        jdbc.update("UPDATE str_rn.pismeno_prilog SET egop_jop = NULL, egop_rbr = NULL, egop_pokusaji = 1,"
                + " egop_sljedeci_pokusaj_u = ? WHERE id = ?", Timestamp.from(Instant.now().minusSeconds(60)), prilogId);

        prilogEgopService.ponovi(Instant.now());

        assertThat(prilogRepository.findById(prilogId).orElseThrow().getEgopJop()).isEqualTo(podnesak.getJop());
        // Izvornik stranke (rbr 1) i jedan prilog — ništa poslano dvaput.
        assertThat(uEgopu(podnesak.getJop())).hasSize(2);
    }

    /**
     * Županijski postupak od predaje do odluke: podnesak čeka službenika (bez eGOP-a), uvažavanje prije
     * urudžbiranja je 409, urudžbiranje ručnom KLASOM i URBROJEM daje ulazno pismeno zadnje u „Dokumentima"
     * (s prilozima i dokumentom stranke), a tek onda uvažavanje izdaje ručni akt obustave.
     */
    @Test
    void countyProcedure_registerThenAccept_endToEnd() {
        RucniAkt prijedlog = new RucniAkt("334-06/26-01/5", "2170-26-1", "Primorsko-goranska županija", null, null, 8);
        rnService.suspend(RN, RnTrigger.INSPECTION, LocalDate.now().plusDays(15), null, SLUZBENIK, List.of(), prijedlog);
        UUID objectionId = objectionService.submit(RnOwner.byOib(OIB), "NIAS:" + OIB,
                request("ocitovanje.pdf", PDF, 2)).objectionId();
        assertThat(adminService.list(RN)).singleElement().satisfies(o -> {
            assertThat(o.zupanijskiPostupak()).isTrue();
            assertThat(o.urudzbiranje()).isNull();
        });
        RucniAkt obustava = new RucniAkt("334-06/26-01/5", "2170-26-3", "Primorsko-goranska županija", null, null, 8);
        assertThatThrownBy(() -> adminService.accept(RN, objectionId, SLUZBENIK, null, obustava))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("OBJECTION_NOT_REGISTERED"));

        SuspensionObjectionDto registriran = adminService.register(RN, objectionId, SLUZBENIK,
                new RucniAkt("334-06/26-01/5", "2170-26-2", "Primorsko-goranska županija", null, null, 8));

        assertThat(registriran.urudzbiranje().klasa()).isEqualTo("334-06/26-01/5");
        assertThat(registriran.urudzbiranje().urBroj()).isEqualTo("2170-26-2");
        EgopPismenoEntity podnesak = pismeno(StrDocumentType.OCITOVANJE_SUSPENZIJE);
        assertThat(podnesak.isRucno()).isTrue();
        assertThat(podnesak.getJop()).as("nije u eGOP-u").isNull();
        String pdf = tekst(podnesak.getPdfContent());
        assertThat(pdf).startsWith("Primorsko-goranska županija").doesNotContain("Ministarstvo")
                .contains("OČITOVANJE NA PRIJEDLOG SUSPENZIJE REGISTRACIJSKOG BROJA")
                .contains("334-06/26-01/5").contains("2170-26-2").contains("ocitovanje.pdf");
        List<RnDocumentDto> dokumenti = documentsService.listForRn(RN);
        assertThat(dokumenti.get(dokumenti.size() - 1)).satisfies(d -> {
            assertThat(d.slug()).isEqualTo("ocitovanje-suspenzije");
            assertThat(d.smjer()).isEqualTo("ULAZNO");
            assertThat(d.href()).isEqualTo("/api/rn/" + RN + "/documents/pohranjeno/" + podnesak.getId());
            assertThat(d.prilozi()).hasSize(3);
            assertThat(d.prilozi().get(0).naziv()).isEqualTo("ocitovanje.pdf");
            assertThat(d.prilozi().get(0).href()).isEqualTo("/api/rn/" + RN + "/documents/podnesak/" + objectionId);
        });
        assertThat(documentsService.podnesak(RN, objectionId).content()).isEqualTo(PDF);

        // Pismeno urudžbirano prije PDF-a podneska (CDU test): prvi dohvat ga izvodi iz retka i sprema.
        jdbc.update("UPDATE str_rn.egop_pismeno SET pdf_content = NULL WHERE id = ?", podnesak.getId());
        byte[] naknadni = documentsService.storedAktPdf(RN, podnesak.getId()).pdf();
        assertThat(tekst(naknadni)).startsWith("Primorsko-goranska županija").contains("2170-26-2");
        assertThat(pismeno(StrDocumentType.OCITOVANJE_SUSPENZIJE).getPdfContent()).isEqualTo(naknadni);

        adminService.accept(RN, objectionId, SLUZBENIK, null, obustava);

        assertThat(rnRepository.findById(RN).orElseThrow().getStatus()).isEqualTo(RnStatus.ACTIVE);
        EgopPismenoEntity akt = pismeno(StrDocumentType.OBUSTAVA_SUSPENZIJE);
        assertThat(akt.isRucno()).isTrue();
        assertThat(akt.getUrBroj()).isEqualTo("2170-26-3");
        assertThat(documentsService.listForRn(RN)).extracting(RnDocumentDto::slug)
                .containsSubsequence("ocitovanje-suspenzije", "obustava-suspenzije");
    }

    /** Postupak vodi županijski službenik (ručni akt, T16): podnesak ostaje samo u STR-u. */
    @Test
    void countyProcedure_objectionStaysInStrOnly() {
        rnService.suspend(RN, RnTrigger.INSPECTION, LocalDate.now().plusDays(15), null, SLUZBENIK, List.of(),
                new RucniAkt("334-06/26-01/5", "2170-26-1", "Primorsko-goranska županija", null, null, 8));

        UUID objectionId = objectionService.submit(RnOwner.byOib(OIB), "NIAS:" + OIB,
                request("prigovor.pdf", PDF, 1)).objectionId();

        assertThat(objectionRepository.findById(objectionId)).isPresent();
        assertThat(egopPismenoRepository.findByRnOrderByCreatedAtAsc(RN))
                .noneMatch(p -> p.objectionId().isPresent())
                .noneMatch(p -> StrDocumentType.OCITOVANJE_SUSPENZIJE.vrstaPismenaNaziv().equals(p.getVrstaPismenaNaziv()));
    }

    private void dovedi(RnStatus status) {
        switch (status) {
            case SUSPENSION_PROPOSED -> rnService.suspend(RN, RnTrigger.INSPECTION, LocalDate.now().plusDays(15), null,
                    SLUZBENIK, List.of());
            case SUSPENDED -> {
                dovedi(RnStatus.SUSPENSION_PROPOSED);
                istekRoka(RnStatus.SUSPENDED, RnTrigger.DEADLINE_EXCEEDED);
            }
            case WITHDRAWAL_PROPOSED -> rnService.proposeWithdrawal(RN, WithdrawalReason.DATA_NOT_CORRECTED,
                    LocalDate.now().plusDays(15), null, SLUZBENIK, List.of());
            case WITHDRAWN -> {
                dovedi(RnStatus.WITHDRAWAL_PROPOSED);
                istekRoka(RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL_DEADLINE_EXCEEDED);
            }
            default -> throw new IllegalArgumentException(status.name());
        }
        assertThat(rnRepository.findById(RN).orElseThrow().getStatus()).isEqualTo(status);
    }

    /** Kao noćni posao: prijelaz istekom roka u vlastitoj transakciji, s AFTER_COMMIT urudžbom akta. */
    private void istekRoka(RnStatus target, RnTrigger trigger) {
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> transitionService.transition(
                rnRepository.findForUpdate(RN).orElseThrow(), target, trigger, "SYSTEM", "Rok je istekao."));
    }

    private EgopPismenoEntity pismeno(StrDocumentType vrsta) {
        return egopPismenoRepository.findByRnOrderByCreatedAtAsc(RN).stream()
                .filter(p -> vrsta.vrstaPismenaNaziv().equals(p.getVrstaPismenaNaziv()))
                .reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("nema pismena " + vrsta));
    }

    /** Tekst PDF-a u jednom retku (ekstraktor ćelije spaja bez razmaka, pa se provjeravaju samo vrijednosti). */
    private static String tekst(byte[] pdf) {
        assertThat(pdf).isNotNull();
        try {
            com.lowagie.text.pdf.PdfReader reader = new com.lowagie.text.pdf.PdfReader(pdf);
            try {
                com.lowagie.text.pdf.parser.PdfTextExtractor extractor =
                        new com.lowagie.text.pdf.parser.PdfTextExtractor(reader);
                StringBuilder sb = new StringBuilder();
                for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                    sb.append(extractor.getTextFromPage(page)).append('\n');
                }
                return sb.toString().replaceAll("\\s+", " ").strip();
            } finally {
                reader.close();
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
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

    private static SuspensionObjectionRequest request(String naziv, byte[] sadrzaj, int dodatnih) {
        SuspensionObjectionRequest req = new SuspensionObjectionRequest();
        req.setRegistrationNumber(RN);
        req.setDatoteka(new MockMultipartFile("datoteka", naziv, null, sadrzaj));
        req.setDatoteke(java.util.stream.IntStream.range(0, dodatnih)
                .mapToObj(i -> (MultipartFile) new MockMultipartFile("datoteke", "dodatak-" + i + ".png", null, PNG))
                .toList());
        return req;
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
                "Primorsko-goranska županija", "Rijeka", "Korzo", "32",
                4, 8, OfferType.PRIMARY_RESIDENCE, Offering.WHOLE, true, false, true);
        acc.setName("Objekt " + RN);
        accommodationRepository.save(acc);
        rnRepository.save(RnEntity.issue(RN, submission.getSubmissionId(), acc.getAccommodationId(), LocalDate.now()));
    }
}
