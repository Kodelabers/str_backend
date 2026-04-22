package com.str.backend.sso;

import com.str.backend.audit.AuditLogEntity;
import com.str.backend.audit.AuditLogRepository;
import com.str.backend.core.CoreObjektEntity;
import com.str.backend.core.CoreObjektRepository;
import com.str.backend.domain.RegistracijskiBroj;
import com.str.backend.domain.Status;
import com.str.backend.domain.TransitionTrigger;
import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.exception.ValidationRejectedException;
import com.str.backend.iznajmljivac.IznajmljivacEntity;
import com.str.backend.iznajmljivac.IznajmljivacRepository;
import com.str.backend.sso.dto.CreateSsoRequest;
import com.str.backend.sso.dto.IznajmljivacRequest;
import com.str.backend.validation.PipelineRezultat;
import com.str.backend.validation.ValidacijskiKontekst;
import com.str.backend.validation.ValidacijskiOrkestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class SsoService {

    private static final Logger log = LoggerFactory.getLogger(SsoService.class);
    private static final int MAX_RB_ATTEMPTS = 5;

    private final SsoRepository ssoRepository;
    private final IznajmljivacRepository iznajmljivacRepository;
    private final CoreObjektRepository coreObjektRepository;
    private final ValidacijskiOrkestrator orkestrator;
    private final StatusTransitionService statusTransitionService;
    private final AuditLogRepository auditLogRepository;

    public SsoService(SsoRepository ssoRepository,
                      IznajmljivacRepository iznajmljivacRepository,
                      CoreObjektRepository coreObjektRepository,
                      ValidacijskiOrkestrator orkestrator,
                      StatusTransitionService statusTransitionService,
                      AuditLogRepository auditLogRepository) {
        this.ssoRepository = ssoRepository;
        this.iznajmljivacRepository = iznajmljivacRepository;
        this.coreObjektRepository = coreObjektRepository;
        this.orkestrator = orkestrator;
        this.statusTransitionService = statusTransitionService;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public SsoEntity iniciraj(CreateSsoRequest req) {
        CoreObjektEntity coreObjekt = coreObjektRepository.findById(req.coreObjektUuid())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "core.objekt not found: " + req.coreObjektUuid()));

        if (ssoRepository.existsById(coreObjekt.getUuid())) {
            throw new BusinessException("sso already exists for core uuid " + coreObjekt.getUuid());
        }

        SsoEntity sso = SsoEntity.initiate(
                coreObjekt.getUuid(),
                req.kapacitetKreveta(),
                req.kapacitetGostiju(),
                req.ponuda(),
                req.kat(),
                req.brojStana());
        ssoRepository.save(sso);

        IznajmljivacRequest i = req.iznajmljivac();
        iznajmljivacRepository.save(IznajmljivacEntity.snapshot(
                sso.getUuidSso(), i.oib(), i.nazivPrezime(), i.adresaPrebivalista()));

        auditLogRepository.save(AuditLogEntity.transition(
                sso.getUuidSso(), null, Status.INICIIRAN.name(), "CREATED"));
        log.info("sso_initiated uuidSso={}", sso.getUuidSso());
        return sso;
    }

    @Transactional
    public SsoEntity validiraj(UUID uuidSso) {
        SsoEntity sso = ucitaj(uuidSso);
        statusTransitionService.transition(sso, Status.VALIDACIJA, TransitionTrigger.USER_SUBMIT);

        CoreObjektEntity coreObjekt = coreObjektRepository.findById(uuidSso)
                .orElseThrow(() -> new ResourceNotFoundException("core.objekt not found: " + uuidSso));
        IznajmljivacEntity iznajmljivac = iznajmljivacRepository
                .findTopByUuidSsoOrderByCreatedAtDesc(uuidSso)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "iznajmljivac snapshot missing for sso " + uuidSso));

        ValidacijskiKontekst kontekst = new ValidacijskiKontekst(sso, coreObjekt, iznajmljivac);
        PipelineRezultat rezultat = orkestrator.izvrsi(kontekst);

        return switch (rezultat.ishod()) {
            case PROSAO -> {
                assignRegistracijskiBroj(sso);
                statusTransitionService.transition(sso, Status.AKTIVAN, TransitionTrigger.VALIDATION_PASSED);
                yield sso;
            }
            case CEKA_CALLBACK -> {
                statusTransitionService.transition(sso, Status.U_OBRADI, TransitionTrigger.AWAITING_CALLBACK);
                yield sso;
            }
            case ODBIJEN -> throw new ValidationRejectedException(rezultat.step(), rezultat.detail());
        };
    }

    @Transactional
    public SsoEntity potvrdiCallback(UUID uuidSso) {
        SsoEntity sso = ucitaj(uuidSso);
        assignRegistracijskiBroj(sso);
        statusTransitionService.transition(sso, Status.AKTIVAN, TransitionTrigger.CALLBACK_CONFIRMED);
        return sso;
    }

    @Transactional
    public SsoEntity suspendiraj(UUID uuidSso, TransitionTrigger razlog) {
        if (razlog != TransitionTrigger.CONSENT_EXPIRY && razlog != TransitionTrigger.INSPECTION) {
            throw new BusinessException("suspend trigger must be CONSENT_EXPIRY or INSPECTION");
        }
        SsoEntity sso = ucitaj(uuidSso);
        statusTransitionService.transition(sso, Status.SUSPENDIRAN, razlog);
        return sso;
    }

    @Transactional
    public SsoEntity povuci(UUID uuidSso) {
        SsoEntity sso = ucitaj(uuidSso);
        statusTransitionService.transition(sso, Status.POVUCEN, TransitionTrigger.WITHDRAWAL);
        return sso;
    }

    @Transactional(readOnly = true)
    public SsoEntity dohvati(UUID uuidSso) {
        return ucitaj(uuidSso);
    }

    private SsoEntity ucitaj(UUID uuidSso) {
        return ssoRepository.findById(uuidSso)
                .orElseThrow(() -> new ResourceNotFoundException("sso not found: " + uuidSso));
    }

    private void assignRegistracijskiBroj(SsoEntity sso) {
        if (sso.getRegistracijskiBroj() != null) {
            return;
        }
        for (int attempt = 0; attempt < MAX_RB_ATTEMPTS; attempt++) {
            String candidate = RegistracijskiBroj.generate().value();
            if (!ssoRepository.existsByRegistracijskiBroj(candidate)) {
                sso.assignRegistracijskiBroj(candidate);
                return;
            }
        }
        throw new BusinessException("Could not generate unique registracijski broj after "
                + MAX_RB_ATTEMPTS + " attempts");
    }
}
