package com.str.backend.rn;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.attachment.PrilogException;
import com.str.backend.attachment.PrilogService;
import com.str.backend.attachment.PrilogVlasnik;
import com.str.backend.address.CountyNames;
import com.str.backend.common.SearchTokens;
import com.str.backend.common.Strings;
import com.str.backend.document.DocumentLabels;
import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.domain.RegistrationNumber;
import com.str.backend.domain.WithdrawalReason;
import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lookup.AccommodationTypeRepository;
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

    public RnService(RnRepository repository, RnStatusTransitionService transitionService,
                     AccommodationRepository accommodationRepository,
                     AccommodationTypeRepository accommodationTypeRepository, Clock clock,
                     DocumentLabels labels, PrilogService prilogService) {
        this.repository = repository;
        this.transitionService = transitionService;
        this.accommodationRepository = accommodationRepository;
        this.accommodationTypeRepository = accommodationTypeRepository;
        this.clock = clock;
        this.labels = labels;
        this.prilogService = prilogService;
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

        LocalDate today = LocalDate.now(clock);
        for (int i = 0; i < MAX_RN_ATTEMPTS; i++) {
            String candidate = RegistrationNumber.generate(countyCode, 0, typeCode).getValue();
            if (!repository.existsByRn(candidate)) {
                RnEntity rn = RnEntity.issue(candidate, submissionId, accommodationId, today);
                repository.save(rn);
                log.info("rn_issued rn={} submission={} accommodation={}", candidate, submissionId, accommodationId);
                return rn;
            }
        }
        throw new BusinessException("error.rn.generation.failed");
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
     * kod obustave, reaktivacije i povlačenja.
     *
     * <p>U {@code registration_number_log.reason} ide natpis razloga, a bilješka službenika samo uz
     * {@link RnTrigger#OTHER} (kao kod {@link #withdraw}): akt i obavijest razlog čitaju odande, pa bi
     * bilješka uz kodirani razlog zamijenila natpis koji MINTS traži.
     *
     * <p>Prilozi službenika ({@code prilogIds} iz staginga, T17) vežu se uz novi zapis
     * {@code registration_number_log} u istoj transakciji, pa ih AFTER_COMMIT listeneri (eGOP, e-pošta)
     * prilažu uz akt; neispravan prilog (tuđi, istekao, previše, ukupno preko 15 MB) poništava i prijedlog.
     */
    @Transactional
    public RnEntity suspend(String rn, RnTrigger trigger, LocalDate suspensionDeadline, String note, String actor,
                            Collection<Long> prilogIds) {
        if (!RnTrigger.SUSPENSION_REASONS.contains(trigger)) {
            throw new BusinessException("error.rn.suspend.trigger.invalid");
        }
        if (trigger == RnTrigger.OTHER && Strings.blankToNull(note) == null) {
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
        String logged = trigger == RnTrigger.OTHER ? Strings.blankToNull(note) : labels.trigger(trigger);
        RnEntity e = loadForUpdate(rn);
        UUID logId = transitionService.transition(e, RnStatus.SUSPENSION_PROPOSED, trigger, actor, logged);
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
        RnEntity e = loadForUpdate(rn);
        if (e.getStatus() != RnStatus.SUSPENSION_PROPOSED || e.getSuspensionDeadline() == null
                || !e.getSuspensionDeadline().isBefore(danas())) {
            return false;
        }
        transitionService.transition(e, RnStatus.SUSPENDED, RnTrigger.DEADLINE_EXCEEDED, actor,
                SuspensionDeadlineJob.DEADLINE_EXCEEDED_NOTE);
        return true;
    }

    @Transactional
    public RnEntity revokeProposal(String rn, String actor) {
        RnEntity e = loadForUpdate(rn);
        transitionService.transition(e, RnStatus.ACTIVE, RnTrigger.REVOKE_PROPOSAL, actor, null);
        return e;
    }

    @Transactional
    public RnEntity reactivate(String rn, String actor) {
        RnEntity e = loadForUpdate(rn);
        transitionService.transition(e, RnStatus.ACTIVE, RnTrigger.REACTIVATE, actor, null);
        return e;
    }

    /**
     * Officer-initiated withdrawal (povlačenje) of a registration number → WITHDRAWN.
     * Permanent: allowed from ACTIVE, SUSPENSION_PROPOSED and SUSPENDED, never reactivated
     * (čl. 6 STR Uredbe). The lessor's own revocation (opoziv) is a separate final status,
     * {@code REVOKED} — see {@code LessorRnActionService}.
     * STR-2.1-002.
     *
     * <p>Razlog je obvezan i bira se s popisa ({@link WithdrawalReason}, MINTS). Okidač prijelaza
     * ostaje {@link RnTrigger#WITHDRAWAL}, a razlog ide u {@code registration_number_log.reason}
     * kao tekst koji akt ispisuje: natpis razloga, a uz {@link WithdrawalReason#OTHER} opis
     * službenika (obvezan). Opis uz kodirani razlog se ne upisuje — kao ni bilješka uz kodirani
     * razlog suspenzije, frontend ga tada ni ne šalje.
     *
     * <p>Prilozi službenika (T17) vežu se uz novi zapis prijelaza kao kod {@link #suspend}.
     *
     * <p>TODO (zasebni epic, "Zajedničke backend teme"): obavijest Internetskim platformama
     * (mail/M2M); akt o povlačenju ide u KP kroz eGOP tok. Retencija (18 mj.) se računa iz {@code valid_to}
     * koji {@link RnEntity#applyStatus} postavlja na dan povlačenja.
     */
    @Transactional
    public RnEntity withdraw(String rn, WithdrawalReason reason, String note, String actor,
                             Collection<Long> prilogIds) {
        if (reason == null) {
            throw new BusinessException("error.rn.withdraw.reason.required");
        }
        String cleanNote = Strings.blankToNull(note);
        if (reason.requiresNote() && cleanNote == null) {
            throw new BusinessException("error.rn.withdraw.note.required");
        }
        String logged = reason.requiresNote() ? cleanNote : labels.withdrawalReason(reason);
        RnEntity e = loadForUpdate(rn);
        UUID logId = transitionService.transition(e, RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL, actor, logged);
        bindPrilozi(actor, prilogIds, logId);
        return e;
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

    /** Današnji dan za rokove suspenzije — po hrvatskom vremenu, kao {@link SuspensionDeadlineJob}. */
    private LocalDate danas() {
        return LocalDate.now(clock.withZone(SuspensionDeadlineJob.ZAGREB));
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
     * SUSPENSION_PROPOSED is deliberately absent — the proposal only opens the response deadline
     * and the RN keeps holding until it expires. That work list is {@code view=SUSPENSION_PROPOSED}
     * on the registry endpoint.
     *
     * @param scopeCounties opseg službenika ({@code accommodation.county}); {@code null} = cijela RH
     */
    @Transactional(readOnly = true)
    public List<RnEntity> inactive(List<String> scopeCounties) {
        List<RnStatus> statuses = List.of(RnStatus.SUSPENDED, RnStatus.REVOKED, RnStatus.WITHDRAWN);
        return scopeCounties == null
                ? repository.findByStatusInOrderByUpdatedAtDesc(statuses)
                : repository.findByStatusInAndCountyIn(statuses, scopeCounties);
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
        return repository.searchRegistry(filter.view().statuses(),
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
        return repository.findRegistryFacilityIds(filter.view().statuses(),
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
     * adresa iznajmljivača. Akti i mailovi i dalje idu kroz {@link #detail}.
     */
    @Transactional(readOnly = true)
    public RnDetailResponse detailView(String rn) {
        RnDetailDto detail = detail(rn);
        RnDetailExtras extras = repository.findDetailExtras(rn)
                .orElseThrow(() -> new ResourceNotFoundException("rn not found: " + rn));
        return RnDetailResponse.of(detail, extras);
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
