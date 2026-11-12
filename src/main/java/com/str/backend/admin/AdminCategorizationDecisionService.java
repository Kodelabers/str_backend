package com.str.backend.admin;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.auth.internal.InternalAccessService;
import com.str.backend.categorization.CategorizationDecisionAdminDto;
import com.str.backend.categorization.CategorizationDecisionEntity;
import com.str.backend.categorization.CategorizationDecisionRepository;
import com.str.backend.categorization.CategorizationDecisionStatus;
import com.str.backend.categorization.CategorizationFileDto;
import com.str.backend.domain.RnStatus;
import com.str.backend.exception.ConflictException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.auth.internal.CountyScope;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import com.str.backend.str.StrFacilityRepository;
import com.str.backend.str.StrFacilityRepository.RegisteredFacilityRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Interni pregled skeniranih rješenja o kategorizaciji za nadležno tijelo. Radi isključivo nad
 * {@code str_rn.categorization_decision} (naša tablica) — pregled, download skena, te prihvat
 * (verify) i odbijanje (reject). Audit se piše kroz {@link AdminAuditService}, kao i za ostale
 * admin akcije.
 *
 * <p>Obrazac je namjerno isti kao {@link AdminPendingRegistrationService}: akter je službenik
 * iz NIAS sesije ({@code INTERNAL:<oib>}, v. {@code InternalAccessService#actorFor}), a u
 * sučelju se prikazuje njegovo ime ({@code InternalAccessService#displayNames}).
 */
@Service
public class AdminCategorizationDecisionService {

    /** Konačni statusi RB-a: uz njih se upis objekta u TuRegistar više ne potvrđuje (T19). */
    private static final Set<RnStatus> FINAL_RN_STATUSES = Set.of(RnStatus.REVOKED, RnStatus.WITHDRAWN);

    private final CategorizationDecisionRepository repository;
    private final AdminAuditService auditService;
    private final InternalAccessService internalAccess;
    private final LessorRepository lessorRepository;
    private final RnRepository rnRepository;
    private final StrFacilityRepository facilityRepository;
    private final AccommodationRepository accommodationRepository;

    public AdminCategorizationDecisionService(CategorizationDecisionRepository repository,
                                              AdminAuditService auditService,
                                              InternalAccessService internalAccess,
                                              LessorRepository lessorRepository,
                                              RnRepository rnRepository,
                                              StrFacilityRepository facilityRepository,
                                              AccommodationRepository accommodationRepository) {
        this.repository = repository;
        this.auditService = auditService;
        this.internalAccess = internalAccess;
        this.lessorRepository = lessorRepository;
        this.rnRepository = rnRepository;
        this.facilityRepository = facilityRepository;
        this.accommodationRepository = accommodationRepository;
    }

    /**
     * Interni red. Zapis koji je pri ponovnom izdavanju RB-a prenesen na novi RB (T15) ovdje se ne
     * prikazuje — obrađuje se kopija uz novi RB, inače bi isti sken bio u redu dvaput.
     *
     * @param scopeCounties opseg službenika ({@code accommodation.county} RB-a); {@code null} = cijela RH
     */
    @Transactional(readOnly = true)
    public Page<CategorizationDecisionAdminDto> list(CategorizationDecisionStatus status, List<String> scopeCounties,
                                                     Pageable pageable) {
        Page<CategorizationDecisionEntity> page;
        if (scopeCounties == null) {
            page = status != null
                    ? repository.findForReviewByStatus(status, pageable)
                    : repository.findForReview(pageable);
        } else {
            page = status != null
                    ? repository.findForReviewByStatusInCounties(status, scopeCounties, pageable)
                    : repository.findForReviewInCounties(scopeCounties, pageable);
        }
        // Jedan upit za imena svih službenika na stranici, ne po retku; isto za porezne brojeve.
        Map<String, String> names = internalAccess.displayNames(page.getContent().stream()
                .flatMap(e -> Stream.of(e.getVerifiedBy(), e.getRegisteredBy()))
                .filter(Objects::nonNull)
                .toList());
        Map<UUID, String> taxNumbers = taxNumbers(page.getContent());
        Map<String, RnStatus> rnStatuses = rnStatuses(page.getContent());
        return page.map(e -> CategorizationDecisionAdminDto.of(e, name(names, e.getVerifiedBy()),
                name(names, e.getRegisteredBy()), taxNumbers.get(e.getLessorId()),
                registrationConfirmable(e, rnStatuses.get(e.getRn()))));
    }

    @Transactional(readOnly = true)
    public CategorizationDecisionAdminDto detail(UUID id, CountyScope scope) {
        CategorizationDecisionEntity e = findOrThrow(id, scope);
        Map<String, String> names = internalAccess.displayNames(
                Stream.of(e.getVerifiedBy(), e.getRegisteredBy()).filter(Objects::nonNull).toList());
        return CategorizationDecisionAdminDto.of(e, name(names, e.getVerifiedBy()), name(names, e.getRegisteredBy()),
                taxNumbers(List.of(e)).get(e.getLessorId()),
                registrationConfirmable(e, rnStatuses(List.of(e)).get(e.getRn())));
    }

    /** Status RB-a po broju, jednim upitom za cijelu stranicu; stari samostalni uploadi RB nemaju. */
    private Map<String, RnStatus> rnStatuses(List<CategorizationDecisionEntity> decisions) {
        Set<String> rns = decisions.stream()
                .map(CategorizationDecisionEntity::getRn)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (rns.isEmpty()) {
            return Collections.emptyMap();
        }
        return rnRepository.findAllById(rns).stream()
                .collect(Collectors.toMap(RnEntity::getRn, RnEntity::getStatus));
    }

    /**
     * Nudi li se „Potvrda upisa objekta" (T19) — isto pravilo kao {@link #confirmRegistration}, da sučelje
     * ne nudi radnju koju backend odbija: prihvaćeno, još neupisano rješenje uz RB koji nije opozvan ni povučen.
     */
    private static boolean registrationConfirmable(CategorizationDecisionEntity e, RnStatus rnStatus) {
        return e.getStatus() == CategorizationDecisionStatus.VERIFIED && !e.isRegistered()
                && rnStatus != null && !FINAL_RN_STATUSES.contains(rnStatus);
    }

    /** Ime službenika; akter je {@code null} dok radnje nema (nepromjenjiva mapa ne podnosi {@code get(null)}). */
    private static String name(Map<String, String> names, String actor) {
        return actor == null ? null : names.get(actor);
    }

    /**
     * Strani porezni broj non-EU iznajmljivača po {@code lessorId} — on nema OIB, pa se u stupcu
     * „OIB iznajmljivača" prikazuje porezni broj. NIAS zapisi nemaju {@code lessorId}.
     */
    private Map<UUID, String> taxNumbers(List<CategorizationDecisionEntity> decisions) {
        Set<UUID> ids = decisions.stream()
                .map(CategorizationDecisionEntity::getLessorId)
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

    @Transactional(readOnly = true)
    public CategorizationFileDto file(UUID id, CountyScope scope) {
        CategorizationDecisionEntity e = findOrThrow(id, scope);
        return new CategorizationFileDto(e.getFileName(), e.getContentType(), e.getFileContent());
    }

    @Transactional
    public void verify(UUID id, CountyScope scope, String actor) {
        CategorizationDecisionEntity e = findOrThrow(id, scope);
        e.verify(actor);
        auditService.record(actor, "CATEGORIZATION_VERIFY", "CATEGORIZATION_DECISION", id.toString(), null);
    }

    /**
     * „Potvrda upisa objekta" (T19). Nakon što prihvati rješenje, službenik novi objekt upisuje u
     * interni eTurizam i pritom na njega upisuje dodijeljeni RB. Ovdje se provjerava da aktivna
     * jedinica s tim RB-om postoji, pa se rješenje veže uz nju („Nije upisano" → „Upisano").
     *
     * <p>Vlasnik objekta se namjerno ne uspoređuje s iznajmljivačem RB-a: RB je upisao službenik, a
     * obrt i OPG eTurizam vodi pod MBO/MIBPG, ne pod OIB-om.
     *
     * <p>Jedinica se upisuje na rješenje („Upisano") i na smještaj RB-a ({@code accommodation.facility_id}).
     * Od tada je RB vezan uz objekt TuRegistra kao i RB izdan za objekt iz eTurizma: opoziv i povlačenje brišu
     * broj iz {@code str.facility}, a uvaženi prigovor na povlačenje ga vraća ({@code RnWithdrawnListener}), pa
     * STR i TuStart ostaju usklađeni. Smještaj koji je već vezan uz objekt eTurizma (rješenje predano uz
     * neverificirani objekt) zadržava svoju vezu — ne prepisuje se.
     *
     * <p>Kad je RB upisan na jedinice dvaju različitih objekata, službenik to mora ispraviti u eTurizmu —
     * STR ne bira umjesto njega. Jedinice istog objekta su u redu; veže se najnovija. Opozvan ili povučen RB
     * se ne potvrđuje.
     *
     * <p>Dva službenika koja istodobno potvrde isto rješenje oba prođu provjeru i zapišu istu jedinicu
     * (traži se po istom RB-u), pa je ishod isti; ostaju samo dva zapisa u auditu.
     */
    @Transactional
    public void confirmRegistration(UUID id, CountyScope scope, String actor) {
        CategorizationDecisionEntity e = findOrThrow(id, scope);
        // Prije upita u eTurizam, da dvoklik ili već obrađen zapis ne čita str.* uzalud; isti 409 kao entitet.
        e.requireRegistrable();
        // Stari samostalni upload nema RB, pa ga se po RB-u ne može ni pronaći.
        if (e.getRn() == null) {
            throw new ConflictException("error.categorization.noRn", "CATEGORIZATION_NO_RN");
        }
        // Opozvan ili povučen broj više ne vrijedi — u TuRegistar se ne upisuje.
        RnEntity rn = rnRepository.findById(e.getRn()).orElse(null);
        if (rn == null || FINAL_RN_STATUSES.contains(rn.getStatus())) {
            throw new ConflictException("error.categorization.rnFinal", "CATEGORIZATION_RN_FINAL");
        }
        List<RegisteredFacilityRow> units = facilityRepository.findActiveByRegistrationNumber(e.getRn());
        if (units.isEmpty()) {
            throw new ConflictException("error.categorization.facilityNotFound", "CATEGORIZATION_FACILITY_NOT_FOUND");
        }
        long objects = units.stream()
                .map(u -> u.getObjectId() != null ? u.getObjectId() : "unit:" + u.getFacilityId())
                .distinct()
                .count();
        if (objects > 1) {
            throw new ConflictException("error.categorization.facilityAmbiguous", "CATEGORIZATION_FACILITY_AMBIGUOUS");
        }
        String facilityId = String.valueOf(units.stream()
                .max(Comparator.comparingLong(RegisteredFacilityRow::getFacilityId))
                .orElseThrow()
                .getFacilityId());
        e.confirmRegistration(facilityId, actor);
        boolean rnLinked = linkAccommodation(rn, facilityId);
        auditService.record(actor, "CATEGORIZATION_REGISTRATION_CONFIRM", "CATEGORIZATION_DECISION", id.toString(),
                "facilityId=" + facilityId + (rnLinked ? "" : " (smještaj RB-a već vezan)"));
    }

    /** Veže smještaj RB-a uz jedinicu TuRegistra, osim kad već ima vezu; {@code true} kad je upisana. */
    private boolean linkAccommodation(RnEntity rn, String facilityId) {
        AccommodationEntity accommodation = accommodationRepository.findById(rn.getAccommodationId())
                .orElseThrow(() -> new ResourceNotFoundException("Smještaj RB-a nije pronađen: " + rn.getRn()));
        if (accommodation.getFacilityId() != null && !accommodation.getFacilityId().isBlank()) {
            return false;
        }
        accommodation.setFacilityId(facilityId);
        return true;
    }

    @Transactional
    public void reject(UUID id, CountyScope scope, String actor, String reason) {
        CategorizationDecisionEntity e = findOrThrow(id, scope);
        e.reject(actor);
        auditService.record(actor, "CATEGORIZATION_REJECT", "CATEGORIZATION_DECISION", id.toString(), reason);
    }

    /**
     * Rješenje izvan opsega službenika (RB u drugoj županiji ili bez RB-a) je nepostojeće (404),
     * kao u redu, iz kojeg ga ne vidi.
     */
    private CategorizationDecisionEntity findOrThrow(UUID id, CountyScope scope) {
        return repository.findById(id)
                .filter(e -> !scope.restricted()
                        || (e.getRn() != null && scope.covers(rnRepository.findCountyByRn(e.getRn()).orElse(null))))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Rješenje o kategorizaciji nije pronađeno: " + id));
    }
}
