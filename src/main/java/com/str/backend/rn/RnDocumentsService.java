package com.str.backend.rn;

import com.str.backend.document.FilingReference;
import com.str.backend.document.StrDocumentType;
import com.str.backend.egop.EgopPismenoEntity;
import com.str.backend.egop.EgopPismenoRepository;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.dto.RnDocumentDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Sastavlja popis svih dokumenata vezanih uz registracijski broj, za prikaz „Moji
 * registracijski brojevi". Spaja tri izvora u jedinstven popis:
 *
 * <ol>
 *   <li><b>Zahtjev</b> — pohranjen na {@code submission.pdf_content} (ako postoji);</li>
 *   <li><b>Obavijest o dodjeli</b> — renderira se na zahtjev iz ZUP predloška (potvrda o
 *       činjenici bez promjenjivih polja, pa je re-render vjerodostojan; urudžbene oznake
 *       dolaze iz {@link #dodjelaFiling} pa PDF nosi isti URBROJ kao verzija poslana eGOP-u);</li>
 *   <li><b>Akti životnog ciklusa</b> — {@code egop_pismeno} retci vezani uz RB, s već
 *       pohranjenim (vjerodostojnim) PDF-om.</li>
 * </ol>
 *
 * <p>Servis samo čita i sastavlja podatke; sam render i download rade endpointi na
 * {@code RnController}.
 */
@Service
public class RnDocumentsService {

    /** Pohranjeni dokument spreman za download — s čitljivim nazivom datoteke. */
    public record StoredDocument(String filename, byte[] pdf) {}

    private final RnRepository rnRepository;
    private final SubmissionRepository submissionRepository;
    private final EgopPismenoRepository egopPismenoRepository;

    public RnDocumentsService(RnRepository rnRepository,
                              SubmissionRepository submissionRepository,
                              EgopPismenoRepository egopPismenoRepository) {
        this.rnRepository = rnRepository;
        this.submissionRepository = submissionRepository;
        this.egopPismenoRepository = egopPismenoRepository;
    }

    @Transactional(readOnly = true)
    public List<RnDocumentDto> listForRn(String rn) {
        RnEntity entity = rnRepository.findById(rn)
                .orElseThrow(() -> new ResourceNotFoundException("rn not found: " + rn));

        List<RnDocumentDto> out = new ArrayList<>();

        SubmissionEntity submission = entity.getSubmissionId() != null
                ? submissionRepository.findById(entity.getSubmissionId()).orElse(null)
                : null;

        if (submission != null && submission.getPdfContent() != null) {
            LocalDate izdano = toLocalDate(submission.getFilingDate() != null
                    ? submission.getFilingDate() : submission.getCreatedAt());
            out.add(new RnDocumentDto(null, StrDocumentType.ZAHTJEV.slug(),
                    StrDocumentType.ZAHTJEV.vrstaPismenaNaziv(), StrDocumentType.ZAHTJEV.smjer().name(),
                    izdano, href(rn, StrDocumentType.ZAHTJEV.slug())));
        }

        // Obavijest o dodjeli — svaki izdani RB je ima; renderira se on-demand.
        out.add(new RnDocumentDto(null, StrDocumentType.DODJELA.slug(),
                StrDocumentType.DODJELA.vrstaPismenaNaziv(), StrDocumentType.DODJELA.smjer().name(),
                entity.getIssueDate(), href(rn, StrDocumentType.DODJELA.slug())));

        for (EgopPismenoEntity akt : egopPismenoRepository.findByRnOrderByCreatedAtAsc(rn)) {
            if (akt.getPdfContent() == null) {
                continue; // akt bez pohranjenog dokumenta se ne može ponuditi na preuzimanje
            }
            String slug = StrDocumentType.fromVrstaPismenaNaziv(akt.getVrstaPismenaNaziv())
                    .map(StrDocumentType::slug).orElse(null);
            out.add(new RnDocumentDto(akt.getId(), slug, akt.getVrstaPismenaNaziv(),
                    akt.getSmjer().name(), toLocalDate(akt.getCreatedAt()),
                    href(rn, "pohranjeno/" + akt.getId())));
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

    /** Pohranjeni PDF akta životnog ciklusa; 404 ako akt ne pripada RB-u ili nema dokumenta. */
    @Transactional(readOnly = true)
    public StoredDocument storedAktPdf(String rn, UUID aktId) {
        EgopPismenoEntity akt = egopPismenoRepository.findById(aktId)
                .filter(a -> rn.equals(a.getRn()))
                .orElseThrow(() -> new ResourceNotFoundException("document not found: " + aktId));
        if (akt.getPdfContent() == null) {
            throw new ResourceNotFoundException("document has no pdf: " + aktId);
        }
        return new StoredDocument(filename(akt, rn), akt.getPdfContent());
    }

    /**
     * Urudžbene oznake obavijesti o dodjeli — KLASA s predmeta, URBROJ s izlaznog pismena
     * registracije (isti broj koji je poslan eGOP-u). Kad urudžbiranje nije (uspješno) provedeno,
     * URBROJ ostaje prazan i akt se renderira samo s KLASOM.
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
        String urBroj = egopPismenoRepository.findBySubmissionIdAndVrstaPismenaNazivAndActRef(
                        submissionId, StrDocumentType.DODJELA.vrstaPismenaNaziv(),
                        EgopPismenoEntity.ACT_REF_REGISTRACIJA)
                .map(EgopPismenoEntity::getUrBroj).orElse(null);
        return new FilingReference(klasa, urBroj);
    }

    /** npr. {@code suspenzija-HR180000123456789001.pdf}; padne na {@code akt} ako vrsta nije poznata. */
    private static String filename(EgopPismenoEntity akt, String rn) {
        String slug = StrDocumentType.fromVrstaPismenaNaziv(akt.getVrstaPismenaNaziv())
                .map(StrDocumentType::slug).orElse("akt");
        return slug + "-" + rn + ".pdf";
    }

    private static String href(String rn, String segment) {
        return "/api/rn/" + rn + "/documents/" + segment;
    }

    private static LocalDate toLocalDate(Instant instant) {
        return instant == null ? null : instant.atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
