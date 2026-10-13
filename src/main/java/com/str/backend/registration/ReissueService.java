package com.str.backend.registration;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.address.AddressIdResolver;
import com.str.backend.address.CountyNames;
import com.str.backend.categorization.CategorizationDecisionEntity;
import com.str.backend.categorization.CategorizationDecisionRepository;
import com.str.backend.categorization.CategorizationDecisionRepository.DecisionSummary;
import com.str.backend.categorization.CategorizationDecisionService;
import com.str.backend.domain.RnStatus;
import com.str.backend.exception.ConflictException;
import com.str.backend.exception.GoneException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.lessor.LessorRnSummaryDto;
import com.str.backend.lookup.AccommodationTypeEntity;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.registration.dto.ReissuePrefillResponse;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RnRepository.ReissueCandidateRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Ponovni zahtjev za RB <b>novog</b> objekta nakon povlačenja (opoziva) — T15.
 *
 * <p>Opoziv ne briše podatke objekta. Unutar {@code app.rn.retention-months} mjeseci (zadano 18,
 * isti prag kao {@code WithdrawnRnRetentionJob}) od dana povlačenja vlasnik smije za isti objekt
 * zatražiti novi RB: obrazac se predpopuni podacima povučenog RB-a ({@link #prefill}), korisnik ih
 * smije mijenjati, a zahtjev nosi {@code prethodniRb}. Nakon isteka roka zahtjev je običan novi
 * zahtjev, bez preuzimanja.
 *
 * <p>Objekt se identificira povučenim RB-om iz korisnikovih „Mojih registracijskih brojeva" — backend
 * je jedini autoritet. eTurizam objekte ovo ne pokriva: za njih popis objekata nakon povlačenja nudi
 * „Zatraži RB" s predpopunom iz eTurizma (PR #120).
 *
 * <p><b>Prihvatljivost</b> — jedna funkcija ({@link #eligibility}) za predpopunu, predaju i popis.
 * RB {@code X} smije se iskoristiti za vlasnika {@code V} kad vrijedi sve:
 * <ol>
 *   <li>X postoji i pripada V (NIAS po efektivnom OIB-u, non-EU po {@code lessorId}) — inače
 *       404 {@code REISSUE_NOT_FOUND}, isto za nepostojeći i tuđi, da se ne otkriva postojanje;</li>
 *   <li>X je {@code WITHDRAWN} — inače 409 {@code REISSUE_NOT_WITHDRAWN};</li>
 *   <li>ni smještaj X-a ni njegovo aktivno rješenje nemaju {@code facility_id} (objekt nije u
 *       eTurizmu, ni naknadno upisan) — inače 409 {@code REISSUE_NOT_NEW_FACILITY};</li>
 *   <li>dan povlačenja ({@code validTo}) je poznat — inače 409 {@code REISSUE_UNKNOWN_WITHDRAWAL_DATE}
 *       (legacy povlačenje bez datuma; rok se ne može utvrditi);</li>
 *   <li>{@code validTo + n mjeseci >= danas} — inače 410 {@code REISSUE_EXPIRED};</li>
 *   <li>X još nije iskorišten: ne postoji <b>nijedan</b> smještaj s {@code previous_rn = X}, bez
 *       obzira na stanje njegova RB-a — inače 409 {@code REISSUE_ALREADY_USED}. Kad je i novi RB
 *       kasnije povučen, mjerodavan je on (najnoviji u lancu), a X više ne. Utrku dviju istodobnih
 *       predaja zatvara parcijalni UNIQUE indeks {@value #PREVIOUS_RN_INDEX} (changeset 136).</li>
 * </ol>
 *
 * <p><b>Rješenje o kategorizaciji</b> prenosi se na novi RB samo kad je objekt isti
 * ({@link #isSameObject}); inače novi RB ostaje bez rješenja i korisnik ga predaje kao za novi objekt.
 *
 * <p>TODO (potvrditi s klijentom): pravila ne razlikuju opoziv iznajmljivača od povlačenja po
 * službenoj dužnosti ({@code RnService.withdraw}, bez aktera). Treba potvrditi smije li se ponovni
 * zahtjev s preuzetim podacima (i prenesenim rješenjem) podnijeti i nakon službenog povlačenja —
 * ako ne, razlikovati po {@code registration_number_log} (akter/okidač povlačenja).
 */
@Service
public class ReissueService {

    private static final Logger log = LoggerFactory.getLogger(ReissueService.class);

    /** Parcijalni UNIQUE indeks na {@code accommodation.previous_rn} (changeset 136). */
    public static final String PREVIOUS_RN_INDEX = "uq_accommodation_previous_rn";
    public static final String ALREADY_USED = "REISSUE_ALREADY_USED";
    public static final String NOT_FOUND = "REISSUE_NOT_FOUND";

    /** Ishod provjere prihvatljivosti; {@link #ALLOWED} je jedini koji dopušta ponovni zahtjev. */
    public enum Outcome {
        ALLOWED, NOT_WITHDRAWN, NOT_NEW_FACILITY, UNKNOWN_WITHDRAWAL_DATE, EXPIRED, ALREADY_USED
    }

    /** @param until zadnji dan roka; poznat čim je poznat dan povlačenja */
    public record Eligibility(Outcome outcome, LocalDate until) {
        public boolean allowed() {
            return outcome == Outcome.ALLOWED;
        }
    }

    private final RnRepository rnRepository;
    private final AccommodationRepository accommodationRepository;
    private final CategorizationDecisionRepository decisionRepository;
    private final AccommodationTypeRepository accommodationTypeRepository;
    private final SubmissionRepository submissionRepository;
    private final LessorRepository lessorRepository;
    private final AddressIdResolver addressIdResolver;
    private final Clock clock;
    private final int retentionMonths;

    public ReissueService(RnRepository rnRepository,
                          AccommodationRepository accommodationRepository,
                          CategorizationDecisionRepository decisionRepository,
                          AccommodationTypeRepository accommodationTypeRepository,
                          SubmissionRepository submissionRepository,
                          LessorRepository lessorRepository,
                          AddressIdResolver addressIdResolver,
                          Clock clock,
                          @Value("${app.rn.retention-months:18}") int retentionMonths) {
        this.rnRepository = rnRepository;
        this.accommodationRepository = accommodationRepository;
        this.decisionRepository = decisionRepository;
        this.accommodationTypeRepository = accommodationTypeRepository;
        this.submissionRepository = submissionRepository;
        this.lessorRepository = lessorRepository;
        this.addressIdResolver = addressIdResolver;
        this.clock = clock;
        this.retentionMonths = retentionMonths;
    }

    // ── pravila ─────────────────────────────────────────────────────────────

    /**
     * Pravilo prihvatljivosti (uvjeti 2–6 iz opisa razreda), bez pristupa bazi.
     *
     * @param inETurizam smještaj ili aktivno rješenje povučenog RB-a ima {@code facility_id}
     * @param used       postoji smještaj s {@code previous_rn} = ovaj RB
     */
    public static Eligibility eligibility(RnStatus status, LocalDate validTo, boolean inETurizam, boolean used,
                                          LocalDate today, int retentionMonths) {
        LocalDate until = validTo == null ? null : validTo.plusMonths(retentionMonths);
        Outcome outcome;
        if (status != RnStatus.WITHDRAWN) {
            outcome = Outcome.NOT_WITHDRAWN;
        } else if (inETurizam) {
            outcome = Outcome.NOT_NEW_FACILITY;
        } else if (until == null) {
            outcome = Outcome.UNKNOWN_WITHDRAWAL_DATE;
        } else if (until.isBefore(today)) {
            outcome = Outcome.EXPIRED;
        } else if (used) {
            outcome = Outcome.ALREADY_USED;
        } else {
            outcome = Outcome.ALLOWED;
        }
        return new Eligibility(outcome, until);
    }

    /**
     * Je li novi zahtjev za <b>isti</b> objekt, pa se rješenje o kategorizaciji smije prenijeti:
     * ista adresa (županija po {@link CountyNames#key}, općina/grad, naselje, ulica i kućni broj,
     * uspoređeni bez razlike u velikim slovima i bjelinama), ista vrsta, a broj kreveta nije veći od
     * starog. Inače rješenje opisuje drugi objekt i ne prenosi se.
     */
    public static boolean isSameObject(AccommodationEntity previous, AccommodationEntity current) {
        return Objects.equals(CountyNames.key(previous.getCounty()), CountyNames.key(current.getCounty()))
                && sameText(previous.getCity(), current.getCity())
                && sameText(previous.getSettlement(), current.getSettlement())
                && sameText(previous.getStreet(), current.getStreet())
                && sameText(previous.getStreetNumber(), current.getStreetNumber())
                && previous.getAccommodationTypeId() != null
                && previous.getAccommodationTypeId().equals(current.getAccommodationTypeId())
                && previous.getMaxBeds() != null
                && current.getMaxBeds() != null
                && current.getMaxBeds() <= previous.getMaxBeds();
    }

    // ── predpopuna ──────────────────────────────────────────────────────────

    /** Predpopuna obrasca iz povučenog RB-a; iznimke kao {@link #requireReusableForSubmission}. */
    @Transactional(readOnly = true)
    public ReissuePrefillResponse prefill(String rn, RnOwner owner) {
        requireOwned(rn, owner);
        RnEntity withdrawn = rnRepository.findById(rn).orElseThrow(ReissueService::notFound);
        AccommodationEntity accommodation = accommodationOf(withdrawn);
        Optional<DecisionSummary> decision = activeDecisionSummary(rn);
        LocalDate until = require(withdrawn, accommodation, decision);

        ReissuePrefillResponse.Rjesenje rjesenje = decision
                .map(d -> new ReissuePrefillResponse.Rjesenje(d.getDecisionId(), d.getFileName(), d.getStatus()))
                .orElse(null);
        return new ReissuePrefillResponse(withdrawn.getRn(), withdrawn.getValidTo(), until,
                objekt(withdrawn, accommodation), rjesenje);
    }

    // ── predaja ─────────────────────────────────────────────────────────────

    /**
     * Provjera ponovnog zahtjeva pri predaji, prije GO pipelinea. Utrku dviju istodobnih predaja ova
     * provjera ne zatvara — zatvara je {@value #PREVIOUS_RN_INDEX} pri upisu smještaja.
     */
    @Transactional
    public void requireReusableForSubmission(String rn, RnOwner owner) {
        requireOwned(rn, owner);
        RnEntity withdrawn = rnRepository.findById(rn).orElseThrow(ReissueService::notFound);
        require(withdrawn, accommodationOf(withdrawn), activeDecisionSummary(rn));
    }

    /**
     * Prenosi predano ili potvrđeno rješenje o kategorizaciji s povučenog RB-a na novi, u
     * transakciji izdavanja — ali samo kad je objekt isti ({@link #isSameObject}). Odbijeno se ne
     * prenosi. Izvorni zapis ostaje uz povučeni RB, a iz internog reda ispada (kopija ga zamjenjuje).
     *
     * @return {@code true} kad je kopija stvarno napravljena
     */
    @Transactional
    public boolean carryOverDecision(String previousRn, String newRn, AccommodationEntity current) {
        Optional<AccommodationEntity> previous = rnRepository.findById(previousRn)
                .flatMap(r -> accommodationRepository.findById(r.getAccommodationId()));
        if (previous.isEmpty() || !isSameObject(previous.get(), current)) {
            log.info("reissue_decision_not_carried previousRn={} rn={} razlog=objekt_promijenjen", previousRn, newRn);
            return false;
        }
        return decisionRepository.findFirstByRnAndStatusInOrderByUploadedAtDesc(
                        previousRn, CategorizationDecisionService.ACTIVE_DECISION_STATUSES)
                .map(source -> {
                    CategorizationDecisionEntity copy = decisionRepository.save(
                            CategorizationDecisionEntity.copyForReissue(source, newRn, Instant.now(clock)));
                    log.info("reissue_decision_carried previousRn={} rn={} source={} decision={} status={}",
                            previousRn, newRn, source.getDecisionId(), copy.getDecisionId(), copy.getStatus());
                    return true;
                })
                .orElse(false);
    }

    /** Je li prekršaj integriteta upravo {@value #PREVIOUS_RN_INDEX} — isti povučeni RB iskorišten dvaput. */
    public static boolean isPreviousRnConflict(DataIntegrityViolationException e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof org.hibernate.exception.ConstraintViolationException cve
                    && cve.getConstraintName() != null) {
                return cve.getConstraintName().toLowerCase(Locale.ROOT).contains(PREVIOUS_RN_INDEX);
            }
        }
        return false;
    }

    public static ConflictException alreadyUsed() {
        return new ConflictException("error.reissue.alreadyUsed", ALREADY_USED);
    }

    // ── popis ───────────────────────────────────────────────────────────────

    /**
     * Dopunjava popis „Mojih registracijskih brojeva" rokom ponovnog zahtjeva po {@link #eligibility}
     * (vlasništvo je popis već riješio). Jedan upit za sve povučene.
     */
    @Transactional(readOnly = true)
    public List<LessorRnSummaryDto> withReissueDeadlines(List<LessorRnSummaryDto> rows) {
        List<String> withdrawn = rows.stream()
                .filter(r -> r.status() == RnStatus.WITHDRAWN)
                .map(LessorRnSummaryDto::rn)
                .distinct()
                .toList();
        if (withdrawn.isEmpty()) {
            return rows;
        }
        LocalDate today = LocalDate.now(clock);
        Map<String, LocalDate> deadlines = new HashMap<>();
        for (ReissueCandidateRow row : rnRepository.findReissueCandidates(withdrawn)) {
            boolean inETurizam = !isBlank(row.getFacilityId()) || Boolean.TRUE.equals(row.getDecisionInETurizam());
            Eligibility e = eligibility(RnStatus.WITHDRAWN, row.getValidTo(), inETurizam,
                    Boolean.TRUE.equals(row.getUsed()), today, retentionMonths);
            if (e.allowed()) {
                deadlines.put(row.getRn(), e.until());
            }
        }
        return rows.stream()
                .map(r -> r.withPonovniZahtjevDo(deadlines.get(r.rn())))
                .toList();
    }

    // ── pomoćno ─────────────────────────────────────────────────────────────

    /** Primjenjuje {@link #eligibility} na učitan RB; vraća zadnji dan roka ili baca odgovarajuću iznimku. */
    private LocalDate require(RnEntity withdrawn, AccommodationEntity accommodation, Optional<DecisionSummary> decision) {
        boolean inETurizam = !isBlank(accommodation.getFacilityId())
                || decision.map(d -> !isBlank(d.getFacilityId())).orElse(false);
        Eligibility e = eligibility(withdrawn.getStatus(), withdrawn.getValidTo(), inETurizam,
                accommodationRepository.existsByPreviousRn(withdrawn.getRn()), LocalDate.now(clock), retentionMonths);
        return switch (e.outcome()) {
            case ALLOWED -> e.until();
            case NOT_WITHDRAWN -> throw new ConflictException("error.reissue.notWithdrawn", "REISSUE_NOT_WITHDRAWN");
            case NOT_NEW_FACILITY -> throw new ConflictException("error.reissue.existingFacility", "REISSUE_NOT_NEW_FACILITY");
            case UNKNOWN_WITHDRAWAL_DATE -> throw new ConflictException(
                    "error.reissue.unknownWithdrawalDate", "REISSUE_UNKNOWN_WITHDRAWAL_DATE");
            case EXPIRED -> throw new GoneException("error.reissue.expired", "REISSUE_EXPIRED");
            case ALREADY_USED -> throw alreadyUsed();
        };
    }

    /** Uvjet 1: isti 404 za nepostojeći i tuđi RB. */
    private void requireOwned(String rn, RnOwner owner) {
        boolean owned = !isBlank(rn) && (owner.oib() != null
                ? rnRepository.isOwnedByOib(rn, owner.oib())
                : rnRepository.isOwnedByLessor(rn, owner.lessorId()));
        if (!owned) {
            throw notFound();
        }
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("error.rn.notFound", NOT_FOUND);
    }

    private AccommodationEntity accommodationOf(RnEntity rn) {
        return accommodationRepository.findById(rn.getAccommodationId()).orElseThrow(ReissueService::notFound);
    }

    /** Aktivno rješenje bez sadržaja datoteke — za provjeru i predpopunu BLOB nije potreban. */
    private Optional<DecisionSummary> activeDecisionSummary(String rn) {
        return decisionRepository.findSummariesByRnAndStatusIn(rn, CategorizationDecisionService.ACTIVE_DECISION_STATUSES)
                .stream().findFirst();
    }

    private ReissuePrefillResponse.Objekt objekt(RnEntity withdrawn, AccommodationEntity a) {
        Optional<AccommodationTypeEntity> type = a.getAccommodationTypeId() == null ? Optional.empty()
                : accommodationTypeRepository.findById(a.getAccommodationTypeId());
        AddressIdResolver.AddressIds ids = addressIdResolver.resolve(
                a.getCounty(), a.getCity(), a.getSettlement(), a.getStreet(), a.getStreetNumber());
        // Kontakt iz snapshota povučenog zahtjeva — ali ne za tvrtku: ondje je to kontakt tadašnjeg
        // zastupnika, koji danas možda više ne zastupa (e-Zastupanja).
        Optional<LessorEntity> contact = Optional.ofNullable(withdrawn.getSubmissionId())
                .flatMap(submissionRepository::findById)
                .map(SubmissionEntity::getLessorId)
                .flatMap(lessorRepository::findById)
                .filter(l -> !l.isLegalEntityOwner());
        return new ReissuePrefillResponse.Objekt(
                a.getName(),
                a.getAccommodationTypeId(),
                type.map(AccommodationTypeEntity::getCode).orElse(null),
                type.map(AccommodationTypeEntity::getName).orElse(null),
                a.getMaxBeds(),
                floor(a.getFloor()),
                ids.zupanijaId(), a.getCounty(),
                ids.opcinaId(), a.getCity(),
                ids.naseljeId(), a.getSettlement(),
                ids.ulicaId(), a.getStreet(),
                ids.kucniBrojId(), a.getStreetNumber(),
                a.getPostalCode(),
                a.getCadastralMunicipality(),
                a.getCadastralParcelNumber(),
                a.getOfferType(),
                a.getOffering(),
                a.isBuilding(),
                a.isApartments(),
                a.isLegalized(),
                a.getLessorResidence(),
                a.getCoOwnerConsent(),
                a.getConsentDate(),
                a.getConsentWithdrawalDate(),
                a.getHost(),
                contact.map(LessorEntity::getEmail).orElse(null),
                contact.map(LessorEntity::getMobileNumber).orElse(null),
                contact.map(LessorEntity::getPhoneNumber).orElse(null),
                contact.map(LessorEntity::getContactName).orElse(null));
    }

    /** Kat se sprema kao tekst ({@code -9..99}); što nije cijeli broj obrazac ne može prikazati. */
    private static Integer floor(String floor) {
        if (isBlank(floor)) {
            return null;
        }
        try {
            return Integer.valueOf(floor.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean sameText(String a, String b) {
        return Objects.equals(normalize(a), normalize(b));
    }

    private static String normalize(String value) {
        return isBlank(value) ? null : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
