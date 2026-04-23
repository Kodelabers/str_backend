package com.str.backend.zahtjev;

import com.str.backend.core.CoreObjektEntity;
import com.str.backend.core.CoreObjektRepository;
import com.str.backend.domain.Kanal;
import com.str.backend.domain.ZahtjevStatus;
import com.str.backend.domain.ZahtjevTrigger;
import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.exception.ValidationRejectedException;
import com.str.backend.iznajmljivac.IznajmljivacEntity;
import com.str.backend.iznajmljivac.IznajmljivacRepository;
import com.str.backend.lookup.VrstaZahtjevaEntity;
import com.str.backend.lookup.VrstaZahtjevaRepository;
import com.str.backend.rb.RbService;
import com.str.backend.sso.SsoEntity;
import com.str.backend.sso.SsoRepository;
import com.str.backend.validation.PipelineRezultat;
import com.str.backend.validation.ValidacijskiKontekst;
import com.str.backend.validation.ValidacijskiOrkestrator;
import com.str.backend.zahtjev.dto.CreateZahtjevRequest;
import com.str.backend.zahtjev.dto.SsoRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ZahtjevService {

    private static final Logger log = LoggerFactory.getLogger(ZahtjevService.class);
    private static final SecureRandom RNG = new SecureRandom();

    private final ZahtjevRepository zahtjevRepository;
    private final SsoRepository ssoRepository;
    private final IznajmljivacRepository iznajmljivacRepository;
    private final VrstaZahtjevaRepository vrstaZahtjevaRepository;
    private final CoreObjektRepository coreObjektRepository;
    private final ZahtjevStatusTransitionService transitionService;
    private final ValidacijskiOrkestrator orkestrator;
    private final RbService rbService;

    public ZahtjevService(ZahtjevRepository zahtjevRepository,
                          SsoRepository ssoRepository,
                          IznajmljivacRepository iznajmljivacRepository,
                          VrstaZahtjevaRepository vrstaZahtjevaRepository,
                          CoreObjektRepository coreObjektRepository,
                          ZahtjevStatusTransitionService transitionService,
                          ValidacijskiOrkestrator orkestrator,
                          RbService rbService) {
        this.zahtjevRepository = zahtjevRepository;
        this.ssoRepository = ssoRepository;
        this.iznajmljivacRepository = iznajmljivacRepository;
        this.vrstaZahtjevaRepository = vrstaZahtjevaRepository;
        this.coreObjektRepository = coreObjektRepository;
        this.transitionService = transitionService;
        this.orkestrator = orkestrator;
        this.rbService = rbService;
    }

    @Transactional
    public ZahtjevEntity iniciraj(CreateZahtjevRequest req) {
        IznajmljivacEntity iznajmljivac = iznajmljivacRepository.findById(req.idIznajmljivaca())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "iznajmljivac not found: " + req.idIznajmljivaca()));

        VrstaZahtjevaEntity vrsta = vrstaZahtjevaRepository.findById(req.oznakaVrste())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "vrsta zahtjeva not found: " + req.oznakaVrste()));
        if (!"AKTIVAN".equals(vrsta.getStatus())) {
            throw new BusinessException("vrsta zahtjeva not active: " + req.oznakaVrste());
        }

        String ur = generateUniqueUr();
        ZahtjevEntity zahtjev = ZahtjevEntity.initiate(
                ur, req.kanal(), req.oznakaVrste(),
                iznajmljivac.getIdIznajmljivaca(), req.idNadleznogTijela());
        zahtjevRepository.save(zahtjev);

        for (SsoRequest sr : req.sso()) {
            SsoEntity sso = SsoEntity.create(
                    zahtjev.getIdZahtjeva(), sr.zupanija(), sr.grad(), sr.ulica(),
                    sr.kucniBroj(), sr.maxKreveta(), sr.maxGostiju(), sr.ponuda(),
                    sr.zgrada(), sr.stanovi(), sr.legalizirano());
            sso.setLocationDetails(sr.naselje(), sr.kat(), sr.katastarskaOpcina(),
                    sr.brojKatastarskeCestice(), sr.oznakaSso(), sr.boravisteIznajmljivaca(),
                    sr.idVrsteSso(), sr.idCoreObjekt());
            if (sr.suglasnostSuvlasnika() != null || sr.datumSuglasnosti() != null
                    || sr.datumPovlacenjaSuglasnosti() != null) {
                sso.setSuglasnost(sr.suglasnostSuvlasnika(), sr.datumSuglasnosti(),
                        sr.datumPovlacenjaSuglasnosti());
            }
            ssoRepository.save(sso);
        }

        if (req.kanal() == Kanal.STRANAC) {
            // STRANAC flow requires document upload before SUBMIT — stays in INICIIRAN
            log.info("zahtjev_initiated id={} ur={} kanal=STRANAC awaitingUpload", zahtjev.getIdZahtjeva(), ur);
        } else {
            log.info("zahtjev_initiated id={} ur={} kanal={}", zahtjev.getIdZahtjeva(), ur, req.kanal());
        }
        return zahtjev;
    }

    @Transactional
    public ZahtjevEntity uploadDokument(UUID idZahtjeva, String link) {
        ZahtjevEntity zahtjev = load(idZahtjeva);
        if (zahtjev.getKanal() != Kanal.STRANAC) {
            throw new BusinessException("upload allowed only for STRANAC channel");
        }
        zahtjev.setLinkDokumenta(link);
        transitionService.transition(zahtjev, ZahtjevStatus.U_VERIFIKACIJI, ZahtjevTrigger.STRANAC_UPLOAD);
        return zahtjev;
    }

    @Transactional(noRollbackFor = ValidationRejectedException.class)
    public ZahtjevEntity approveReferent(UUID idZahtjeva) {
        ZahtjevEntity zahtjev = load(idZahtjeva);
        transitionService.transition(zahtjev, ZahtjevStatus.U_OBRADI, ZahtjevTrigger.REFERENT_APPROVE);
        runPipelineAndResolve(zahtjev);
        return zahtjev;
    }

    @Transactional(noRollbackFor = ValidationRejectedException.class)
    public ZahtjevEntity submit(UUID idZahtjeva) {
        ZahtjevEntity zahtjev = load(idZahtjeva);
        if (zahtjev.getKanal() == Kanal.STRANAC) {
            throw new BusinessException("STRANAC zahtjev requires referent approval, not submit");
        }
        transitionService.transition(zahtjev, ZahtjevStatus.U_OBRADI, ZahtjevTrigger.SUBMIT);
        runPipelineAndResolve(zahtjev);
        return zahtjev;
    }

    private void runPipelineAndResolve(ZahtjevEntity zahtjev) {
        IznajmljivacEntity iznajmljivac = iznajmljivacRepository.findById(zahtjev.getIdIznajmljivaca())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "iznajmljivac not found: " + zahtjev.getIdIznajmljivaca()));

        List<SsoEntity> ssoList = ssoRepository.findByIdZahtjeva(zahtjev.getIdZahtjeva());
        if (ssoList.isEmpty()) {
            throw new BusinessException("zahtjev has no sso entries: " + zahtjev.getIdZahtjeva());
        }

        List<SsoEntity> prosli = new ArrayList<>();
        for (SsoEntity sso : ssoList) {
            CoreObjektEntity core = sso.getIdCoreObjekt() != null
                    ? coreObjektRepository.findById(sso.getIdCoreObjekt()).orElse(null)
                    : null;
            ValidacijskiKontekst kontekst = new ValidacijskiKontekst(zahtjev, sso, iznajmljivac, core);
            PipelineRezultat rez = orkestrator.izvrsi(kontekst);
            if (rez.ishod() == PipelineRezultat.Ishod.ODBIJEN) {
                transitionService.transition(zahtjev, ZahtjevStatus.ODBIJEN, ZahtjevTrigger.VALIDATION_REJECTED);
                throw new ValidationRejectedException(rez.step(), rez.detail());
            }
            prosli.add(sso);
        }

        for (SsoEntity sso : prosli) {
            rbService.issue(zahtjev.getIdZahtjeva(), sso.getIdSso());
        }
        transitionService.transition(zahtjev, ZahtjevStatus.PRIHVACEN, ZahtjevTrigger.VALIDATION_PASSED);
    }

    @Transactional(readOnly = true)
    public ZahtjevEntity dohvati(UUID id) {
        return load(id);
    }

    @Transactional(readOnly = true)
    public List<SsoEntity> ssoZa(UUID idZahtjeva) {
        return ssoRepository.findByIdZahtjeva(idZahtjeva);
    }

    private ZahtjevEntity load(UUID id) {
        return zahtjevRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("zahtjev not found: " + id));
    }

    private String generateUniqueUr() {
        int year = Year.now().getValue();
        for (int i = 0; i < 5; i++) {
            String ur = "UR-%d-%06d".formatted(year, RNG.nextInt(1_000_000));
            if (!zahtjevRepository.existsByUrZahtjeva(ur)) {
                return ur;
            }
        }
        throw new BusinessException("cannot generate unique ur_zahtjeva");
    }
}
