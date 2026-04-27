package com.str.backend.registracija;

import com.str.backend.core.CoreObjektEntity;
import com.str.backend.core.CoreObjektRepository;
import com.str.backend.domain.Scenarij;
import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.exception.ValidationRejectedException;
import com.str.backend.iznajmljivac.IznajmljivacEntity;
import com.str.backend.iznajmljivac.IznajmljivacRepository;
import com.str.backend.rb.RbEntity;
import com.str.backend.rb.RbService;
import com.str.backend.registracija.dto.IznajmljivacRequest;
import com.str.backend.registracija.dto.RegistracijaRequest;
import com.str.backend.registracija.dto.RegistracijaResponse;
import com.str.backend.sso.SsoEntity;
import com.str.backend.sso.SsoRepository;
import com.str.backend.validation.ParallelValidacijskiOrkestrator;
import com.str.backend.validation.PipelineRezultat;
import com.str.backend.validation.ValidacijskiKontekst;
import com.str.backend.registracija.dto.SsoRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * STR §5/§6: single entry point for RB issuance.
 * Per discussion: one endpoint, RB returned only after all GO checks pass; checks
 * run in parallel. No persisted Zahtjev workflow — for S1 (postojeći objekt) the SSO
 * already exists; for S2/S3 (novi objekt) the SSO is created up front and RB is
 * issued in the same transaction once validation succeeds.
 */
@Service
public class RegistracijaService {

    private static final Logger log = LoggerFactory.getLogger(RegistracijaService.class);

    private final IznajmljivacRepository iznajmljivacRepository;
    private final SsoRepository ssoRepository;
    private final CoreObjektRepository coreObjektRepository;
    private final ParallelValidacijskiOrkestrator orkestrator;
    private final RbService rbService;

    public RegistracijaService(IznajmljivacRepository iznajmljivacRepository,
                               SsoRepository ssoRepository,
                               CoreObjektRepository coreObjektRepository,
                               ParallelValidacijskiOrkestrator orkestrator,
                               RbService rbService) {
        this.iznajmljivacRepository = iznajmljivacRepository;
        this.ssoRepository = ssoRepository;
        this.coreObjektRepository = coreObjektRepository;
        this.orkestrator = orkestrator;
        this.rbService = rbService;
    }

    @Transactional(noRollbackFor = ValidationRejectedException.class)
    public RegistracijaResponse registriraj(RegistracijaRequest req) {
        IznajmljivacEntity iznajmljivac = buildIznajmljivac(req.getIznajmljivac());
        iznajmljivacRepository.save(iznajmljivac);

        List<SsoEntity> ssoList = materijaliziraj(req);
        List<RegistracijaResponse.DodijeljeniRb> dodijeljeni = new ArrayList<>(ssoList.size());

        for (SsoEntity sso : ssoList) {
            CoreObjektEntity core = sso.getIdCoreObjekt() != null
                    ? coreObjektRepository.findById(sso.getIdCoreObjekt()).orElse(null)
                    : null;
            ValidacijskiKontekst kontekst = new ValidacijskiKontekst(sso, iznajmljivac, core);
            PipelineRezultat rez = orkestrator.izvrsi(kontekst);
            if (rez.getIshod() == PipelineRezultat.Ishod.ODBIJEN) {
                throw new ValidationRejectedException(rez.getStep(), rez.getDetail());
            }
            RbEntity rb = rbService.issue(null, sso.getIdSso());
            dodijeljeni.add(new RegistracijaResponse.DodijeljeniRb(sso.getIdSso(), rb.getRb()));
        }

        log.info("registracija_success scenarij={} iznajmljivac={} count={}",
                req.getScenarij(), iznajmljivac.getIdIznajmljivaca(), dodijeljeni.size());
        return new RegistracijaResponse(req.getScenarij(), iznajmljivac.getIdIznajmljivaca(), dodijeljeni);
    }

    private IznajmljivacEntity buildIznajmljivac(IznajmljivacRequest r) {
        IznajmljivacEntity e = IznajmljivacEntity.create(
                r.getIme(), r.getPrezime(), r.getUlica(), r.getKucniBroj(), r.getMjesto(), r.getZupanija(), r.getEmail());
        if (r.getOibZastupnika() != null || r.getNazivPravneOsobe() != null) {
            e.setLegalEntity(r.getOibZastupnika(), r.getNazivPravneOsobe(),
                    r.getZastupnikPravneOsobe(), r.getEmailZastupnika(), r.getTelefonZastupnika());
        }
        if (r.getImeKontakta() != null || r.getBrojTelefona() != null || r.getBrojMobitela() != null) {
            e.setContact(r.getImeKontakta(), r.getBrojTelefona(), r.getBrojMobitela(), r.getNapomenaKontakta());
        }
        if (r.getAdresaZastupnika() != null) {
            e.setAdresaZastupnika(r.getAdresaZastupnika());
        }
        return e;
    }

    private List<SsoEntity> materijaliziraj(RegistracijaRequest req) {
        return switch (req.getScenarij()) {
            case S1_POSTOJECI_OBJEKT -> loadExisting(req);
            case S2_NOVI_OBJEKT_VANJSKI, S3_NOVI_OBJEKT_INTERNI -> createNew(req);
        };
    }

    private List<SsoEntity> loadExisting(RegistracijaRequest req) {
        if (req.getSso().stream().anyMatch(s -> s.getIdCoreObjekt() == null)) {
            throw new BusinessException("S1 requires idCoreObjekt for each sso");
        }
        List<SsoEntity> result = new ArrayList<>(req.getSso().size());
        for (SsoRequest sr : req.getSso()) {
            SsoEntity sso = ssoRepository.findByIdCoreObjekt(sr.getIdCoreObjekt())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "sso not found for core objekt: " + sr.getIdCoreObjekt()));
            result.add(sso);
        }
        return result;
    }

    private List<SsoEntity> createNew(RegistracijaRequest req) {
        List<SsoEntity> result = new ArrayList<>(req.getSso().size());
        UUID syntheticZahtjev = UUID.randomUUID();
        for (SsoRequest sr : req.getSso()) {
            SsoEntity sso = SsoEntity.create(
                    syntheticZahtjev, sr.getZupanija(), sr.getGrad(), sr.getUlica(),
                    sr.getKucniBroj(), sr.getMaxKreveta(), sr.getMaxGostiju(), sr.getPonuda(),
                    sr.getZgrada(), sr.getStanovi(), sr.getLegalizirano());
            sso.setLocationDetails(sr.getNaselje(), sr.getKat(), sr.getKatastarskaOpcina(),
                    sr.getBrojKatastarskeCestice(), sr.getOznakaSso(), sr.getBoravisteIznajmljivaca(),
                    sr.getIdVrsteSso(), sr.getIdCoreObjekt());
            if (sr.getSuglasnostSuvlasnika() != null || sr.getDatumSuglasnosti() != null
                    || sr.getDatumPovlacenjaSuglasnosti() != null) {
                sso.setSuglasnost(sr.getSuglasnostSuvlasnika(), sr.getDatumSuglasnosti(),
                        sr.getDatumPovlacenjaSuglasnosti());
            }
            ssoRepository.save(sso);
            result.add(sso);
        }
        return result;
    }
}
