package com.str.backend.registration;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.address.CadastreResolver;
import com.str.backend.address.CountyNames;
import com.str.backend.address.CountyEntity;
import com.str.backend.address.CountyRepository;
import com.str.backend.address.MunicipalityEntity;
import com.str.backend.address.MunicipalityRepository;
import com.str.backend.address.SettlementEntity;
import com.str.backend.address.SettlementRepository;
import com.str.backend.auth.nias.ActingSubject;
import com.str.backend.auth.nias.NiasIdentity;
import com.str.backend.draft.SubmissionDraftService;
import com.str.backend.exception.BusinessException;
import com.str.backend.exception.DuplicateLocationException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.exception.ValidationRejectedException;
import com.str.backend.lessor.EnteredAddress;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.lessor.SubjectProfileService;
import com.str.backend.lookup.AccommodationTypeEntity;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.registration.dto.AccommodationRequest;
import com.str.backend.registration.dto.PodnositeljUnos;
import com.str.backend.registration.dto.RegistrationExternalRequest;
import com.str.backend.registration.dto.RegistrationRequest;
import com.str.backend.registration.dto.RegistrationResponse;
import com.str.backend.registration.event.RnIssuedEvent;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RnService;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.str.FacilityClaimVerifier;
import com.str.backend.str.StrFacilityRepository.FacilityOwnershipRow;
import com.str.backend.validation.ParallelValidationOrchestrator;
import com.str.backend.validation.PipelineResult;
import com.str.backend.validation.ValidationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);
    private final LessorRepository lessorRepository;
    private final AccommodationRepository accommodationRepository;
    private final SubmissionRepository submissionRepository;
    private final ParallelValidationOrchestrator orchestrator;
    private final RnService rnService;
    private final RnRepository rnRepository;
    private final SubjectProfileService subjectProfileService;
    private final CountyRepository countyRepository;
    private final MunicipalityRepository municipalityRepository;
    private final SettlementRepository settlementRepository;
    private final AccommodationTypeRepository accommodationTypeRepository;
    private final FacilityClaimVerifier facilityClaimVerifier;
    private final CadastreResolver cadastreResolver;
    private final ApplicationEventPublisher eventPublisher;
    private final SubmissionDraftService submissionDraftService;

    public RegistrationService(LessorRepository lessorRepository,
                               AccommodationRepository accommodationRepository,
                               SubmissionRepository submissionRepository,
                               ParallelValidationOrchestrator orchestrator,
                               RnService rnService,
                               RnRepository rnRepository,
                               SubjectProfileService subjectProfileService,
                               CountyRepository countyRepository,
                               MunicipalityRepository municipalityRepository,
                               SettlementRepository settlementRepository,
                               AccommodationTypeRepository accommodationTypeRepository,
                               FacilityClaimVerifier facilityClaimVerifier,
                               CadastreResolver cadastreResolver,
                               ApplicationEventPublisher eventPublisher,
                               SubmissionDraftService submissionDraftService) {
        this.lessorRepository = lessorRepository;
        this.accommodationRepository = accommodationRepository;
        this.submissionRepository = submissionRepository;
        this.orchestrator = orchestrator;
        this.rnService = rnService;
        this.rnRepository = rnRepository;
        this.subjectProfileService = subjectProfileService;
        this.countyRepository = countyRepository;
        this.municipalityRepository = municipalityRepository;
        this.settlementRepository = settlementRepository;
        this.accommodationTypeRepository = accommodationTypeRepository;
        this.facilityClaimVerifier = facilityClaimVerifier;
        this.cadastreResolver = cadastreResolver;
        this.eventPublisher = eventPublisher;
        this.submissionDraftService = submissionDraftService;
    }

    /** Bez NIAS assertiona (local/mock, testovi): identitet je samo OIB iz zahtjeva. */
    @Transactional(noRollbackFor = ValidationRejectedException.class)
    public RegistrationResponse generateRegistrationNumber(RegistrationRequest req) {
        return generateRegistrationNumber(req, null);
    }

    /**
     * @param niasIdentity identitet iz NIAS assertiona; ime i prezime iz njega su mjerodavni.
     *                     OIB zahtjeva kontroler je već zamijenio NIAS OIB-om, pa se ime uzima
     *                     samo kad se OIB-ovi slažu.
     */
    @Transactional(noRollbackFor = ValidationRejectedException.class)
    public RegistrationResponse generateRegistrationNumber(RegistrationRequest req, NiasIdentity niasIdentity) {
        return generateRegistrationNumber(req, niasIdentity, null);
    }

    /**
     * @param legalEntity pravna osoba u čije ime NIAS osoba djeluje, upravo ponovo potvrđena kroz
     *                    e-Ovlaštenja; {@code null} kad osoba djeluje u svoje ime. Kad je zadana,
     *                    {@code req.oib()} je OIB tvrtke (postavlja ga kontroler).
     */
    @Transactional(noRollbackFor = ValidationRejectedException.class)
    public RegistrationResponse generateRegistrationNumber(RegistrationRequest req, NiasIdentity niasIdentity,
                                                           ActingSubject legalEntity) {
        if (legalEntity != null && !legalEntity.legalOib().equals(req.oib())) {
            throw new IllegalArgumentException("OIB zahtjeva nije OIB pravne osobe u čije ime se djeluje");
        }
        AccommodationEntity accommodation = buildAccommodation(req, countyName(req.countyId()));
        verifyAndCompleteFacility(req.oib(), accommodation);
        requireMaxBeds(accommodation);
        checkDuplicateLocation(req.oib(), accommodation, req.confirmDuplicateLocation());

        // Identitet i adresa iz NIAS-a / registra, na serveru. Iz zahtjeva adresa (i MBS tvrtke)
        // vrijedi samo kad je registar nema — tada je obavezna (400 prije GO pipelinea i pohrane).
        // Nedostupan registar ne blokira: korisnik je tada adresu upisao sam.
        EnteredAddress entered = enteredAddress(req.podnositelj());
        LessorEntity lessor;
        if (legalEntity != null) {
            lessor = subjectProfileService.resolveLegalLessor(legalEntity.legalOib(), legalEntity.legalName(),
                    legalEntity.representativeOib(), legalEntity.representativeFirstName(),
                    legalEntity.representativeLastName(), entered);
        } else {
            boolean sameOib = niasIdentity != null && req.oib().equals(niasIdentity.oib());
            lessor = subjectProfileService.resolveLessor(req.oib(),
                    sameOib ? niasIdentity.firstName() : null,
                    sameOib ? niasIdentity.lastName() : null,
                    entered);
        }
        // Kontakt mora biti upisan PRIJE prve pohrane — lessor.email je updatable=false.
        // resolveLessor vraća još nepohranjen entitet (sprema ga tek commitRegistration).
        lessor.applyContact(trimmed(req.kontaktEmail()), trimmed(req.kontaktOsoba()),
                trimmed(req.kontaktTelefon()), trimmed(req.kontaktMobitel()));
        runValidation(accommodation, lessor);

        return commitRegistration(lessor, accommodation);
    }

    @Transactional(noRollbackFor = ValidationRejectedException.class)
    public RegistrationResponse generateRegistrationNumberExternal(RegistrationExternalRequest req, UUID lessorId) {
        String countyName = countyName(req.countyId());

        LessorEntity lessor = lessorRepository.findById(lessorId)
                .orElseThrow(() -> new ResourceNotFoundException("lessor not found: " + lessorId));

        AccommodationEntity accommodation = buildAccommodation(req, countyName);
        verifyAndCompleteFacility(lessor.getLessorOib(), accommodation);
        requireMaxBeds(accommodation);
        checkDuplicateLocation(lessor.getLessorOib(), accommodation, req.confirmDuplicateLocation());

        // Iznajmljivač je već pohranjen (samoregistracija), pa se e-mail ne dira — on je
        // identitet računa i stupac je updatable=false. Mobitel je obavezan i uvijek se upisuje.
        applyContact(lessor, req);
        runValidation(accommodation, lessor);

        return commitRegistration(lessor, accommodation);
    }

    /**
     * Upisuje kontakt na već pohranjenog iznajmljivača (non-EU samoregistracija).
     *
     * <p>Mobitel je obavezan i uvijek stiže, pa se uvijek upisuje — time non-EU iznajmljivač ne
     * može ostati bez ijednog broja, što je do sada bilo moguće jer je telefon na samoregistraciji
     * bio neobavezan. Ostala polja se, kad nisu poslana, <b>ne brišu</b>: zadržava se zatečena
     * vrijednost. E-mail se ne dira — {@code updatable = false}, identitet je računa.
     */
    private void applyContact(LessorEntity lessor, RegistrationExternalRequest req) {
        String osoba = trimmed(req.kontaktOsoba());
        String telefon = trimmed(req.kontaktTelefon());
        lessor.setContact(osoba != null ? osoba : lessor.getContactName(),
                telefon != null ? telefon : lessor.getPhoneNumber(),
                trimmed(req.kontaktMobitel()),
                lessor.getContactNote());
    }

    /**
     * Naziv županije, ili {@code null} kad je zahtjev nema — to smije samo postojeći eTurizam
     * objekt kojem eTurizam županiju ne zna (novi objekt je traži već na validaciji zahtjeva).
     * Poslan, a nepostojeći id i dalje je greška.
     */
    private String countyName(Long countyId) {
        if (countyId == null) {
            return null;
        }
        return countyRepository.findById(countyId)
                .map(CountyEntity::getName)
                .orElseThrow(() -> new ResourceNotFoundException("county not found: " + countyId));
    }

    /**
     * Adresa / MBS podnositelja s obrasca; županija se razrješava u naziv kao za objekt. Nepoznata
     * županija nije greška ovdje: upisana adresa vrijedi samo kad je registar nema, a tada je bez
     * županije nepotpuna i servis vraća {@code error.subject.addressRequired} (400). Zaostala
     * vrijednost iz nacrta tako ne može srušiti zahtjev kojem je adresa iz registra.
     */
    private EnteredAddress enteredAddress(PodnositeljUnos p) {
        if (p == null) {
            return null;
        }
        String county = p.zupanijaId() == null ? null
                : countyRepository.findById(p.zupanijaId()).map(CountyEntity::getName).orElse(null);
        return new EnteredAddress(trimmed(p.ulica()), trimmed(p.kucniBroj()), trimmed(p.postanskiBroj()),
                trimmed(p.mjesto()), trimmed(p.opcina()), county, trimmed(p.mbs()));
    }

    /** Prazan string iz forme je „nije upisano", ne vrijednost — ne spremamo ga kao takvog. */
    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Kad zahtjev nosi {@code facilityId} iz tuStart handoffa, objekt mora pripadati podnositelju,
     * a poslana vrsta, broj kreveta, naziv i adresa moraju odgovarati eTurizmu — v.
     * {@link FacilityClaimVerifier}. Provjera ide prije svega ostalog: jedina je brana između
     * tuđeg {@code facilityId} i write-backa RB-a u tuđi zapis.
     *
     * <p>Nakon provjere se dopunjava ono što zahtjev nije donio, a eTurizam zna. Obrazac ta polja
     * za postojeći objekt ne traži i šalje ih kao {@code null} kad ih ne uspije razriješiti
     * (šifrarnik nije stigao, naziv se ne nađe u adresnom registru), pa bi bez dopune RB nosio
     * kod županije ili vrste 00 i GO-1 ne bi znao županiju. Dopuna mijenja samo prazna polja.
     */
    private void requireMaxBeds(AccommodationEntity accommodation) {
        if (accommodation.getFacilityId() != null && accommodation.getMaxBeds() == null) {
            throw new BusinessException("error.accommodation.maxBedsRequired");
        }
    }

    private void verifyAndCompleteFacility(String oib, AccommodationEntity accommodation) {
        facilityClaimVerifier.verify(oib, accommodation.getFacilityId(),
                        new FacilityClaimVerifier.Claim(
                                accommodation.getAccommodationTypeId(),
                                accommodation.getMaxBeds(),
                                accommodation.getName(),
                                accommodation.getCounty(),
                                accommodation.getCity(),
                                accommodation.getSettlement(),
                                accommodation.getStreet(),
                                accommodation.getStreetNumber()))
                .ifPresent(facility -> accommodation.completeFrom(facilityData(facility)));
    }

    /** Ono što eTurizam stvarno zna o objektu, u obliku za dopunu; popunjivači postaju {@code null}. */
    private AccommodationEntity.FacilityData facilityData(FacilityOwnershipRow facility) {
        String subtypeCode = FacilityClaimVerifier.knownOrNull(facility.getSubtypeCode());
        Long typeId = subtypeCode == null ? null
                : accommodationTypeRepository.findByCodeIgnoreCase(subtypeCode)
                        .map(AccommodationTypeEntity::getTypeId)
                        .orElse(null);
        return new AccommodationEntity.FacilityData(
                FacilityClaimVerifier.objectName(facility),
                typeId,
                FacilityClaimVerifier.maxGuests(facility),
                registryCountyName(FacilityClaimVerifier.knownOrNull(facility.getCountyName())),
                FacilityClaimVerifier.knownOrNull(facility.getMunicipalityName()),
                FacilityClaimVerifier.knownOrNull(facility.getSettlementName()),
                FacilityClaimVerifier.knownOrNull(facility.getStreetName()),
                FacilityClaimVerifier.knownOrNull(facility.getHouseNumber()),
                FacilityClaimVerifier.knownOrNull(facility.getPostalCode()),
                FacilityClaimVerifier.knownOrNull(facility.getCategoryName()));
    }

    /**
     * Naziv županije iz eTurizma u obliku adresnog registra — u tom obliku ga zahtjev s
     * {@code countyId} sprema i po njemu {@code RnService} određuje kod županije u RB-u. Kad se
     * ne nađe, ostaje naziv iz eTurizma: bolji je od praznog, a izdavanje se ne smije blokirati.
     */
    private String registryCountyName(String eturizamName) {
        String key = CountyNames.key(eturizamName);
        if (key == null) {
            return null;
        }
        return countyRepository.findAll().stream()
                .map(CountyEntity::getName)
                .filter(name -> key.equals(CountyNames.key(name)))
                .findFirst()
                .orElse(eturizamName.trim());
    }

    /**
     * Surfaces a still-standing (ACTIVE/SUSPENSION_PROPOSED/SUSPENDED) RN that already covers
     * this address so the FE can prompt
     * for explicit confirmation (and only then proceed). Match is on the full address tuple
     * (county + city + street + streetNumber); when OIB is known it additionally narrows to
     * the same lessor, which is the common case. The house-number šifra used to be the sole
     * key but doesn't survive flows where it's missing — the address tuple does.
     */
    private void checkDuplicateLocation(String oib, AccommodationEntity accommodation, Boolean confirmed) {
        if (Boolean.TRUE.equals(confirmed)) return;
        if (isBlank(accommodation.getStreet()) || isBlank(accommodation.getStreetNumber())
                || isBlank(accommodation.getCity()) || isBlank(accommodation.getCounty())) {
            return;
        }
        rnRepository.findActiveOrSuspendedRnByAddressAndOib(
                        accommodation.getCounty(),
                        accommodation.getCity(),
                        accommodation.getStreet(),
                        accommodation.getStreetNumber(),
                        isBlank(oib) ? null : oib)
                .stream().findFirst()
                .ifPresent(rn -> { throw new DuplicateLocationException(rn); });
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * PDF zahtjeva nosi osobne podatke podnositelja (ime, OIB, adresu prebivališta), pa ga smije
     * preuzeti samo vlasnik. Tuđi zahtjev daje isti 404 kao nepostojeći — postojanje tuđeg
     * zapisa nije podatak koji ovaj endpoint smije otkriti.
     */
    @Transactional(readOnly = true)
    public SubmissionEntity getSubmissionForPdf(UUID submissionId, SubmissionRequester requester) {
        SubmissionEntity submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("submission not found: " + submissionId));
        if (!isOwnedBy(submission, requester)) {
            throw new ResourceNotFoundException("submission not found: " + submissionId);
        }
        if (submission.getPdfContent() == null || submission.getPdfContent().length == 0) {
            throw new ResourceNotFoundException("error.pdf.not.stored");
        }
        return submission;
    }

    private boolean isOwnedBy(SubmissionEntity submission, SubmissionRequester requester) {
        if (requester.unrestricted()) {
            return true;
        }
        if (requester.lessorId() != null) {
            return requester.lessorId().equals(submission.getLessorId());
        }
        return lessorRepository.findById(submission.getLessorId())
                .map(LessorEntity::getLessorOib)
                .filter(oib -> oib.equals(requester.oib()))
                .isPresent();
    }

    AccommodationEntity buildAccommodation(AccommodationRequest req, String countyName) {
        String cityName = resolveEntityName(req.cityId(), municipalityRepository, MunicipalityEntity::getName);
        String settlementName = resolveEntityName(req.settlementId(), settlementRepository, SettlementEntity::getName);
        // Obrazac ima jedno polje, maksimalan broj gostiju (kreveti + pomoćni kreveti, stavka 2),
        // koje putuje kao `maxBeds`. Isti broj ide u max_beds i max_guests; obje kolone su
        // zadržane radi već izdanih RB-ova, pa se podjela na krevete ovdje ne rekonstruira.
        // Prazan string je „nije upisano": za postojeći objekt ta polja više nisu @NotBlank, pa
        // bez ovoga "   " ne bi bio ni dopunjen iz eTurizma ni prepoznat kao prazan.
        AccommodationEntity entity = AccommodationEntity.create(
                null, countyName, cityName, trimmed(req.street()), trimmed(req.streetNumber()),
                req.maxBeds(), req.maxBeds(), req.offerType(), req.offering(),
                req.building(), req.apartments(), req.legalized());
        entity.setName(trimmed(req.name()));
        entity.setFacilityId(req.facilityId());
        entity.setSettlement(settlementName);
        entity.setPostalCode(trimmed(req.postalCode()));
        entity.setFloor(req.floor());
        entity.setLessorResidence(req.lessorResidence());
        // Katastar iz registra, ne iz zahtjeva — v. CadastreResolver. Ide prije provjere vlasništva
        // objekta: podmetnut kućni broj je neispravan zahtjev bez obzira na objekt.
        CadastreResolver.Cadastre katastar = cadastreResolver.resolve(
                req.kucniBrojId(), req.street(), req.streetNumber(), req.kcBroj());
        entity.setHouseNumberCode(katastar.sifra());
        entity.setCadastralMunicipality(katastar.katOpcinaNaziv());
        entity.setCadastralParcelNumber(katastar.kcCestica());
        entity.setConsent(req.coOwnerConsent(), req.consentDate(), req.consentWithdrawalDate());
        if (req.host() != null) {
            entity.markHost(req.host());
        }
        resolveAccommodationTypeId(req.typeId()).ifPresent(entity::setAccommodationTypeId);
        return entity;
    }

    /**
     * Prihvaća i numerički {@code type_id} i stabilnu šifru vrste ({@code FS_KUCA_ZA_ODMOR},
     * ...). Šifra je ono što tuStart šalje u handoff URL-u i ono na što se veže frontend,
     * jer se {@code type_id} razlikuje među okolinama.
     *
     * <p>Nerazrješiva vrsta se ne ignorira tiho: bez nje registracija gubi provjeru iz
     * {@code RnService.issue()} koja hotelu/kampu brani dodjelu RB-a, pa bi objekt bez
     * prava na RB prošao. Zato {@link BusinessException} (→ 400) umjesto praznog polja.
     */
    private Optional<Long> resolveAccommodationTypeId(String typeId) {
        if (typeId == null || typeId.isBlank()) {
            return Optional.empty();
        }
        String value = typeId.trim();

        if (value.chars().allMatch(Character::isDigit)) {
            long id = Long.parseLong(value);
            if (!accommodationTypeRepository.existsById(id)) {
                throw new BusinessException("error.accommodation.type.unknown");
            }
            return Optional.of(id);
        }

        return Optional.of(accommodationTypeRepository.findByCodeIgnoreCase(value)
                .orElseThrow(() -> {
                    log.warn("nepoznata vrsta smještaja '{}' — nije ni type_id ni šifra", value);
                    return new BusinessException("error.accommodation.type.unknown");
                })
                .getTypeId());
    }

    private void runValidation(AccommodationEntity accommodation, LessorEntity lessor) {
        ValidationContext context = new ValidationContext(accommodation, lessor);
        PipelineResult result = orchestrator.execute(context);
        if (result.getOutcome() == PipelineResult.Outcome.REJECTED) {
            throw new ValidationRejectedException(result.getStep(), result.getDetail());
        }
    }

    private RegistrationResponse commitRegistration(LessorEntity lessor, AccommodationEntity accommodation) {
        lessorRepository.save(lessor);

        // RN is issued before PDF/eGOP/e-mail. filing_number stays null — it
        // will be populated asynchronously after eGOP confirms, or stay null
        // on the non-EU e-mail path.
        SubmissionEntity submission = SubmissionEntity.create(
                null,
                lessor.getLessorId(),
                null,
                Instant.now(),
                null,
                null);
        submissionRepository.save(submission);

        accommodation.linkToSubmission(submission.getSubmissionId());
        accommodationRepository.save(accommodation);

        RnEntity rn = rnService.issue(submission.getSubmissionId(), accommodation.getAccommodationId());

        // Objekt je dobio RB, pa njegovi nacrti više nemaju svrhu — svi, i slučajni duplikati.
        // Ista transakcija: odbijen ili poništen zahtjev nacrte ostavlja.
        if (accommodation.getFacilityId() != null) {
            int discarded = submissionDraftService.discardForFacility(accommodation.getFacilityId());
            if (discarded > 0) {
                log.info("draft_discard facility={} count={}", accommodation.getFacilityId(), discarded);
            }
        }

        eventPublisher.publishEvent(new RnIssuedEvent(submission.getSubmissionId(), rn.getRn()));

        log.info("registration_success lessor={} submission={} rn={}",
                lessor.getLessorId(), submission.getSubmissionId(), rn.getRn());
        return new RegistrationResponse(rn.getRn(), submission.getSubmissionId());
    }

    /** {@code null} kad id nije poslan — postojeći eTurizam objekt kojem eTurizam to ne zna. */
    private <T> String resolveEntityName(String id, JpaRepository<T, Long> repository,
                                          Function<T, String> nameExtractor) {
        if (id == null || id.isBlank()) return null;
        try {
            return repository.findById(Long.parseLong(id))
                    .map(nameExtractor)
                    .orElse(id);
        } catch (NumberFormatException ignored) {
            return id;
        }
    }

}
