package com.str.backend.rn;

import com.str.backend.attachment.PrilogDto;
import com.str.backend.attachment.PrilogRepository.PrilogSazetak;
import com.str.backend.attachment.PrilogService;
import com.str.backend.attachment.PrilogVlasnik;
import com.str.backend.attachment.VrstaVlasnika;
import com.str.backend.common.Uuids;
import com.str.backend.common.Zagreb;
import com.str.backend.document.FilingReference;
import com.str.backend.document.StrDocumentType;
import com.str.backend.egop.EgopPismenoEntity;
import com.str.backend.egop.EgopPismenoRepository;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.objection.PodnesakPdfService;
import com.str.backend.objection.SuspensionObjectionFileDto;
import com.str.backend.objection.SuspensionObjectionRepository;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.dto.RnDocumentDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Sastavlja popis svih dokumenata vezanih uz registracijski broj, za sekciju „Dokumenti" na
 * detalju RB-a ({@code /registar/{rn}}). Spaja tri izvora u jedinstven popis:
 *
 * <ol>
 *   <li><b>Zahtjev</b> — pohranjen na {@code submission.pdf_content} (ako postoji);</li>
 *   <li><b>Obavijest o dodjeli</b> — renderira se na zahtjev iz ZUP predloška (potvrda o
 *       činjenici bez promjenjivih polja, pa je re-render vjerodostojan; urudžbene oznake
 *       dolaze iz {@link #dodjelaFiling} pa PDF nosi isti URBROJ kao verzija poslana eGOP-u);</li>
 *   <li><b>Akti životnog ciklusa</b> — {@code egop_pismeno} retci vezani uz RB, s već
 *       pohranjenim (vjerodostojnim) PDF-om: obavijesti o prijedlogu suspenzije, obustavi
 *       postupka suspenzije, suspenziji, reaktivaciji, povlačenju i opozivu. Zapisuje ih
 *       {@code RnLifecycleFilingListener} pri svakom prijelazu koji
 *       {@link StrDocumentType#forTransition} mapira na akt, i kad je eGOP ugašen.</li>
 * </ol>
 *
 * <p>Očitovanja i prigovori iznajmljivača imaju svoje ulazno pismeno u {@code egop_pismeno}
 * ({@code act_ref} = {@code PRIGOVOR:<objection_id>}) i ovdje se prikazuju tek kad su <b>urudžbirani</b>
 * (imaju URBROJ): u postupku MINTS-a kad eGOP vrati URBROJ, u županijskom kad ga službenik upiše
 * ({@code SuspensionObjectionAdminService#register}). Dokument je pohranjeni PDF podneska
 * ({@code /documents/pohranjeno/{id}}), a prilozi su datoteka koju je stranka učitala
 * ({@code /documents/podnesak/{objectionId}}) i njezine ostale datoteke.
 *
 * <p>Uz svako pismeno idu i njegovi prilozi (T17): uz zahtjev prilozi zahtjeva, uz akt prijelaza
 * prilozi tog prijelaza — uz ulazno pismeno stranke, uz izlazni akt službenika.
 *
 * <p>Servis čita i sastavlja podatke (jedini upis je naknadni PDF podneska u {@link #storedAktPdf});
 * sam render i download rade endpointi na
 * {@code RnController}.
 *
 * <p><b>{@code str.rn.documents.zahtjev-visible}</b> (default {@code true}, na svim okolinama)
 * s {@code false} izbacuje zahtjev iz popisa. CDU i preprod su ga do 22.10.2026. skrivali po
 * zahtjevu naručitelja; sada popis nosi sva ulazna i izlazna pismena. Zastavica je
 * <b>isključivo za popis</b>: podnesak i dalje postoji, nosi urudžbeni
 * broj 1, ostaje pohranjen na {@code submission.pdf_content} i dohvatljiv na
 * {@code GET /api/rn/{rn}/documents/zahtjev} te na
 * {@code GET /api/generateRegistrationNumber/{submissionId}/pdf}. Ne pretvarati je u 404 —
 * podnesak je po čl. 71. ZUP-a dio spisa.
 */
@Service
public class RnDocumentsService {

    private static final Logger log = LoggerFactory.getLogger(RnDocumentsService.class);

    /** Pohranjeni dokument spreman za download — s čitljivim nazivom datoteke. */
    public record StoredDocument(String filename, byte[] pdf) {}

    private final RnRepository rnRepository;
    private final SubmissionRepository submissionRepository;
    private final EgopPismenoRepository egopPismenoRepository;
    private final PrilogService prilogService;
    private final SuspensionObjectionRepository objectionRepository;
    private final PodnesakPdfService podnesakPdf;
    private final boolean zahtjevVisible;

    public RnDocumentsService(RnRepository rnRepository,
                              SubmissionRepository submissionRepository,
                              EgopPismenoRepository egopPismenoRepository,
                              PrilogService prilogService,
                              SuspensionObjectionRepository objectionRepository,
                              PodnesakPdfService podnesakPdf,
                              @Value("${str.rn.documents.zahtjev-visible:true}") boolean zahtjevVisible) {
        this.podnesakPdf = podnesakPdf;
        this.rnRepository = rnRepository;
        this.submissionRepository = submissionRepository;
        this.egopPismenoRepository = egopPismenoRepository;
        this.prilogService = prilogService;
        this.objectionRepository = objectionRepository;
        this.zahtjevVisible = zahtjevVisible;
    }

    @Transactional(readOnly = true)
    public List<RnDocumentDto> listForRn(String rn) {
        RnEntity entity = rnRepository.findById(rn)
                .orElseThrow(() -> new ResourceNotFoundException("rn not found: " + rn));

        List<RnDocumentDto> out = new ArrayList<>();

        SubmissionEntity submission = entity.getSubmissionId() != null
                ? submissionRepository.findById(entity.getSubmissionId()).orElse(null)
                : null;

        if (zahtjevVisible && submission != null && submission.getPdfContent() != null) {
            LocalDate izdano = toLocalDate(submission.getFilingDate() != null
                    ? submission.getFilingDate() : submission.getCreatedAt());
            out.add(new RnDocumentDto(null, StrDocumentType.ZAHTJEV.slug(),
                    StrDocumentType.ZAHTJEV.vrstaPismenaNaziv(), StrDocumentType.ZAHTJEV.smjer().name(),
                    izdano, href(rn, StrDocumentType.ZAHTJEV.slug()),
                    prilozi(rn, prilogService.zaVlasnika(PrilogVlasnik.zahtjev(entity.getSubmissionId())))));
        }

        // Obavijest o dodjeli — svaki izdani RB je ima; renderira se on-demand.
        out.add(new RnDocumentDto(null, StrDocumentType.DODJELA.slug(),
                StrDocumentType.DODJELA.vrstaPismenaNaziv(), StrDocumentType.DODJELA.smjer().name(),
                entity.getIssueDate(), href(rn, StrDocumentType.DODJELA.slug())));

        List<EgopPismenoEntity> akti = egopPismenoRepository.findByRnOrderByCreatedAtAsc(rn);
        // Prilozi prijelaza, jednim upitom: akt nosi act_ref = log_id prijelaza.
        Map<UUID, List<PrilogSazetak>> priloziPrijelaza = prilogService.zaVlasnike(VrstaVlasnika.RN_LOG,
                akti.stream().map(a -> Uuids.parse(a.getActRef())).flatMap(Optional::stream).toList());
        // Urudžbirana očitovanja i prigovori: dokumenti stranke (izvornik s prigovora i dodatne datoteke),
        // po jedan upit za sve.
        List<UUID> podnesci = akti.stream().filter(RnDocumentsService::urudzbiranPodnesak)
                .map(EgopPismenoEntity::objectionId).flatMap(Optional::stream).toList();
        Map<UUID, List<PrilogSazetak>> priloziPodnesaka = prilogService.zaVlasnike(VrstaVlasnika.OBJECTION, podnesci);
        // Sažeci bez BLOB-a; jedan upit po RB-u, samo kad ima urudžbiranih podnesaka.
        Map<UUID, SuspensionObjectionRepository.ObjectionSummary> izvornici = podnesci.isEmpty() ? Map.of()
                : objectionRepository.findSummariesByRn(rn).stream()
                        .filter(o -> podnesci.contains(o.getObjectionId()))
                        .collect(Collectors.toMap(SuspensionObjectionRepository.ObjectionSummary::getObjectionId,
                                o -> o));
        for (EgopPismenoEntity akt : akti) {
            Optional<UUID> objectionId = akt.objectionId();
            if (objectionId.isPresent()) {
                // Podnesak stranke (očitovanje, prigovor). Dok nije urudžbiran, nije dio spisa — vidi se samo u
                // panelu prigovora. Glavni dokument je PDF podneska na pismenu, a dokumenti stranke su prilozi —
                // osim kod starog pismena MINTS-a (bez PDF-a), kojem je u eGOP-u glavni dokument izvornik
                // stranke: STR prikazuje isto što je u eGOP-u.
                if (urudzbiranPodnesak(akt)) {
                    String slug = StrDocumentType.fromVrstaPismenaNaziv(akt.getVrstaPismenaNaziv())
                            .map(StrDocumentType::slug).orElse(null);
                    List<PrilogDto> prilozi = new ArrayList<>();
                    String dokument;
                    if (imaPdfPodneska(akt)) {
                        dokument = href(rn, "pohranjeno/" + akt.getId());
                        Optional.ofNullable(izvornici.get(objectionId.get()))
                                .map(o -> izvornik(rn, o))
                                .ifPresent(prilozi::add);
                    } else {
                        dokument = href(rn, "podnesak/" + objectionId.get());
                    }
                    prilozi.addAll(prilozi(rn, priloziPodnesaka.getOrDefault(objectionId.get(), List.of())));
                    out.add(new RnDocumentDto(akt.getId(), slug, akt.getVrstaPismenaNaziv(), akt.getSmjer().name(),
                            toLocalDate(akt.getCreatedAt()), dokument, prilozi));
                }
                continue;
            }
            if (akt.getPdfContent() == null) {
                continue; // akt bez pohranjenog dokumenta se ne može ponuditi na preuzimanje
            }
            String slug = StrDocumentType.fromVrstaPismenaNaziv(akt.getVrstaPismenaNaziv())
                    .map(StrDocumentType::slug).orElse(null);
            boolean izlazno = akt.getSmjer() == EgopPismenoEntity.Smjer.IZLAZNO;
            List<PrilogSazetak> prilozi = Uuids.parse(akt.getActRef()).map(id -> priloziPrijelaza.getOrDefault(id, List.of()))
                    .orElse(List.of()).stream()
                    .filter(p -> PrilogService.pripadaPismenu(p, izlazno))
                    .toList();
            out.add(new RnDocumentDto(akt.getId(), slug, akt.getVrstaPismenaNaziv(),
                    akt.getSmjer().name(), toLocalDate(akt.getCreatedAt()),
                    href(rn, "pohranjeno/" + akt.getId()), prilozi(rn, prilozi)));
        }

        out.sort(Comparator.comparing(RnDocumentDto::izdano,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    /** Bajtovi zahtjeva (podneska) za RB — s {@code submission.pdf_content}. */
    @Transactional(readOnly = true)
    public byte[] zahtjevPdf(String rn) {
        RnEntity entity = rnRepository.findById(rn)
                .orElseThrow(() -> new ResourceNotFoundException("rn not found: " + rn));
        byte[] pdf = entity.getSubmissionId() == null ? null
                : submissionRepository.findById(entity.getSubmissionId())
                        .map(SubmissionEntity::getPdfContent).orElse(null);
        if (pdf == null) {
            throw new ResourceNotFoundException("zahtjev pdf not found for rn: " + rn);
        }
        return pdf;
    }

    /**
     * Pohranjeni PDF akta životnog ciklusa ili podneska; 404 ako pismeno ne pripada RB-u ili nema dokumenta.
     * Ručno (županijsko) urudžbirani podnesak zapisan prije PDF-a podneska (T12) ga dobiva ovdje: PDF se izvodi
     * iz retka ({@link PodnesakPdfService#zaRucnoPismeno}) i sprema uvjetno; ako ga je istodobni dohvat spremio
     * prvi, vraća se spremljeni, pa je svaki dohvat isti dokument. Staro pismeno MINTS-a se ne dira.
     */
    @Transactional
    public StoredDocument storedAktPdf(String rn, UUID aktId) {
        EgopPismenoEntity akt = egopPismenoRepository.findById(aktId)
                .filter(a -> rn.equals(a.getRn()))
                .orElseThrow(() -> new ResourceNotFoundException("document not found: " + aktId));
        String filename = filename(akt, rn);
        byte[] pdf = akt.getPdfContent();
        if (pdf == null && akt.isRucno() && urudzbiranPodnesak(akt)) {
            byte[] izveden = podnesakPdf.zaRucnoPismeno(akt);
            if (egopPismenoRepository.upisiPdfAkoNema(aktId, izveden, Instant.now()) == 1) {
                pdf = izveden;
                log.info("podnesak_pdf_naknadno pismeno={} rn={} vrsta='{}' urbroj={} — PDF podneska izveden iz"
                        + " retka i spremljen", aktId, rn, akt.getVrstaPismenaNaziv(), akt.getUrBroj());
            } else {
                pdf = egopPismenoRepository.findPdfContent(aktId);
            }
        }
        if (pdf == null) {
            throw new ResourceNotFoundException("document has no pdf: " + aktId);
        }
        return new StoredDocument(filename, pdf);
    }

    /** Dokument koji je stranka učitala uz podnesak — prvi prilog, preuzima se s prigovora. */
    private static PrilogDto izvornik(String rn, SuspensionObjectionRepository.ObjectionSummary o) {
        return new PrilogDto(null, o.getFileName(), o.getFileSize(), o.getContentType(),
                href(rn, "podnesak/" + o.getObjectionId()));
    }

    /**
     * Dokument urudžbiranog očitovanja ili prigovora — datoteka koju je stranka učitala (PDF, DOC ili DOCX).
     * 404 kad prigovor ne pripada RB-u ili podnesak još nije urudžbiran: tada nije u „Dokumentima".
     */
    @Transactional(readOnly = true)
    public SuspensionObjectionFileDto podnesak(String rn, UUID objectionId) {
        egopPismenoRepository.findFirstByRnAndActRefAndUrBrojIsNotNull(rn, EgopPismenoEntity.actRefPodneska(objectionId))
                .orElseThrow(() -> new ResourceNotFoundException("document not found: " + objectionId));
        return objectionRepository.findByObjectionIdAndRn(objectionId, rn)
                .map(o -> new SuspensionObjectionFileDto(o.getFileName(), o.getContentType(), o.getFileContent()))
                .orElseThrow(() -> new ResourceNotFoundException("document not found: " + objectionId));
    }

    /**
     * Podnesak čiji je glavni dokument PDF podneska: ima ga spremljenog, ili je ručni (dobiva ga pri dohvatu).
     * Staro pismeno MINTS-a bez PDF-a nema — u eGOP-u mu je glavni dokument izvornik stranke.
     */
    private static boolean imaPdfPodneska(EgopPismenoEntity akt) {
        return akt.getPdfContent() != null || akt.isRucno();
    }

    /** Ulazno pismeno očitovanja ili prigovora kojem je dodijeljen URBROJ (eGOP ili županijski službenik). */
    private static boolean urudzbiranPodnesak(EgopPismenoEntity akt) {
        return akt.objectionId().isPresent() && akt.getUrBroj() != null;
    }

    /**
     * Pečaćena obavijest o dodjeli, kad postoji — ista koja je priložena u eGOP i otpremljena u KP.
     * Bez nje se obavijest renderira na zahtjev (eSeal ugašen, ili pečat još nije stigao).
     */
    @Transactional(readOnly = true)
    public Optional<byte[]> sealedDodjela(String rn) {
        RnEntity entity = rnRepository.findById(rn)
                .orElseThrow(() -> new ResourceNotFoundException("rn not found: " + rn));
        if (entity.getSubmissionId() == null) {
            return Optional.empty();
        }
        return egopPismenoRepository.findBySubmissionIdAndVrstaPismenaNazivAndActRef(entity.getSubmissionId(),
                        StrDocumentType.DODJELA.vrstaPismenaNaziv(), EgopPismenoEntity.ACT_REF_REGISTRACIJA)
                .filter(EgopPismenoEntity::isSealed)
                .map(EgopPismenoEntity::getPdfContent);
    }

    /**
     * Urudžbene oznake obavijesti o dodjeli — KLASA s predmeta, URBROJ i JOP s izlaznog
     * pismena registracije (isti brojevi kao na verziji poslanoj eGOP-u). Kad urudžbiranje nije
     * (uspješno) provedeno, URBROJ i JOP ostaju prazni i akt se renderira samo s KLASOM.
     */
    @Transactional(readOnly = true)
    public FilingReference dodjelaFiling(String rn) {
        RnEntity entity = rnRepository.findById(rn)
                .orElseThrow(() -> new ResourceNotFoundException("rn not found: " + rn));
        UUID submissionId = entity.getSubmissionId();
        if (submissionId == null) {
            return FilingReference.NONE;
        }
        String klasa = submissionRepository.findById(submissionId)
                .map(SubmissionEntity::getEgopKlasa).orElse(null);
        Optional<EgopPismenoEntity> izlazno =
                egopPismenoRepository.findBySubmissionIdAndVrstaPismenaNazivAndActRef(
                        submissionId, StrDocumentType.DODJELA.vrstaPismenaNaziv(),
                        EgopPismenoEntity.ACT_REF_REGISTRACIJA);
        return new FilingReference(klasa,
                izlazno.map(EgopPismenoEntity::getUrBroj).orElse(null),
                izlazno.map(EgopPismenoEntity::getJop).orElse(null));
    }

    /** npr. {@code suspenzija-HR180000123456789001.pdf}; padne na {@code akt} ako vrsta nije poznata. */
    private static String filename(EgopPismenoEntity akt, String rn) {
        String slug = StrDocumentType.fromVrstaPismenaNaziv(akt.getVrstaPismenaNaziv())
                .map(StrDocumentType::slug).orElse("akt");
        return slug + "-" + rn + ".pdf";
    }

    private static List<PrilogDto> prilozi(String rn, List<PrilogSazetak> prilozi) {
        return prilozi.stream().map(p -> PrilogDto.of(rn, p)).toList();
    }

    private static String href(String rn, String segment) {
        return "/api/rn/" + rn + "/documents/" + segment;
    }

    private static LocalDate toLocalDate(Instant instant) {
        // Hrvatski dan, kao urudzbiranje.datum na prigovoru; kontejner radi u UTC-u.
        return instant == null ? null : instant.atZone(Zagreb.ZONE).toLocalDate();
    }
}
