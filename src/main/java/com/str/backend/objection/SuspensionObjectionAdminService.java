package com.str.backend.objection;

import com.str.backend.admin.AdminAuditService;
import com.str.backend.attachment.PrilogDto;
import com.str.backend.attachment.PrilogRepository.PrilogSazetak;
import com.str.backend.attachment.PrilogService;
import com.str.backend.attachment.VrstaVlasnika;
import com.str.backend.auth.internal.InternalAccessService;
import com.str.backend.common.Strings;
import com.str.backend.common.Zagreb;
import com.str.backend.document.StrDocumentType;
import com.str.backend.domain.RnStatus;
import com.str.backend.egop.EgopPismenoEntity;
import com.str.backend.egop.EgopPismenoRepository;
import com.str.backend.exception.ConflictException;
import com.str.backend.exception.ForbiddenException;
import com.str.backend.exception.IllegalStatusTransitionException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.RegistrationNumberLogEntity;
import com.str.backend.rn.RegistrationNumberLogRepository;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnReissueGuard;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RnService;
import com.str.backend.rn.RucniAkt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.HashMap;
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
 *
 * <p><b>Županijski postupak</b> (T16): kad je akt na koji podnesak odgovara izdao županijski službenik s
 * ručnom KLASOM i URBROJEM ({@link RegistrationNumberLogRepository#findRucniAkt}), podnesak ne ide u eGOP
 * ({@code PodnesakFilingListener}). Takav postupak vodi <b>samo županijski službenik</b>: drugi (ministarstvo,
 * neodređen opseg) ne smije ni urudžbiti ni odlučiti (403 {@code OBJECTION_NOT_COUNTY_OFFICIAL}). Županijski
 * službenik podnesak urudžbira sam ({@link #register}) i tek nakon toga smije odlučiti o njemu (409
 * {@code OBJECTION_NOT_REGISTERED}). Zasebnog statusa prigovora za to nema: „urudžbiran" znači da uz prigovor
 * postoji {@code egop_pismeno} s {@code act_ref} = {@code PRIGOVOR:<objection_id>} i URBROJ-em. U postupku
 * MINTS-a odlučuje se odmah, kao i dosad (i županijski službenik, s ručnim aktom iz T16).
 */
@Service
public class SuspensionObjectionAdminService {

    private static final Logger log = LoggerFactory.getLogger(SuspensionObjectionAdminService.class);

    private final SuspensionObjectionRepository repository;
    private final RnRepository rnRepository;
    private final RnService rnService;
    private final AdminAuditService auditService;
    private final InternalAccessService internalAccess;
    private final LessorRepository lessorRepository;
    private final PrilogService prilogService;
    private final RnReissueGuard reissueGuard;
    private final RegistrationNumberLogRepository logRepository;
    private final EgopPismenoRepository pismenoRepository;
    private final SubmissionRepository submissionRepository;

    public SuspensionObjectionAdminService(SuspensionObjectionRepository repository,
                                           RnRepository rnRepository,
                                           RnService rnService,
                                           AdminAuditService auditService,
                                           InternalAccessService internalAccess,
                                           LessorRepository lessorRepository,
                                           PrilogService prilogService,
                                           RnReissueGuard reissueGuard,
                                           RegistrationNumberLogRepository logRepository,
                                           EgopPismenoRepository pismenoRepository,
                                           SubmissionRepository submissionRepository) {
        this.reissueGuard = reissueGuard;
        this.logRepository = logRepository;
        this.pismenoRepository = pismenoRepository;
        this.submissionRepository = submissionRepository;
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
     * povučen RB svaka stavka nosi {@code brojeviNaIstojAdresi} (T12, jedan upit za cijeli popis), a svaka
     * {@code zupanijskiPostupak} i {@code urudzbiranje} (po jedan upit za cijeli popis).
     */
    @Transactional(readOnly = true)
    public List<SuspensionObjectionDto> list(String rn) {
        RnEntity rnEntity = rnRepository.findById(rn)
                .orElseThrow(() -> new ResourceNotFoundException("error.rn.notFound"));
        return dtos(rn, rnEntity, repository.findSummariesByRn(rn));
    }

    private List<SuspensionObjectionDto> dtos(String rn, RnEntity rnEntity,
                                              List<SuspensionObjectionRepository.ObjectionSummary> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        // Jedan upit za imena svih službenika, ne po retku; isto za porezne brojeve.
        Map<String, String> names = internalAccess.displayNames(
                rows.stream().map(SuspensionObjectionRepository.ObjectionSummary::getResolvedBy).toList());
        Map<UUID, String> taxNumbers = taxNumbers(rows);
        // Dodatne datoteke svih prigovora jednim upitom (T17).
        Map<UUID, List<PrilogSazetak>> prilozi = prilogService.zaVlasnike(VrstaVlasnika.OBJECTION,
                rows.stream().map(SuspensionObjectionRepository.ObjectionSummary::getObjectionId).toList());
        List<String> naIstojAdresi = rnEntity.getStatus() == RnStatus.WITHDRAWN
                ? List.copyOf(reissueGuard.brojeviNaIstojAdresi(rn))
                : List.of();
        Set<UUID> zupanijski = zupanijskiPostupci(rows);
        Map<UUID, SuspensionObjectionDto.Urudzbiranje> urudzbiranja = urudzbiranja(rn, rnEntity);
        return rows.stream()
                .map(o -> SuspensionObjectionDto.of(o, names.get(o.getResolvedBy()),
                        taxNumbers.get(o.getLessorId()),
                        prilozi.getOrDefault(o.getObjectionId(), List.of()).stream()
                                .map(p -> PrilogDto.of(rn, p))
                                .toList(),
                        naIstojAdresi,
                        zupanijski.contains(o.getObjectionId()),
                        urudzbiranja.get(o.getObjectionId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public SuspensionObjectionFileDto file(String rn, UUID id) {
        SuspensionObjectionEntity e = findOrThrow(rn, id);
        return new SuspensionObjectionFileDto(e.getFileName(), e.getContentType(), e.getFileContent());
    }

    /**
     * Urudžbiranje podneska u županijskom postupku (T16): službenik upisuje KLASU i URBROJ, a podnesak postaje
     * ulazno pismeno u STR-u ({@link EgopPismenoEntity#rucniPodnesak}), bez eGOP-a, KP-a i e-pošte. Vrsta
     * pismena ovisi o statusu broja pri predaji ({@link StrDocumentType#podnesakNaAkt}); dokument je datoteka
     * koju je stranka učitala, a ostale njezine datoteke su prilozi. Pismeno se vidi u „Dokumentima" RB-a.
     *
     * <p>Urudžbiti se mora svako ulazno pismeno, pa i podnesak prigovora koji je u međuvremenu zatvoren
     * ({@code CLOSED}, RB je otišao dalje drugim putem). Odlučen prigovor ({@code ACCEPTED}, {@code REJECTED})
     * se ne urudžbira — u županijskom postupku o njemu se nije moglo odlučiti bez urudžbe.
     *
     * <p>RB se zaključava kao kod uvažavanja. Redom: prigovor {@code SUBMITTED} ili {@code CLOSED} (inače 409),
     * uz {@code SUBMITTED} RB u statusu na koji se prigovara (409 {@code OBJECTION_RN_STATUS}), postupak
     * županijski (409 {@code OBJECTION_NOT_COUNTY_PROCEDURE} — u postupku MINTS-a urudžbira eGOP), a za prigovor
     * još nema pismena (409 {@code OBJECTION_ALREADY_REGISTERED}).
     *
     * @param rucni KLASA i URBROJ županijskog službenika ({@code RucniAktService#zaRadnju}, već provjereni);
     *              službenika koji nije županijski kontroler odbija prije (403)
     * @return prigovor s urudžbenim oznakama
     */
    @Transactional
    public SuspensionObjectionDto register(String rn, UUID id, String actor, RucniAkt rucni) {
        Objects.requireNonNull(rucni, "rucni");
        RnEntity rnEntity = rnRepository.findForUpdate(rn)
                .orElseThrow(() -> new ResourceNotFoundException("error.rn.notFound"));
        SuspensionObjectionEntity e = findOrThrow(rn, id);
        if (e.getStatus() != SuspensionObjectionStatus.SUBMITTED && e.getStatus() != SuspensionObjectionStatus.CLOSED) {
            throw new IllegalStatusTransitionException(
                    "Prigovor " + id + " je odlučen (" + e.getStatus() + ") — urudžbira se samo SUBMITTED ili CLOSED");
        }
        if (e.getStatus() == SuspensionObjectionStatus.SUBMITTED
                && !SuspensionObjectionService.OBJECTION_ALLOWED_RN_STATUSES.contains(rnEntity.getStatus())) {
            throw new ConflictException("error.objection.rnStatus", "OBJECTION_RN_STATUS");
        }
        if (!zupanijskiPostupak(e)) {
            throw new ConflictException("error.objection.notCountyProcedure", "OBJECTION_NOT_COUNTY_PROCEDURE");
        }
        // Bilo koji zapis za ovaj act_ref, i bez URBROJ-a: drugi upis pao bi na unique ključu pismena.
        if (pismenoRepository.existsByRnAndActRef(rn, EgopPismenoEntity.actRefPodneska(id))) {
            throw new ConflictException("error.objection.alreadyRegistered", "OBJECTION_ALREADY_REGISTERED");
        }
        if (rnEntity.getSubmissionId() == null) {
            // egop_pismeno.submission_id je obavezan (spis se vodi uz zahtjev); svaki izdani RB ga ima.
            throw new ConflictException("error.objection.rnWithoutSubmission", "OBJECTION_RN_WITHOUT_SUBMISSION");
        }
        StrDocumentType vrsta = StrDocumentType.podnesakNaAkt(e.getRnStatus())
                .orElseThrow(() -> new IllegalStateException("Prigovor " + id + " predan uz status "
                        + e.getRnStatus() + " nema vrstu pismena"));
        EgopPismenoEntity pismeno = pismenoRepository.save(EgopPismenoEntity.rucniPodnesak(
                rnEntity.getSubmissionId(), rn, id, vrsta.vrstaPismenaNaziv(), rucni.klasa(), rucni.urBroj()));
        auditService.record(actor, "OBJECTION_REGISTER", "SUSPENSION_OBJECTION", id.toString(),
                "KLASA: " + rucni.klasa() + ", URBROJ: " + rucni.urBroj() + ", pismeno: " + pismeno.getId());
        log.info("objection_registered objection={} rn={} pismeno={} vrsta='{}' klasa={} urbroj={} zu_rb={}",
                id, rn, pismeno.getId(), vrsta.vrstaPismenaNaziv(), rucni.klasa(), rucni.urBroj(),
                rucni.izdavateljZuRb());
        return dtos(rn, rnEntity, repository.findSummariesByRn(rn)).stream()
                .filter(d -> d.objectionId().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Prigovor " + id + " nije u povijesti RB-a " + rn));
    }

    /**
     * Uvažavanje: prigovor → {@code ACCEPTED}, RB → {@code ACTIVE} (i iz prijedloga povlačenja, bez obzira
     * na status prije njega). RB se zaključava prije provjere statusa, da se uvažavanje ne utrka s noćnim
     * prijelazom prijedlog → suspenzija odnosno povlačenje (o kojem ovisi koji se prijelaz radi) ni s
     * ručnom radnjom drugog službenika. Uvažavanje prigovora na povučen broj za čiji je objekt u
     * međuvremenu izdan novi broj je 409 {@code OBJECTION_RN_REISSUED} i ništa ne mijenja.
     *
     * <p>U županijskom postupku: službenik koji nije županijski 403 {@code OBJECTION_NOT_COUNTY_OFFICIAL}, a
     * neurudžbiran podnesak 409 {@code OBJECTION_NOT_REGISTERED}.
     *
     * @param rucni ručni akt županijskog službenika (T16, {@code RucniAktService#zaRadnju}); {@code null} = akt
     *              kroz eGOP, tj. službenik nije županijski
     */
    @Transactional
    public void accept(String rn, UUID id, String actor, String note, RucniAkt rucni) {
        RnEntity rnEntity = rnRepository.findForUpdate(rn)
                .orElseThrow(() -> new ResourceNotFoundException("error.rn.notFound"));
        SuspensionObjectionEntity e = findOrThrow(rn, id);
        RnStatus rnStatus = rnEntity.getStatus();
        if (!SuspensionObjectionService.OBJECTION_ALLOWED_RN_STATUSES.contains(rnStatus)) {
            throw new ConflictException("error.objection.rnStatus", "OBJECTION_RN_STATUS");
        }
        requireCountyRulesForDecision(rn, e, rucni != null);
        String cleanNote = Strings.blankToNull(note);
        // Prigovor prvi: zatvaranje neobrađenih prigovora pri prijelazu RB-a ovaj više ne dira.
        e.accept(actor, cleanNote);
        switch (rnStatus) {
            case SUSPENSION_PROPOSED -> rnService.revokeProposal(rn, actor, rucni);
            case SUSPENDED -> rnService.reactivate(rn, actor, rucni);
            case WITHDRAWAL_PROPOSED -> rnService.revokeWithdrawalProposal(rn, actor, rucni);
            case WITHDRAWN -> rnService.reinstate(rn, actor, rucni);
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
     * <p>RB se zaključava prije svega, kao kod uvažavanja, da se ne utrka s noćnim jobom. Pravila
     * županijskog postupka kao kod uvažavanja (403, pa 409).
     *
     * @param zupanijskiSluzbenik službenik je županijski ({@code RucniAktService#zupanijskiSluzbenik})
     */
    @Transactional
    public void reject(String rn, UUID id, String actor, String note, boolean zupanijskiSluzbenik) {
        RnEntity rnEntity = rnRepository.findForUpdate(rn)
                .orElseThrow(() -> new ResourceNotFoundException("error.rn.notFound"));
        SuspensionObjectionEntity e = findOrThrow(rn, id);
        requireCountyRulesForDecision(rn, e, zupanijskiSluzbenik);
        String cleanNote = Strings.blankToNull(note);
        e.reject(actor, cleanNote);
        if (rnEntity.getStatus() == RnStatus.SUSPENSION_PROPOSED) {
            rnService.suspendIfDeadlineExpired(rn, actor);
        } else if (rnEntity.getStatus() == RnStatus.WITHDRAWAL_PROPOSED) {
            rnService.withdrawIfDeadlineExpired(rn, actor);
        }
        auditService.record(actor, "OBJECTION_REJECT", "SUSPENSION_OBJECTION", id.toString(), cleanNote);
    }

    /**
     * Županijski postupak: odlučuje samo županijski službenik (403 {@code OBJECTION_NOT_COUNTY_OFFICIAL}), i to
     * tek kad je podnesak urudžbiran (409 {@code OBJECTION_NOT_REGISTERED}; samo za neobrađen prigovor —
     * ponovnu odluku o obrađenom odbija entitet, 409 bez koda). Postupak MINTS-a ne dira.
     */
    private void requireCountyRulesForDecision(String rn, SuspensionObjectionEntity e, boolean zupanijskiSluzbenik) {
        if (!zupanijskiPostupak(e)) {
            return;
        }
        if (!zupanijskiSluzbenik) {
            throw new ForbiddenException("error.objection.notCountyOfficial", "OBJECTION_NOT_COUNTY_OFFICIAL");
        }
        if (e.getStatus() == SuspensionObjectionStatus.SUBMITTED
                && pismenoRepository.findFirstByRnAndActRefAndUrBrojIsNotNull(rn,
                        EgopPismenoEntity.actRefPodneska(e.getObjectionId())).isEmpty()) {
            throw new ConflictException("error.objection.notRegistered", "OBJECTION_NOT_REGISTERED");
        }
    }

    /** Postupak vodi županija: akt na koji podnesak odgovara ima ručnu KLASU i URBROJ. */
    private boolean zupanijskiPostupak(SuspensionObjectionEntity e) {
        return logRepository.findRucniAkt(e.getRnLogId()).isPresent();
    }

    /**
     * Prigovori u županijskom postupku, jednim upitom za zapise prijelaza svih prigovora — isto pravilo kao
     * {@link #zupanijskiPostupak} ({@link RegistrationNumberLogEntity#rucniAkt}).
     */
    private Set<UUID> zupanijskiPostupci(List<SuspensionObjectionRepository.ObjectionSummary> rows) {
        Set<UUID> logIds = rows.stream()
                .map(SuspensionObjectionRepository.ObjectionSummary::getRnLogId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (logIds.isEmpty()) {
            return Set.of();
        }
        Set<UUID> rucni = logRepository.findAllById(logIds).stream()
                .filter(l -> l.rucniAkt().isPresent())
                .map(RegistrationNumberLogEntity::getLogId)
                .collect(Collectors.toSet());
        return rows.stream()
                .filter(o -> o.getRnLogId() != null && rucni.contains(o.getRnLogId()))
                .map(SuspensionObjectionRepository.ObjectionSummary::getObjectionId)
                .collect(Collectors.toSet());
    }

    /**
     * Urudžbene oznake podnesaka RB-a po prigovoru, jednim upitom. Ručno urudžbiran podnesak nosi svoju KLASU;
     * eGOP-ov (MINTS) ide u predmet akata RB-a, pa je KLASA sa submissiona ({@code egop_akti_klasa}). Datum je
     * dan zapisa pismena: za ručni dan urudžbiranja, za eGOP-ov dan predaje — trenutak dodjele URBROJ-a se ne
     * pamti, a slijedi odmah nakon predaje (ili s prvim uspjelim pokušajem retry joba).
     */
    private Map<UUID, SuspensionObjectionDto.Urudzbiranje> urudzbiranja(String rn, RnEntity rnEntity) {
        List<EgopPismenoEntity> podnesci = pismenoRepository.findUrudzbiraniPodnesci(rn);
        if (podnesci.isEmpty()) {
            return Map.of();
        }
        boolean trebaKlasuPredmeta = podnesci.stream().anyMatch(p -> p.getKlasa() == null)
                && rnEntity.getSubmissionId() != null;
        String klasaPredmeta = trebaKlasuPredmeta
                ? submissionRepository.findById(rnEntity.getSubmissionId())
                        .map(SubmissionEntity::getEgopAktiKlasa).orElse(null)
                : null;
        Map<UUID, SuspensionObjectionDto.Urudzbiranje> out = new HashMap<>();
        for (EgopPismenoEntity p : podnesci) {
            p.objectionId().ifPresent(objectionId -> out.putIfAbsent(objectionId,
                    new SuspensionObjectionDto.Urudzbiranje(p.getKlasa() != null ? p.getKlasa() : klasaPredmeta,
                            p.getUrBroj(), p.getCreatedAt().atZone(Zagreb.ZONE).toLocalDate())));
        }
        return out;
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
