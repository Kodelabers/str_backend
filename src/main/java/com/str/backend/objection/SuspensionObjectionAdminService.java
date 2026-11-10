package com.str.backend.objection;

import com.str.backend.admin.AdminAuditService;
import com.str.backend.attachment.PrilogDto;
import com.str.backend.attachment.PrilogRepository.PrilogSazetak;
import com.str.backend.attachment.PrilogService;
import com.str.backend.attachment.VrstaVlasnika;
import com.str.backend.auth.internal.InternalAccessService;
import com.str.backend.common.Strings;
import com.str.backend.domain.RnStatus;
import com.str.backend.exception.ConflictException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnReissueGuard;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RnService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Obrada prigovora na detalju RB-a (službenik s rolom {@code RN_MANAGEMENT}): povijest, preuzimanje
 * dokumenta, uvažavanje i neuvažavanje. Audit kroz {@link AdminAuditService}, kao i ostale admin
 * akcije; akter je službenik iz sesije ({@code INTERNAL:<oib>}).
 *
 * <p>Uvažavanje radi postojeći prijelaz po trenutnom statusu RB-a — iz prijedloga suspenzije
 * {@link RnService#revokeProposal} (akt „obustava"), iz suspenzije {@link RnService#reactivate}, iz
 * prijedloga povlačenja {@link RnService#revokeWithdrawalProposal} (uvijek u {@code ACTIVE}), iz
 * povlačenja {@link RnService#reinstate} (T12) — pa nastaju isti akti i obavijesti
 * kao kad službenik to napravi ručno. Razlog se prijelazu
 * namjerno ne prosljeđuje: akt obustave ispisuje razlog pokretanja suspenzije, a ne razlog
 * obustave (v. {@code StrDocumentService#razlogIzTraga}). Veza s prigovorom stoji u prigovoru
 * ({@code resolved_by}, {@code resolution_note}) i u auditu.
 */
@Service
public class SuspensionObjectionAdminService {

    private final SuspensionObjectionRepository repository;
    private final RnRepository rnRepository;
    private final RnService rnService;
    private final AdminAuditService auditService;
    private final InternalAccessService internalAccess;
    private final LessorRepository lessorRepository;
    private final PrilogService prilogService;
    private final RnReissueGuard reissueGuard;

    public SuspensionObjectionAdminService(SuspensionObjectionRepository repository,
                                           RnRepository rnRepository,
                                           RnService rnService,
                                           AdminAuditService auditService,
                                           InternalAccessService internalAccess,
                                           LessorRepository lessorRepository,
                                           PrilogService prilogService,
                                           RnReissueGuard reissueGuard) {
        this.reissueGuard = reissueGuard;
        this.repository = repository;
        this.rnRepository = rnRepository;
        this.rnService = rnService;
        this.auditService = auditService;
        this.internalAccess = internalAccess;
        this.lessorRepository = lessorRepository;
        this.prilogService = prilogService;
    }

    /**
     * Povijest prigovora uz RB, najnoviji prvi. Nepostojeći RB je 404, RB bez prigovora prazan popis. Uz
     * povučen RB svaka stavka nosi {@code brojeviNaIstojAdresi} (T12, jedan upit za cijeli popis).
     */
    @Transactional(readOnly = true)
    public List<SuspensionObjectionDto> list(String rn) {
        RnEntity rnEntity = rnRepository.findById(rn)
                .orElseThrow(() -> new ResourceNotFoundException("error.rn.notFound"));
        List<SuspensionObjectionRepository.ObjectionSummary> rows = repository.findSummariesByRn(rn);
        // Jedan upit za imena svih službenika, ne po retku; isto za porezne brojeve.
        Map<String, String> names = internalAccess.displayNames(
                rows.stream().map(SuspensionObjectionRepository.ObjectionSummary::getResolvedBy).toList());
        Map<UUID, String> taxNumbers = taxNumbers(rows);
        // Dodatne datoteke svih prigovora jednim upitom (T17).
        Map<UUID, List<PrilogSazetak>> prilozi = prilogService.zaVlasnike(VrstaVlasnika.OBJECTION,
                rows.stream().map(SuspensionObjectionRepository.ObjectionSummary::getObjectionId).toList());
        List<String> naIstojAdresi = rnEntity.getStatus() == RnStatus.WITHDRAWN && !rows.isEmpty()
                ? List.copyOf(reissueGuard.brojeviNaIstojAdresi(rn))
                : List.of();
        return rows.stream()
                .map(o -> SuspensionObjectionDto.of(o, names.get(o.getResolvedBy()),
                        taxNumbers.get(o.getLessorId()),
                        prilozi.getOrDefault(o.getObjectionId(), List.of()).stream()
                                .map(p -> PrilogDto.of(rn, p))
                                .toList(),
                        naIstojAdresi))
                .toList();
    }

    @Transactional(readOnly = true)
    public SuspensionObjectionFileDto file(String rn, UUID id) {
        SuspensionObjectionEntity e = findOrThrow(rn, id);
        return new SuspensionObjectionFileDto(e.getFileName(), e.getContentType(), e.getFileContent());
    }

    /**
     * Uvažavanje: prigovor → {@code ACCEPTED}, RB → {@code ACTIVE} (i iz prijedloga povlačenja, bez obzira
     * na status prije njega). RB se zaključava prije provjere statusa, da se uvažavanje ne utrka s noćnim
     * prijelazom prijedlog → suspenzija odnosno povlačenje (o kojem ovisi koji se prijelaz radi) ni s
     * ručnom radnjom drugog službenika. Uvažavanje prigovora na povučen broj za čiji je objekt u
     * međuvremenu izdan novi broj je 409 {@code OBJECTION_RN_REISSUED} i ništa ne mijenja.
     */
    @Transactional
    public void accept(String rn, UUID id, String actor, String note) {
        RnEntity rnEntity = rnRepository.findForUpdate(rn)
                .orElseThrow(() -> new ResourceNotFoundException("error.rn.notFound"));
        SuspensionObjectionEntity e = findOrThrow(rn, id);
        RnStatus rnStatus = rnEntity.getStatus();
        if (!SuspensionObjectionService.OBJECTION_ALLOWED_RN_STATUSES.contains(rnStatus)) {
            throw new ConflictException("error.objection.rnStatus", "OBJECTION_RN_STATUS");
        }
        String cleanNote = Strings.blankToNull(note);
        // Prigovor prvi: zatvaranje neobrađenih prigovora pri prijelazu RB-a ovaj više ne dira.
        e.accept(actor, cleanNote);
        switch (rnStatus) {
            case SUSPENSION_PROPOSED -> rnService.revokeProposal(rn, actor);
            case SUSPENDED -> rnService.reactivate(rn, actor);
            case WITHDRAWAL_PROPOSED -> rnService.revokeWithdrawalProposal(rn, actor);
            case WITHDRAWN -> rnService.reinstate(rn, actor);
            default -> throw new ConflictException("error.objection.rnStatus", "OBJECTION_RN_STATUS");
        }
        auditService.record(actor, "OBJECTION_ACCEPT", "SUSPENSION_OBJECTION", id.toString(), cleanNote);
    }

    /**
     * Neuvažavanje: prigovor → {@code REJECTED}. Ako je RB i dalje pred suspenzijom, a rok je već
     * istekao, odmah se suspendira u ime službenika ({@link RnService#suspendIfDeadlineExpired}); isto
     * tako se broj predložen za povlačenje odmah povlači ({@link RnService#withdrawIfDeadlineExpired}):
     * prigovor predan u roku zadržao je {@code SuspensionDeadlineJob}, pa bi suspenzija inače
     * čekala noćni job. Dok rok teče, RB ostaje gdje jest. Prigovor je u tom trenutku već
     * {@code REJECTED}, a suspenzija ga ni ne zatvara ({@link SuspensionObjectionLifecycleListener}),
     * pa odluka službenika ostaje zapisana.
     *
     * <p>RB se zaključava prije svega, kao kod uvažavanja, da se ne utrka s noćnim jobom.
     */
    @Transactional
    public void reject(String rn, UUID id, String actor, String note) {
        RnEntity rnEntity = rnRepository.findForUpdate(rn)
                .orElseThrow(() -> new ResourceNotFoundException("error.rn.notFound"));
        SuspensionObjectionEntity e = findOrThrow(rn, id);
        String cleanNote = Strings.blankToNull(note);
        e.reject(actor, cleanNote);
        if (rnEntity.getStatus() == RnStatus.SUSPENSION_PROPOSED) {
            rnService.suspendIfDeadlineExpired(rn, actor);
        } else if (rnEntity.getStatus() == RnStatus.WITHDRAWAL_PROPOSED) {
            rnService.withdrawIfDeadlineExpired(rn, actor);
        }
        auditService.record(actor, "OBJECTION_REJECT", "SUSPENSION_OBJECTION", id.toString(), cleanNote);
    }

    private SuspensionObjectionEntity findOrThrow(String rn, UUID id) {
        return repository.findByObjectionIdAndRn(id, rn)
                .orElseThrow(() -> new ResourceNotFoundException("Prigovor nije pronađen: " + id));
    }

    /** Strani porezni broj non-EU iznajmljivača po {@code lessorId} — on nema OIB. */
    private Map<UUID, String> taxNumbers(List<SuspensionObjectionRepository.ObjectionSummary> rows) {
        Set<UUID> ids = rows.stream()
                .map(SuspensionObjectionRepository.ObjectionSummary::getLessorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        // Ne Map.of(): NIAS zapisi traže po null ključu, a nepromjenjiva mapa tada baca NPE.
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return lessorRepository.findAllById(ids).stream()
                .filter(l -> l.getTaxNumber() != null)
                .collect(Collectors.toMap(LessorEntity::getLessorId, LessorEntity::getTaxNumber));
    }
}
