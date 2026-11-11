package com.str.backend.rn;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.attachment.PrilogException;
import com.str.backend.attachment.PrilogService;
import com.str.backend.attachment.PrilogVlasnik;
import com.str.backend.categorization.CategorizationDecisionRepository;
import com.str.backend.categorization.CategorizationDecisionStatus;
import com.str.backend.address.CountyNames;
import com.str.backend.common.SearchTokens;
import com.str.backend.common.Strings;
import com.str.backend.common.Zagreb;
import com.str.backend.document.DocumentLabels;
import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.domain.RegistrationNumber;
import com.str.backend.domain.WithdrawalReason;
import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ConflictException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.lookup.ObjectGroupPolicy;
import com.str.backend.objection.SuspensionObjectionEntity;
import com.str.backend.objection.SuspensionObjectionRepository;
import com.str.backend.objection.SuspensionObjectionStatus;
import com.str.backend.rn.dto.RnCategorizationInfo;
import com.str.backend.rn.dto.RnDetailDto;
import com.str.backend.rn.dto.RnDetailExtras;
import com.str.backend.rn.dto.RnDetailResponse;
import com.str.backend.rn.dto.RnSummaryDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RnService {

    private static final Logger log = LoggerFactory.getLogger(RnService.class);
    private static final int MAX_RN_ATTEMPTS = 5;
    /** Deset godina — dovoljno za svaki stvarni rok, a drži datum unutar raspona DB tipa `date`. */
    private static final int MAX_DEADLINE_WINDOW_DAYS = 3650;
    /** Najdulja bilješka službenika — širina {@code registration_number_log.reason}. */
    static final int MAX_NOTE_LENGTH = 1024;
    /**
     * Lista za {@code :verifiedIds} kad filtra „Verificiran" nema: uvjet je tada istinit bez
     * obzira na listu, pa vrijednost nije važna, ali Hibernate ne smije dobiti praznu kolekciju
     * za {@code IN}.
     */
    private static final Set<String> UNUSED_FACILITY_IDS = Set.of("");
    /** Lista za {@code IN :scopeCounties} kad opseg nije ograničen — mora biti neprazna, ne koristi se. */
    private static final List<String> UNUSED_SCOPE_COUNTIES = List.of("");

    // Maps str.county.name → EGOP organization ID used in RN generation
    private static final Map<String, Integer> COUNTY_EGOP_ORG_IDS = Map.ofEntries(
            Map.entry("Zagrebačka županija",                    2),
            Map.entry("Krapinsko-zagorska županija",            3),
            Map.entry("Sisačko-moslavačka županija",            4),
            Map.entry("Karlovačka županija",                    5),
            Map.entry("Varaždinska županija",                   6),
            Map.entry("Koprivničko-križevačka županija",        7),
            Map.entry("Bjelovarsko-bilogorska županija",        8),
            Map.entry("Primorsko-goranska županija",            9),
            Map.entry("Ličko-senjska županija",                10),
            Map.entry("Virovitičko-podravska županija",        11),
            Map.entry("Požeško-slavonska županija",            12),
            Map.entry("Brodsko-posavska županija",             13),
            Map.entry("Zadarska županija",                     14),
            Map.entry("Osječko-baranjska županija",            15),
            Map.entry("Šibensko-kninska županija",             16),
            Map.entry("Vukovarsko-srijemska županija",         17),
            Map.entry("Splitsko-dalmatinska županija",         18),
            Map.entry("Istarska županija",                     19),
            Map.entry("Dubrovačko-neretvanska županija",       20),
            Map.entry("Međimurska županija",                   21),
            Map.entry("Grad Zagreb",                           22)
    );

    /** Isti kodovi, po {@link CountyNames#key} — v. {@link #countyCode}. */
    private static final Map<String, Integer> COUNTY_CODES_BY_KEY = COUNTY_EGOP_ORG_IDS.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(e -> CountyNames.key(e.getKey()), Map.Entry::getValue));

    private final RnRepository repository;
    private final RnStatusTransitionService transitionService;
    private final AccommodationRepository accommodationRepository;
    private final AccommodationTypeRepository accommodationTypeRepository;
    private final Clock clock;
    private final DocumentLabels labels;
    private final PrilogService prilogService;
    private final RnReissueGuard reissueGuard;
    private final SuspensionObjectionRepository objectionRepository;
    private final ObjectGroupPolicy objectGroupPolicy;
    private final RegistrationNumberLogRepository logRepository;
    private final CategorizationDecisionRepository decisionRepository;

    public RnService(RnRepository repository, RnStatusTransitionService transitionService,
                     AccommodationRepository accommodationRepository,
                     AccommodationTypeRepository accommodationTypeRepository, Clock clock,
                     DocumentLabels labels, PrilogService prilogService, RnReissueGuard reissueGuard,
                     SuspensionObjectionRepository objectionRepository, ObjectGroupPolicy objectGroupPolicy,
                     RegistrationNumberLogRepository logRepository,
                     CategorizationDecisionRepository decisionRepository) {
        this.logRepository = logRepository;
        this.decisionRepository = decisionRepository;
        this.reissueGuard = reissueGuard;
        this.objectionRepository = objectionRepository;
        this.repository = repository;
        this.transitionService = transitionService;
        this.accommodationRepository = accommodationRepository;
        this.accommodationTypeRepository = accommodationTypeRepository;
        this.clock = clock;
        this.labels = labels;
        this.prilogService = prilogService;
        this.objectGroupPolicy = objectGroupPolicy;
    }

    @Transactional
    public RnEntity issue(UUID submissionId, UUID accommodationId) {
        // STR spec §2.1: Hotel/kamp (registrationNumberAllowed = false) must not receive RN.
        AccommodationEntity accommodation = accommodationRepository.findById(accommodationId)
                .orElseThrow(() -> new ResourceNotFoundException("accommodation not found: " + accommodationId));
        if (accommodation.getAccommodationTypeId() != null) {
            accommodationTypeRepository.findById(accommodation.getAccommodationTypeId()).ifPresent(type -> {
                if (!type.isRegistrationNumberAllowed()) {
                    throw new BusinessException("error.rn.type.not.allowed");
                }
            });
        }
        int countyCode = countyCode(accommodation);
        int typeCode = accommodation.getAccommodationTypeId() != null
                ? accommodation.getAccommodationTypeId().intValue() : 0;
        // Oznaka skupine iz str_rn.object_group (T13); vrsta bez skupine daje 0, kao svaki RB prije.
        int groupCode = objectGroupPolicy.rbGroupCode(accommodation.getAccommodationTypeId());

        LocalDate today = LocalDate.now(clock);
        for (int i = 0; i < MAX_RN_ATTEMPTS; i++) {
            String candidate = RegistrationNumber.generate(countyCode, groupCode, typeCode).getValue();
            if (!repository.existsByRn(candidate)) {
                RnEntity rn = RnEntity.issue(candidate, submissionId, accommodationId, today);
                repository.save(rn);
                log.info("rn_issued rn={} submission={} accommodation={}", candidate, submissionId, accommodationId);
                zatvoriPrigovoreZamijenjenihBrojeva(accommodation, candidate);
                return rn;
            }
        }
        throw new BusinessException("error.rn.generation.failed");
    }

    /** Akter zatvaranja prigovora kad je za objekt izdan novi broj — kao u {@code SuspensionObjectionLifecycleListener}. */
    private static final String SYSTEM_ACTOR = "SYSTEM";

    /**
     * Novi broj za isti objekt (isti {@code facility_id} ili ponovni zahtjev s {@code previous_rn}) zatvara
     * otvoreni prigovor na povučeni broj (T12): uvažiti se više ne bi mogao ({@link RnReissueGuard}).
     * Ista transakcija kao izdavanje. Stari broj se zaključava prije prigovora — isti redoslijed kao
     * uvažavanje ({@code SuspensionObjectionAdminService#accept}), pa nema ni zastarjelog čitanja ni
     * međusobnog čekanja.
     */
    private void zatvoriPrigovoreZamijenjenihBrojeva(AccommodationEntity accommodation, String noviRn) {
        Set<String> stari = new LinkedHashSet<>();
        if (accommodation.getPreviousRn() != null) {
            stari.add(accommodation.getPreviousRn());
        }
        String facilityId = Strings.blankToNull(accommodation.getFacilityId());
        if (facilityId != null) {
            stari.addAll(repository.findRnsOfFacilityInStatus(facilityId, RnStatus.WITHDRAWN));
        }
        stari.remove(noviRn);
        for (String stariRn : stari) {
            RnEntity stariBroj = repository.findForUpdate(stariRn).orElse(null);
            if (stariBroj == null || stariBroj.getStatus() != RnStatus.WITHDRAWN) {
                continue;
            }
            for (SuspensionObjectionEntity prigovor
                    : objectionRepository.findByRnAndStatus(stariRn, SuspensionObjectionStatus.SUBMITTED)) {
                prigovor.close(SYSTEM_ACTOR, "Izdan je novi registracijski broj " + noviRn + " za isti objekt.");
                log.info("suspension_objection_closed_new_rn rn={} objection={} newRn={}",
                        stariRn, prigovor.getObjectionId(), noviRn);
            }
        }
    }

    /**
     * Prva faza suspenzije (čl. 30. st. 2 ZUP-a): stranka se poziva na očitovanje, RB i dalje
     * vrijedi, a suspendira ga tek {@link SuspensionDeadlineJob} po isteku roka.
     *
     * <p>Rok za očitovanje je obvezan (MINTS): službenik ga sam određuje, a sustav ga ne
     * izmišlja. Bez roka kolona bi ostala {@code NULL}, job predmet nikad ne bi pokupio i RB bi
     * zauvijek visio u {@code SUSPENSION_PROPOSED}.
     *
     * <p>{@code actor} je službenik ({@code INTERNAL:<oib>}) — zapisuje se u revizijski trag, kao i
     * kod obustave, reaktivacije i prijedloga povlačenja.
     *
     * <p>U {@code registration_number_log.reason} ide natpis razloga, a bilješka službenika samo uz
     * {@link RnTrigger#OTHER} (kao kod {@link #proposeWithdrawal}): akt i obavijest razlog čitaju odande,
     * pa bi bilješka uz kodirani razlog zamijenila natpis koji MINTS traži. Bilješka ima najviše
     * {@value #MAX_NOTE_LENGTH} znakova (stupac {@code reason}).
     *
     * <p>Prilozi službenika ({@code prilogIds} iz staginga, T17) vežu se uz novi zapis
     * {@code registration_number_log} u istoj transakciji, pa ih AFTER_COMMIT listeneri (eGOP, e-pošta)
     * prilažu uz akt; neispravan prilog (tuđi, istekao, previše, ukupno preko 15 MB) poništava i prijedlog.
     */
    @Transactional
    public RnEntity suspend(String rn, RnTrigger trigger, LocalDate suspensionDeadline, String note, String actor,
                            Collection<Long> prilogIds) {
        return suspend(rn, trigger, suspensionDeadline, note, actor, prilogIds, null);
    }

    /** @param rucni ručni akt županijskog službenika (T16); {@code null} = akt kroz eGOP */
    @Transactional
    public RnEntity suspend(String rn, RnTrigger trigger, LocalDate suspensionDeadline, String note, String actor,
                            Collection<Long> prilogIds, RucniAkt rucni) {
        if (!RnTrigger.SUSPENSION_REASONS.contains(trigger)) {
            throw new BusinessException("error.rn.suspend.trigger.invalid");
        }
        String cleanNote = trigger == RnTrigger.OTHER ? biljeska(note) : null;
        if (trigger == RnTrigger.OTHER && cleanNote == null) {
            throw new BusinessException("error.rn.suspend.note.required");
        }
        if (suspensionDeadline == null) {
            throw new BusinessException("error.rn.suspend.deadline.required");
        }
        // Rok u prošlosti nije rok: SuspensionDeadlineJob bi ga pokupio već iduće noći i RB bi bio
        // suspendiran a da se stranka nije imala kad očitovati — a upravo joj to čl. 30. st. 2
        // ZUP-a jamči. Tipfeler u godini ne smije proći kao valjan poziv na očitovanje.
        if (suspensionDeadline.isBefore(danas())) {
            throw new BusinessException("error.rn.suspend.deadline.past");
        }
        String logged = trigger == RnTrigger.OTHER ? cleanNote : labels.trigger(trigger);
        RnEntity e = loadForUpdate(rn);
        UUID logId = transitionService.transition(e, RnStatus.SUSPENSION_PROPOSED, trigger, actor, logged, rucni);
        e.setSuspensionDeadline(suspensionDeadline);
        bindPrilozi(actor, prilogIds, logId);
        return e;
    }

    /**
     * Suspenzija istekom roka, odmah, u ime službenika — kad službenik ne uvaži prigovor predan u
     * roku. Takav prigovor zadržava {@link SuspensionDeadlineJob} (o njemu prvo odlučuje
     * službenik), pa bi bez ovoga RB nakon neuvažavanja čekao noćni job, a akt o suspenziji bi
     * kasnio dan.
     *
     * <p>Isti prijelaz i isti uvjet kao u jobu ({@code DEADLINE_EXCEEDED}, rok strogo prije
     * danas), pa nastaje isti akt. RB koji više nije pred suspenzijom ili mu rok još teče ostaje
     * kakav jest.
     *
     * @return je li RB suspendiran
     */
    @Transactional
    public boolean suspendIfDeadlineExpired(String rn, String actor) {
        return expireIfDeadlinePassed(ProposalProcedure.SUSPENSION, rn, actor);
    }

    @Transactional
    public RnEntity revokeProposal(String rn, String actor) {
        return revokeProposal(rn, actor, null);
    }

    @Transactional
    public RnEntity revokeProposal(String rn, String actor, RucniAkt rucni) {
        RnEntity e = loadForUpdate(rn);
        transitionService.transition(e, RnStatus.ACTIVE, RnTrigger.REVOKE_PROPOSAL, actor, null, rucni);
        return e;
    }

    @Transactional
    public RnEntity reactivate(String rn, String actor) {
        return reactivate(rn, actor, null);
    }

    @Transactional
    public RnEntity reactivate(String rn, String actor, RucniAkt rucni) {
        RnEntity e = loadForUpdate(rn);
        transitionService.transition(e, RnStatus.ACTIVE, RnTrigger.REACTIVATE, actor, null, rucni);
        return e;
    }

    /**
     * Prva faza povlačenja (T12, po uzoru na {@link #suspend}): stranka se poziva na očitovanje, a broj
     * se povlači tek istekom roka — noćnim poslom ({@link WithdrawalDeadlineJob}) ili kad službenik nakon
     * roka ne uvaži očitovanje ({@link #withdrawIfDeadlineExpired}). Ručnog povlačenja nema, kao ni ručne
     * suspenzije. Dopušteno iz {@code ACTIVE}, {@code SUSPENSION_PROPOSED} i {@code SUSPENDED}; postupak
     * suspenzije koji je bio u tijeku time završava (rok suspenzije se briše). Prijedlog iz
     * {@code SUSPENDED} javno ostaje nevaljan ({@code withdrawal_from_suspended}, {@link RnEntity#isValidForPublicUse}).
     *
     * <p>Razlog je obvezan i bira se s popisa ({@link WithdrawalReason}, MINTS). U
     * {@code registration_number_log.reason} ide natpis razloga, a uz {@link WithdrawalReason#OTHER}
     * opis službenika (obvezan, najviše {@value #MAX_NOTE_LENGTH} znakova). Odatle ga čitaju akti
     * prijedloga, obustave i povlačenja.
     *
     * <p>Rok za očitovanje je obvezan i ne smije biti u prošlosti (po hrvatskom vremenu), iz istih
     * razloga kao kod suspenzije. Prilozi službenika (T17) vežu se uz zapis prijelaza kao kod
     * {@link #suspend}.
     */
    @Transactional
    public RnEntity proposeWithdrawal(String rn, WithdrawalReason reason, LocalDate withdrawalDeadline,
                                      String note, String actor, Collection<Long> prilogIds) {
        return proposeWithdrawal(rn, reason, withdrawalDeadline, note, actor, prilogIds, null);
    }

    /** @param rucni ručni akt županijskog službenika (T16); {@code null} = akt kroz eGOP */
    @Transactional
    public RnEntity proposeWithdrawal(String rn, WithdrawalReason reason, LocalDate withdrawalDeadline,
                                      String note, String actor, Collection<Long> prilogIds, RucniAkt rucni) {
        if (reason == null) {
            throw new BusinessException("error.rn.withdraw.reason.required");
        }
        String cleanNote = reason.requiresNote() ? biljeska(note) : null;
        if (reason.requiresNote() && cleanNote == null) {
            throw new BusinessException("error.rn.withdraw.note.required");
        }
        if (withdrawalDeadline == null) {
            throw new BusinessException("error.rn.withdraw.deadline.required");
        }
        if (withdrawalDeadline.isBefore(danas())) {
            throw new BusinessException("error.rn.withdraw.deadline.past");
        }
        String logged = reason.requiresNote() ? cleanNote : labels.withdrawalReason(reason);
        RnEntity e = loadForUpdate(rn);
        boolean izSuspenzije = e.getStatus() == RnStatus.SUSPENDED;
        UUID logId = transitionService.transition(e, RnStatus.WITHDRAWAL_PROPOSED, RnTrigger.WITHDRAWAL_PROPOSAL,
                actor, logged, rucni);
        e.setWithdrawalDeadline(withdrawalDeadline);
        if (izSuspenzije) {
            // Suspendiran broj javno ostaje nevaljan i dok traje postupak povlačenja.
            e.markWithdrawalFromSuspended();
        }
        bindPrilozi(actor, prilogIds, logId);
        return e;
    }

    /**
     * Obustava postupka povlačenja — ručna ili uvaženim očitovanjem: broj se uvijek vraća u
     * {@code ACTIVE} (odluka korisnika, T12), bez obzira na to iz kojeg je statusa povlačenje predloženo.
     */
    @Transactional
    public RnEntity revokeWithdrawalProposal(String rn, String actor) {
        return revokeWithdrawalProposal(rn, actor, null);
    }

    @Transactional
    public RnEntity revokeWithdrawalProposal(String rn, String actor, RucniAkt rucni) {
        RnEntity e = loadForUpdate(rn);
        if (e.getStatus() != RnStatus.WITHDRAWAL_PROPOSED) {
            throw new ConflictException("error.rn.withdraw.proposal.notPending", "RN_NOT_WITHDRAWAL_PROPOSED");
        }
        transitionService.transition(e, RnStatus.ACTIVE, RnTrigger.REVOKE_WITHDRAWAL_PROPOSAL, actor, null, rucni);
        return e;
    }

    /**
     * Povlačenje istekom roka, odmah, u ime službenika — kad službenik ne uvaži prigovor (očitovanje)
     * predan u roku. Takav prigovor zadržava {@link WithdrawalDeadlineJob}, pa bi bez ovoga broj nakon
     * neuvažavanja čekao noćni posao. Isti prijelaz i isti uvjet kao u poslu
     * ({@code WITHDRAWAL_DEADLINE_EXCEEDED}, rok strogo prije danas), pa nastaje isti akt; razlog je
     * uvijek onaj s prijedloga. Broj koji više nije predložen za povlačenje ili mu rok još teče ostaje
     * kakav jest.
     *
     * @return je li broj povučen
     */
    @Transactional
    public boolean withdrawIfDeadlineExpired(String rn, String actor) {
        return expireIfDeadlinePassed(ProposalProcedure.WITHDRAWAL, rn, actor);
    }

    /** Zajednički uvjet i prijelaz istekom roka za {@link #suspendIfDeadlineExpired} i {@link #withdrawIfDeadlineExpired}. */
    private boolean expireIfDeadlinePassed(ProposalProcedure procedure, String rn, String actor) {
        RnEntity e = loadForUpdate(rn);
        if (!procedure.isExpired(e, danas())) {
            return false;
        }
        // T16: ručni prijedlog daje ručni akt (KLASA i izdavatelj s prijedloga, URBROJ + 1), kao u noćnom poslu.
        RucniAkt rucni = RucniAkt.izPrijedloga(logRepository, rn, procedure.proposed()).orElse(null);
        transitionService.transition(e, procedure.target(), procedure.trigger(), actor, procedure.note(), rucni);
        return true;
    }

    /**
     * Uvaženi prigovor na povučen broj (T12): {@code WITHDRAWN} → {@code ACTIVE}, bez roka za
     * prigovor. {@link RnEntity#applyStatus} briše {@code valid_to}, a {@code RnWithdrawnListener} broj
     * nakon commita ponovno upisuje u eTurizam.
     *
     * <p>Odbija se s 409 {@code OBJECTION_RN_REISSUED} kad za isti objekt već postoji drugi važeći
     * broj ({@link RnReissueGuard}). Dva važeća broja za isti objekt ne smiju postojati.
     */
    @Transactional
    public RnEntity reinstate(String rn, String actor) {
        return reinstate(rn, actor, null);
    }

    @Transactional
    public RnEntity reinstate(String rn, String actor, RucniAkt rucni) {
        RnEntity e = loadForUpdate(rn);
        if (e.getStatus() == RnStatus.WITHDRAWN && reissueGuard.noviBrojIzdan(e)) {
            throw new ConflictException("error.objection.rnReissued", "OBJECTION_RN_REISSUED");
        }
        transitionService.transition(e, RnStatus.ACTIVE, RnTrigger.REINSTATE, actor, null, rucni);
        return e;
    }

    /**
     * Bilješka službenika uz prijedlog (suspenzije ili povlačenja), očišćena; {@code null} kad je prazna.
     * Ide u {@code registration_number_log.reason}, pa preduga je 400, a ne 500 iz baze.
     */
    private static String biljeska(String note) {
        String clean = Strings.blankToNull(note);
        if (clean != null && clean.length() > MAX_NOTE_LENGTH) {
            throw new BusinessException("error.rn.note.tooLong");
        }
        return clean;
    }

    /**
     * Prilozi službenika uz upravo nastali prijelaz. Bez priloga ne radi ništa — ni ne traži
     * službenika, pa radnja bez priloga radi i bez aktera (testovi bez sigurnosnog lanca). Prilozi
     * bez identificiranog službenika su 403 {@code ATTACHMENT_FORBIDDEN}: nevezani prilog pripada
     * učitavatelju, a njega ovdje nema — i radnja se poništava.
     */
    private void bindPrilozi(String actor, Collection<Long> prilogIds, UUID logId) {
        if (prilogIds == null || prilogIds.isEmpty()) {
            return;
        }
        if (actor == null) {
            throw new PrilogException(HttpStatus.FORBIDDEN, "error.attachment.forbidden", PrilogException.FORBIDDEN);
        }
        prilogService.bind(actor, prilogIds, PrilogVlasnik.prijelaz(logId));
    }

    /** Današnji dan za rokove suspenzije i povlačenja — po hrvatskom vremenu, kao noćni poslovi. */
    private LocalDate danas() {
        return Zagreb.today(clock);
    }

    @Transactional(readOnly = true)
    public RnEntity find(String rn) {
        return load(rn);
    }

    @Transactional(readOnly = true)
    public List<RnEntity> forAccommodation(UUID accommodationId) {
        return repository.findByAccommodationId(accommodationId);
    }

    /**
     * STR-1.5: list of inactive RNs (SUSPENDED + REVOKED + WITHDRAWN), for display to competent authority.
     * SUSPENSION_PROPOSED and WITHDRAWAL_PROPOSED are deliberately absent — a proposal only opens the
     * response deadline and the RN keeps holding until it expires. Their work lists are
     * {@code view=SUSPENSION_PROPOSED} and {@code view=WITHDRAWAL_PROPOSED} on the registry endpoint.
     * Exception (T12): a withdrawal proposal raised from SUSPENDED stays publicly suspended, so it is listed
     * ({@link RnEntity#isValidForPublicUse}).
     *
     * @param scopeCounties opseg službenika ({@code accommodation.county}); {@code null} = cijela RH
     */
    @Transactional(readOnly = true)
    public List<RnEntity> inactive(List<String> scopeCounties) {
        List<RnStatus> statuses = List.of(RnStatus.SUSPENDED, RnStatus.WITHDRAWAL_PROPOSED, RnStatus.REVOKED,
                RnStatus.WITHDRAWN);
        List<RnEntity> rows = scopeCounties == null
                ? repository.findByStatusInOrderByUpdatedAtDesc(statuses)
                : repository.findByStatusInAndCountyIn(statuses, scopeCounties);
        return rows.stream().filter(e -> !e.isValidForPublicUse()).toList();
    }

    /**
     * STR wireframe §12 / §13: public registry of active or invalid RNs with filters + paging.
     *
     * <p>{@code verifiedFacilityIds} je skup verificiranih eTurizam objekata za filtar
     * „Verificiran" ({@link RnRegistrySearch}), nikad null; zanemaruje se kad je
     * {@code filter.verified()} null.
     * Stupac {@code facilityVerified} ovdje ostaje prazan — dopunjuje ga {@link RnRegistrySearch}.
     */
    @Transactional(readOnly = true)
    public Page<RnSummaryDto> searchRegistry(RnRegistryFilter filter, Set<String> verifiedFacilityIds,
                                             Pageable pageable) {
        if (Boolean.TRUE.equals(filter.verified()) && verifiedFacilityIds.isEmpty()) {
            return Page.empty(pageable);
        }
        String[] t = SearchTokens.slots(filter.q());
        String verifiedMode = verifiedMode(filter.verified(), verifiedFacilityIds);
        return repository.searchRegistry(filter.view().statuses(), filter.view().validity(),
                t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7], t[8], t[9],
                Strings.blankToNull(filter.county()),
                filter.scopeCounties() == null ? null : "ON",
                filter.scopeCounties() == null ? UNUSED_SCOPE_COUNTIES : filter.scopeCounties(),
                Strings.blankToNull(filter.municipality()),
                filter.typeId(),
                filter.foreignOnly() ? "true" : null,
                Strings.blankToNull(filter.rb()), Strings.blankToNull(filter.city()),
                Strings.blankToNull(filter.street()), Strings.blankToNull(filter.name()),
                Strings.blankToNull(filter.lessor()),
                deadlineBefore(filter.deadlineWithinDays()),
                filter.decision() == null ? null : filter.decision().name(),
                verifiedMode,
                verifiedMode == null ? UNUSED_FACILITY_IDS : verifiedFacilityIds,
                pageable);
    }

    /**
     * STR wireframe §12 / §13: export of registry rows matching filters. Vraća najviše
     * {@code maxRows + 1} redaka, da pozivatelj prepozna prekoračenje bez brojanja.
     */
    @Transactional(readOnly = true)
    public List<RnSummaryDto> searchRegistryForExport(RnRegistryFilter filter, Set<String> verifiedFacilityIds,
                                                      int maxRows) {
        return searchRegistry(filter, verifiedFacilityIds, PageRequest.of(0, maxRows + 1)).getContent();
    }

    /**
     * eTurizam objekti RB-ova koje registar s ovim filtrima prikazuje, bez filtra „Verificiran" —
     * njihovu verifikaciju {@link RnRegistrySearch} pita eTurizam.
     */
    @Transactional(readOnly = true)
    public List<String> registryFacilityIds(RnRegistryFilter filter) {
        String[] t = SearchTokens.slots(filter.q());
        return repository.findRegistryFacilityIds(filter.view().statuses(), filter.view().validity(),
                t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7], t[8], t[9],
                Strings.blankToNull(filter.county()),
                filter.scopeCounties() == null ? null : "ON",
                filter.scopeCounties() == null ? UNUSED_SCOPE_COUNTIES : filter.scopeCounties(),
                Strings.blankToNull(filter.municipality()),
                filter.typeId(),
                filter.foreignOnly() ? "true" : null,
                Strings.blankToNull(filter.rb()), Strings.blankToNull(filter.city()),
                Strings.blankToNull(filter.street()), Strings.blankToNull(filter.name()),
                Strings.blankToNull(filter.lessor()),
                deadlineBefore(filter.deadlineWithinDays()),
                filter.decision() == null ? null : filter.decision().name());
    }

    /**
     * Način filtra „Verificiran" za {@link RnRepository#RN_VERIFIED_FILTER}. Bez ijednog
     * verificiranog objekta „Ne" ne odsijeca ništa, pa filtra ni nema (prazan {@code IN ()} se ne
     * šalje); „Da" je tada prazan rezultat i ne dolazi do ovdje.
     */
    private static String verifiedMode(Boolean verified, Set<String> verifiedFacilityIds) {
        if (verified == null || verifiedFacilityIds.isEmpty()) {
            return null;
        }
        return verified ? "IN" : "NOT_IN";
    }

    /**
     * Gornja granica roka za očitovanje — „ističe u narednih {@code x} dana". Datum se računa
     * ovdje, a ne u upitu, da rezultat ne ovisi o vremenskoj zoni baze; {@code Clock} je isti
     * koji koristi i izdavanje RB-a, pa je test deterministički.
     *
     * <p>{@code x = 0} znači „rok je istekao ili ističe danas", a negativan broj pomiče granicu
     * u prošlost („istekao prije barem x dana") — oboje su smisleni upiti i prolaze kakvi jesu.
     *
     * <p>Vrijednost se ograničava na {@link #MAX_DEADLINE_WINDOW_DAYS}: {@code Integer.MAX_VALUE}
     * dana daje godinu 5881637, što premašuje raspon Postgresovog tipa {@code date} (do 5874897)
     * i sruši upit s 500. H2 na testu to proguta, pa se rub ne bi vidio prije produkcije. Kapa
     * je i inače bezopasna — prozor od 10 godina obuhvaća svaki postojeći rok.
     */
    private LocalDate deadlineBefore(Integer deadlineWithinDays) {
        if (deadlineWithinDays == null) {
            return null;
        }
        int days = Math.clamp(deadlineWithinDays.longValue(),
                -MAX_DEADLINE_WINDOW_DAYS, MAX_DEADLINE_WINDOW_DAYS);
        return danas().plusDays(days);
    }

    /** STR wireframe §12 / §13: full detail for a single RN (joined accommodation + lessor). */
    @Transactional(readOnly = true)
    public RnDetailDto detail(String rn) {
        return repository.findDetail(rn)
                .orElseThrow(() -> new ResourceNotFoundException("rn not found: " + rn));
    }

    /**
     * Detalj RB-a za prikaz (/registar/{RB}): {@link #detail} plus ponuda, namjena, kontakt i
     * adresa iznajmljivača. Akti i mailovi i dalje idu kroz {@link #detail}. Rješenje o kategorizaciji
     * (T19) dobiva samo službenik — iznajmljivač ga vidi na svojim popisima, a interni id i stanje upisa
     * u TuRegistar nisu za njega.
     */
    @Transactional(readOnly = true)
    public RnDetailResponse detailView(String rn, boolean sluzbenik) {
        RnDetailDto detail = detail(rn);
        RnDetailExtras extras = repository.findDetailExtras(rn)
                .orElseThrow(() -> new ResourceNotFoundException("rn not found: " + rn));
        return RnDetailResponse.of(detail, extras, sluzbenik ? categorization(rn) : null);
    }

    /**
     * Mjerodavno rješenje o kategorizaciji (T19), istim redom kao registar: aktivno (prihvaćeno ili
     * predano — najviše jedno po RB-u, changeset 126), a inače zadnje odbijeno.
     */
    private RnCategorizationInfo categorization(String rn) {
        return decisionRepository.findFirstByRnAndStatusInOrderByUploadedAtDesc(rn,
                        List.of(CategorizationDecisionStatus.VERIFIED, CategorizationDecisionStatus.SUBMITTED))
                .or(() -> decisionRepository.findFirstByRnAndStatusInOrderByUploadedAtDesc(rn,
                        List.of(CategorizationDecisionStatus.REJECTED)))
                .map(RnCategorizationInfo::of)
                .orElse(null);
    }

    /**
     * Kod županije za RB. Traži se po {@link CountyNames#key}, ne po točnom nazivu: lokalni seed
     * piše „Splitsko-dalmatinska", registar na dev/CDU „Splitsko-dalmatinska županija", a naziv
     * dopunjen iz eTurizma može biti i velikim slovima — svi moraju dati isti kod.
     * Bez županije (postojeći objekt kojem je ne zna ni eTurizam) kod je 0.
     */
    private static int countyCode(AccommodationEntity accommodation) {
        String key = CountyNames.key(accommodation.getCounty());
        return key == null ? 0 : COUNTY_CODES_BY_KEY.getOrDefault(key, 0);
    }

    private RnEntity load(String rn) {
        return repository.findById(rn)
                .orElseThrow(() -> new ResourceNotFoundException("rn not found: " + rn));
    }

    /** Za promjenu statusa — redak ostaje zaključan do commita, v. {@link RnRepository#findForUpdate}. */
    private RnEntity loadForUpdate(String rn) {
        return repository.findForUpdate(rn)
                .orElseThrow(() -> new ResourceNotFoundException("rn not found: " + rn));
    }
}
