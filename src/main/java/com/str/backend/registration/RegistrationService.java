package com.str.backend.registration;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.domain.Scenario;
import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.exception.ValidationRejectedException;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.pdf.SubmissionPdfGenerator;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnService;
import com.str.backend.registration.dto.AccommodationRequest;
import com.str.backend.registration.dto.LessorRequest;
import com.str.backend.registration.dto.RegistrationRequest;
import com.str.backend.registration.dto.RegistrationResponse;
import com.str.backend.registries.EgopClient;
import com.str.backend.registries.GisClient;
import com.str.backend.registries.RpjClient;
import com.str.backend.registries.SrClient;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.validation.ParallelValidationOrchestrator;
import com.str.backend.validation.PipelineResult;
import com.str.backend.validation.ValidationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);
    private static final String TYPE_NEW_REGISTRATION = "NOVA_REGISTRACIJA";

    private final LessorRepository lessorRepository;
    private final AccommodationRepository accommodationRepository;
    private final SubmissionRepository submissionRepository;
    private final ParallelValidationOrchestrator orchestrator;
    private final RnService rnService;
    private final GisClient gisClient;
    private final RpjClient rpjClient;
    private final SrClient srClient;
    private final EgopClient egopClient;
    private final SubmissionPdfGenerator pdfGenerator;

    public RegistrationService(LessorRepository lessorRepository,
                               AccommodationRepository accommodationRepository,
                               SubmissionRepository submissionRepository,
                               ParallelValidationOrchestrator orchestrator,
                               RnService rnService,
                               GisClient gisClient,
                               RpjClient rpjClient,
                               SrClient srClient,
                               EgopClient egopClient,
                               SubmissionPdfGenerator pdfGenerator) {
        this.lessorRepository = lessorRepository;
        this.accommodationRepository = accommodationRepository;
        this.submissionRepository = submissionRepository;
        this.orchestrator = orchestrator;
        this.rnService = rnService;
        this.gisClient = gisClient;
        this.rpjClient = rpjClient;
        this.srClient = srClient;
        this.egopClient = egopClient;
        this.pdfGenerator = pdfGenerator;
    }

    @Transactional(noRollbackFor = ValidationRejectedException.class)
    public RegistrationResponse generateRegistrationNumber(RegistrationRequest req) {
        enrich(req);

        LessorEntity lessor = buildLessor(req.getLessor());

        // Build accommodations in memory (not yet persisted) so GO checks can run
        // before we touch eGOP. For S1 this loads existing entities from DB.
        boolean isNewScenario = req.getScenario() != Scenario.S1_EXISTING_UNIT;
        List<AccommodationEntity> accommodationList = isNewScenario
                ? buildNewAccommodations(req)
                : loadExisting(req);

        // GO-1 through GO-5: validate before reserving the filing number.
        // An eGOP call must never happen for a request that would be rejected.
        for (AccommodationEntity accommodation : accommodationList) {
            ValidationContext context = new ValidationContext(accommodation, lessor);
            PipelineResult result = orchestrator.execute(context);
            if (result.getOutcome() == PipelineResult.Outcome.REJECTED) {
                throw new ValidationRejectedException(result.getStep(), result.getDetail());
            }
        }

        // Validation passed — now call eGOP
        byte[] draftPdf = pdfGenerator.generate(req, lessor, null);
        EgopClient.UrudzbeniBroj filing = egopClient.rezervirajUrudzbeniBroj();
        byte[] finalPdf = pdfGenerator.generate(req, lessor, filing.formatiran());
        EgopClient.PotvrdaUrudzbiranja confirmation =
                egopClient.posaljiZahtjev(filing.formatiran(), finalPdf);

        log.info("egop_filing_ok filingNumber={} draft_size={} final_size={}",
                confirmation.urudzbeniBroj(), draftPdf.length, finalPdf.length);

        lessorRepository.save(lessor);
        SubmissionEntity submission = SubmissionEntity.create(
                confirmation.urudzbeniBroj(),
                TYPE_NEW_REGISTRATION,
                lessor.getLessorId(),
                req.getCompetentAuthorityId(),
                confirmation.datumPotvrde(),
                "egop://" + confirmation.urudzbeniBroj(),
                finalPdf);
        submissionRepository.save(submission);

        // For new accommodations, link submission and persist now that we have the ID
        if (isNewScenario) {
            for (AccommodationEntity accommodation : accommodationList) {
                accommodation.linkToSubmission(submission.getSubmissionId());
                accommodationRepository.save(accommodation);
            }
        }

        List<RegistrationResponse.AssignedRb> assigned = new ArrayList<>(accommodationList.size());
        for (AccommodationEntity accommodation : accommodationList) {
            RnEntity rn = rnService.issue(submission.getSubmissionId(), accommodation.getAccommodationId());
            assigned.add(new RegistrationResponse.AssignedRb(accommodation.getAccommodationId(), rn.getRn()));
        }

        log.info("registration_success scenario={} lessor={} submission={} count={}",
                req.getScenario(), lessor.getLessorId(), submission.getSubmissionId(),
                assigned.size());
        return new RegistrationResponse(req.getScenario(), lessor.getLessorId(), assigned);
    }

    private void enrich(RegistrationRequest req) {
        LessorRequest lr = req.getLessor();
        if (lr.getRepresentativeOib() != null && lr.getLegalEntityName() == null) {
            srClient.dohvatiPravnuOsobu(lr.getRepresentativeOib()).ifPresent(po -> {
                lr.setLegalEntityName(po.naziv());
                if (lr.getRepresentativeAddress() == null) {
                    lr.setRepresentativeAddress(po.sjediste());
                }
                if (lr.getLegalRepresentativeName() == null && !po.zastupnici().isEmpty()) {
                    lr.setLegalRepresentativeName(po.zastupnici().get(0));
                }
            });
        }
        for (AccommodationRequest s : req.getAccommodations()) {
            rpjClient.normalizirajAdresu(s.getCounty(), s.getCity(), s.getStreet(), s.getStreetNumber())
                    .ifPresent(a -> {
                        if (s.getSettlement() == null) s.setSettlement(a.naselje());
                    });
            gisClient.dohvatiParcelu(s.getCadastralMunicipality(), s.getCadastralParcelNumber())
                    .ifPresent(p -> log.debug("gis_lookup_ok ko={} brc={} legalan={}",
                            p.katastarskaOpcina(), p.brojCestice(), p.legalanObjekt()));
        }
    }

    private LessorEntity buildLessor(LessorRequest r) {
        LessorEntity e = LessorEntity.create(
                r.getFirstName(), r.getLastName(), r.getStreet(), r.getStreetNumber(),
                r.getPlace(), r.getCounty(), r.getEmail());
        if (r.getRepresentativeOib() != null || r.getLegalEntityName() != null) {
            e.setLegalEntity(r.getRepresentativeOib(), r.getLegalEntityName(),
                    r.getLegalRepresentativeName(), r.getRepresentativeEmail(), r.getRepresentativePhone());
        }
        if (r.getContactName() != null || r.getPhoneNumber() != null || r.getMobileNumber() != null) {
            e.setContact(r.getContactName(), r.getPhoneNumber(), r.getMobileNumber(), r.getContactNote());
        }
        if (r.getRepresentativeAddress() != null) {
            e.setRepresentativeAddress(r.getRepresentativeAddress());
        }
        return e;
    }

    private List<AccommodationEntity> loadExisting(RegistrationRequest req) {
        if (req.getAccommodations().stream().anyMatch(s -> s.getCoreObjectId() == null)) {
            throw new BusinessException("S1 requires coreObjectId for each accommodation");
        }
        List<AccommodationEntity> result = new ArrayList<>(req.getAccommodations().size());
        for (AccommodationRequest ar : req.getAccommodations()) {
            AccommodationEntity accommodation = accommodationRepository.findByCoreObjectId(ar.getCoreObjectId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "accommodation not found for core object: " + ar.getCoreObjectId()));
            result.add(accommodation);
        }
        return result;
    }

    private List<AccommodationEntity> buildNewAccommodations(RegistrationRequest req) {
        List<AccommodationEntity> result = new ArrayList<>(req.getAccommodations().size());
        for (AccommodationRequest ar : req.getAccommodations()) {
            AccommodationEntity accommodation = AccommodationEntity.create(
                    null, ar.getCounty(), ar.getCity(), ar.getStreet(),
                    ar.getStreetNumber(), ar.getMaxBeds(), ar.getMaxGuests(), ar.getOfferType(),
                    ar.getBuilding(), ar.getApartments(), ar.getLegalized());
            accommodation.setLocationDetails(ar.getSettlement(), ar.getFloor(),
                    ar.getCadastralMunicipality(), ar.getCadastralParcelNumber(),
                    ar.getAccommodationCode(), ar.getLessorResidence(),
                    ar.getAccommodationTypeId(), ar.getCoreObjectId());
            if (ar.getCoOwnerConsent() != null || ar.getConsentDate() != null
                    || ar.getConsentWithdrawalDate() != null) {
                accommodation.setConsent(ar.getCoOwnerConsent(), ar.getConsentDate(),
                        ar.getConsentWithdrawalDate());
            }
            result.add(accommodation);
        }
        return result;
    }
}
