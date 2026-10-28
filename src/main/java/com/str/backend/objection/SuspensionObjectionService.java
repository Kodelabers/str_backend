package com.str.backend.objection;

import com.str.backend.common.Strings;
import com.str.backend.domain.RnStatus;
import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ConflictException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.registration.RnOwner;
import com.str.backend.rn.RegistrationNumberLogEntity;
import com.str.backend.rn.RegistrationNumberLogRepository;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Predaja prigovora na prijedlog suspenzije ili na suspenziju RB-a. Iznajmljivač predaje samo
 * dokument; zapis čeka službenika ({@link SuspensionObjectionAdminService}). Kao i kod rješenja o
 * kategorizaciji, predaja ne mijenja status RB-a, ne ide u eGOP i ne šalje email — a ni rok za
 * očitovanje ne zaustavlja: ako službenik ne odluči na vrijeme, RB po roku prelazi u suspenziju,
 * a prigovor ostaje otvoren.
 */
@Service
public class SuspensionObjectionService {

    private static final byte[] MAGIC_PDF = {0x25, 0x50, 0x44, 0x46};                                  // %PDF
    private static final byte[] MAGIC_OLE2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};                                               // .doc
    private static final byte[] MAGIC_ZIP = {0x50, 0x4B, 0x03, 0x04};                                   // .docx

    static final String CONTENT_TYPE_PDF = "application/pdf";
    static final String CONTENT_TYPE_DOC = "application/msword";
    static final String CONTENT_TYPE_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    /** Parcijalni unique index iz changeseta 145 — jedan neobrađeni prigovor po RB-u. */
    static final String SUBMITTED_OBJECTION_INDEX = "uq_suspension_objection_rn_submitted";
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    /** Statusi RB-a na koje se prigovara; izvan njih prigovor nema predmeta. */
    public static final Set<RnStatus> OBJECTION_ALLOWED_RN_STATUSES =
            EnumSet.of(RnStatus.SUSPENSION_PROPOSED, RnStatus.SUSPENDED);

    private final SuspensionObjectionRepository repository;
    private final RnRepository rnRepository;
    private final RegistrationNumberLogRepository rnLogRepository;
    private final LessorRepository lessorRepository;

    public SuspensionObjectionService(SuspensionObjectionRepository repository,
                                      RnRepository rnRepository,
                                      RegistrationNumberLogRepository rnLogRepository,
                                      LessorRepository lessorRepository) {
        this.repository = repository;
        this.rnRepository = rnRepository;
        this.rnLogRepository = rnLogRepository;
        this.lessorRepository = lessorRepository;
    }

    /**
     * Predaja prigovora uz RB vlasnika {@code owner} — NIAS po OIB-u, non-EU po {@code lessorId}.
     *
     * <p>Redoslijed provjera kao kod rješenja: prvo vlasništvo (404 i za nepostojeći i za tuđi RB,
     * da se ne otkriva postoji li), zatim smisao predaje (409), a tek onda datoteka (400).
     *
     * @param submittedBy tko je fizički predao — {@code NIAS:<oib osobe>} ili {@code LESSOR:<lessorId>}
     */
    @Transactional
    public SuspensionObjectionResponse submit(RnOwner owner, String submittedBy, SuspensionObjectionRequest req) {
        String rn = Strings.blankToNull(req.getRegistrationNumber());
        if (rn == null || !isOwned(rn, owner)) {
            throw new ResourceNotFoundException("error.rn.notFound");
        }
        RnEntity rnEntity = rnRepository.findById(rn)
                .orElseThrow(() -> new ResourceNotFoundException("error.rn.notFound"));

        RnStatus rnStatus = rnEntity.getStatus();
        if (!OBJECTION_ALLOWED_RN_STATUSES.contains(rnStatus)) {
            throw new ConflictException("error.objection.rnStatus", "OBJECTION_RN_STATUS");
        }
        if (repository.existsByRnAndStatus(rn, SuspensionObjectionStatus.SUBMITTED)) {
            throw new ConflictException("error.objection.alreadySubmitted", "OBJECTION_ALREADY_SUBMITTED");
        }

        MultipartFile file = req.getDatoteka();
        if (file == null || file.isEmpty()) {
            throw new BusinessException("error.objection.file.empty");
        }
        byte[] content = readBytes(file);
        String fileName = Strings.safeFileName(file.getOriginalFilename(), "prigovor");
        String contentType = detectContentType(content, fileName);

        // Akt koji se pobija: prijelaz u trenutni status (prijedlog ili suspenzija).
        UUID rnLogId = rnLogRepository.findFirstByRnAndToStatusOrderByOccurredAtDesc(rn, rnStatus.name())
                .map(RegistrationNumberLogEntity::getLogId)
                .orElse(null);

        SuspensionObjectionEntity entity = owner.oib() != null
                ? SuspensionObjectionEntity.createForOib(owner.oib(), rn, rnStatus, rnLogId, submittedBy,
                        fileName, contentType, content)
                : SuspensionObjectionEntity.createForLessor(owner.lessorId(), lessorEmail(owner.lessorId()), rn,
                        rnStatus, rnLogId, submittedBy, fileName, contentType, content);

        return SuspensionObjectionResponse.of(saveSubmitted(entity));
    }

    private boolean isOwned(String rn, RnOwner owner) {
        return owner.oib() != null
                ? rnRepository.isOwnedByOib(rn, owner.oib())
                : rnRepository.isOwnedByLessor(rn, owner.lessorId());
    }

    private String lessorEmail(UUID lessorId) {
        return lessorRepository.findById(lessorId).map(LessorEntity::getEmail).orElse(null);
    }

    /**
     * Provjera iznad ne zatvara utrku dviju istodobnih predaja (dvostruki klik, dva prozora) —
     * zatvara je unique index. Upis se zato odmah flusha, da sukob stigne kao kod po kojem
     * frontend zna da je prigovor već zaprimljen, a ne kao generički {@code DATA_CONFLICT}.
     */
    private SuspensionObjectionEntity saveSubmitted(SuspensionObjectionEntity entity) {
        try {
            return repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            if (isSubmittedObjectionConflict(e)) {
                throw new ConflictException("error.objection.alreadySubmitted", "OBJECTION_ALREADY_SUBMITTED");
            }
            throw e;
        }
    }

    static boolean isSubmittedObjectionConflict(DataIntegrityViolationException e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof org.hibernate.exception.ConstraintViolationException cve
                    && cve.getConstraintName() != null) {
                return cve.getConstraintName().toLowerCase(Locale.ROOT).contains(SUBMITTED_OBJECTION_INDEX);
            }
            if (c instanceof SQLException sql && SQLSTATE_UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                // Bez naziva constrainta: jedini drugi unique je PK, a on je nasumičan UUID.
                return true;
            }
        }
        return false;
    }

    /**
     * Tip se određuje iz sadržaja, ne iz {@code Content-Type} headera — header postavlja klijent i
     * može lagati. Dopušteni su PDF, DOC i DOCX, isto što frontend nudi u dropzoneu. Potpisi Worda
     * nisu jedinstveni (DOCX je ZIP arhiva, DOC je OLE2 kao i stari Excel), pa se uz potpis traži
     * i odgovarajuća ekstenzija — inače bi prošla bilo koja arhiva ili Office datoteka.
     */
    static String detectContentType(byte[] content, String fileName) {
        if (startsWith(content, MAGIC_PDF)) return CONTENT_TYPE_PDF;
        String name = fileName.toLowerCase(Locale.ROOT);
        if (startsWith(content, MAGIC_OLE2) && name.endsWith(".doc")) return CONTENT_TYPE_DOC;
        if (startsWith(content, MAGIC_ZIP) && name.endsWith(".docx")) return CONTENT_TYPE_DOCX;
        throw new BusinessException("error.objection.file.type");
    }

    private static boolean startsWith(byte[] content, byte[] magic) {
        return content.length >= magic.length
                && Arrays.equals(content, 0, magic.length, magic, 0, magic.length);
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("error.objection.file.unreadable");
        }
    }
}
