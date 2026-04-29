package com.str.backend.registracija;

import com.str.backend.core.CoreObjektEntity;
import com.str.backend.core.CoreObjektRepository;
import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.exception.ValidationRejectedException;
import com.str.backend.iznajmljivac.IznajmljivacEntity;
import com.str.backend.iznajmljivac.IznajmljivacRepository;
import com.str.backend.pdf.ZahtjevPdfGenerator;
import com.str.backend.rb.RbEntity;
import com.str.backend.rb.RbService;
import com.str.backend.registracija.dto.IznajmljivacRequest;
import com.str.backend.registracija.dto.RegistracijaRequest;
import com.str.backend.registracija.dto.RegistracijaResponse;
import com.str.backend.registracija.dto.SsoRequest;
import com.str.backend.registries.EgopClient;
import com.str.backend.registries.GisClient;
import com.str.backend.registries.RpjClient;
import com.str.backend.registries.SrClient;
import com.str.backend.sso.SsoEntity;
import com.str.backend.sso.SsoRepository;
import com.str.backend.validation.ParallelValidacijskiOrkestrator;
import com.str.backend.validation.PipelineRezultat;
import com.str.backend.validation.ValidacijskiKontekst;
import com.str.backend.zahtjev.ZahtjevEntity;
import com.str.backend.zahtjev.ZahtjevRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * STR §3 osnovni flow registracije:
 *   1. forma → enrich (GIS/RPJ/SR) — popunjavanje kanonskih podataka iz core registara
 *   2. PdfGenerator → inicijalni PDF (bez urudžbenog broja)
 *   3. eGOP {@code rezervirajUrudzbeniBroj()} → službeni broj
 *   4. PdfGenerator → finalni PDF s utisnutim brojem
 *   5. eGOP {@code posaljiZahtjev(...)} → potvrda urudžbiranja
 *   6. spremanje metapodataka zahtjeva (str.zahtjev) — urudžbeni, datum, vrsta, link
 *   7. spremanje iznajmljivača i SSO (tek nakon uspješnog urudžbiranja)
 *   8. validacijski orkestrator (GO-1..GO-5)
 *   9. izdavanje RB-a po SSO-u
 *
 * Ako bilo koji korak prije generiranja RB-a padne, transakcija se rolla i ne
 * ostavljaju se "siroče" entitete u bazi.
 */
@Service
public class RegistracijaService {

    private static final Logger log = LoggerFactory.getLogger(RegistracijaService.class);
    private static final String VRSTA_NOVA_REGISTRACIJA = "NOVA_REGISTRACIJA";

    private final IznajmljivacRepository iznajmljivacRepository;
    private final SsoRepository ssoRepository;
    private final ZahtjevRepository zahtjevRepository;
    private final CoreObjektRepository coreObjektRepository;
    private final ParallelValidacijskiOrkestrator orkestrator;
    private final RbService rbService;
    private final GisClient gisClient;
    private final RpjClient rpjClient;
    private final SrClient srClient;
    private final EgopClient egopClient;
    private final ZahtjevPdfGenerator pdfGenerator;

    public RegistracijaService(IznajmljivacRepository iznajmljivacRepository,
                               SsoRepository ssoRepository,
                               ZahtjevRepository zahtjevRepository,
                               CoreObjektRepository coreObjektRepository,
                               ParallelValidacijskiOrkestrator orkestrator,
                               RbService rbService,
                               GisClient gisClient,
                               RpjClient rpjClient,
                               SrClient srClient,
                               EgopClient egopClient,
                               ZahtjevPdfGenerator pdfGenerator) {
        this.iznajmljivacRepository = iznajmljivacRepository;
        this.ssoRepository = ssoRepository;
        this.zahtjevRepository = zahtjevRepository;
        this.coreObjektRepository = coreObjektRepository;
        this.orkestrator = orkestrator;
        this.rbService = rbService;
        this.gisClient = gisClient;
        this.rpjClient = rpjClient;
        this.srClient = srClient;
        this.egopClient = egopClient;
        this.pdfGenerator = pdfGenerator;
    }

    @Transactional(noRollbackFor = ValidationRejectedException.class)
    public RegistracijaResponse registriraj(RegistracijaRequest req) {
        // 1. Obogaćivanje podataka iz core registara
        obogati(req);

        // 2-5. Build entiteta u memoriji + PDF + eGOP urudžbiranje
        IznajmljivacEntity iznajmljivac = buildIznajmljivac(req.getIznajmljivac());

        byte[] draftPdf = pdfGenerator.generiraj(req, iznajmljivac, null);
        EgopClient.UrudzbeniBroj urudzbeni = egopClient.rezervirajUrudzbeniBroj();
        byte[] finalPdf = pdfGenerator.generiraj(req, iznajmljivac, urudzbeni.formatiran());
        EgopClient.PotvrdaUrudzbiranja potvrda =
                egopClient.posaljiZahtjev(urudzbeni.formatiran(), finalPdf);

        log.info("egop_urudzbiranje_ok urudzbeni={} draft_size={} final_size={}",
                potvrda.urudzbeniBroj(), draftPdf.length, finalPdf.length);

        // 6. Spremanje iznajmljivača i metapodataka zahtjeva (str.zahtjev)
        iznajmljivacRepository.save(iznajmljivac);
        ZahtjevEntity zahtjev = ZahtjevEntity.create(
                potvrda.urudzbeniBroj(),
                VRSTA_NOVA_REGISTRACIJA,
                iznajmljivac.getIdIznajmljivaca(),
                req.getIdNadleznogTijela(),
                potvrda.datumPotvrde(),
                "egop://" + potvrda.urudzbeniBroj(),
                finalPdf);
        zahtjevRepository.save(zahtjev);

        // 7. Spremanje SSO-ova vezanih na zahtjev
        List<SsoEntity> ssoList = materijaliziraj(req, zahtjev.getIdZahtjeva());

        // 8-9. Validacija + RB po SSO-u
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
            RbEntity rb = rbService.issue(zahtjev.getIdZahtjeva(), sso.getIdSso());
            dodijeljeni.add(new RegistracijaResponse.DodijeljeniRb(sso.getIdSso(), rb.getRb()));
        }

        log.info("registracija_success scenarij={} iznajmljivac={} zahtjev={} count={}",
                req.getScenarij(), iznajmljivac.getIdIznajmljivaca(), zahtjev.getIdZahtjeva(),
                dodijeljeni.size());
        return new RegistracijaResponse(req.getScenarij(), iznajmljivac.getIdIznajmljivaca(), dodijeljeni);
    }

    /**
     * GIS/RPJ/SR enrichment — popunjava nedostajuća polja u request DTO-u prije perzistiranja.
     * Ne overrida postojeće vrijednosti koje je korisnik već unio.
     */
    private void obogati(RegistracijaRequest req) {
        IznajmljivacRequest ir = req.getIznajmljivac();
        if (ir.getOibZastupnika() != null && ir.getNazivPravneOsobe() == null) {
            srClient.dohvatiPravnuOsobu(ir.getOibZastupnika()).ifPresent(po -> {
                ir.setNazivPravneOsobe(po.naziv());
                if (ir.getAdresaZastupnika() == null) {
                    ir.setAdresaZastupnika(po.sjediste());
                }
                if (ir.getZastupnikPravneOsobe() == null && !po.zastupnici().isEmpty()) {
                    ir.setZastupnikPravneOsobe(po.zastupnici().get(0));
                }
            });
        }
        for (SsoRequest s : req.getSso()) {
            rpjClient.normalizirajAdresu(s.getZupanija(), s.getGrad(), s.getUlica(), s.getKucniBroj())
                    .ifPresent(a -> {
                        if (s.getNaselje() == null) s.setNaselje(a.naselje());
                    });
            gisClient.dohvatiParcelu(s.getKatastarskaOpcina(), s.getBrojKatastarskeCestice())
                    .ifPresent(p -> {
                        // GIS može ovjeriti postojanje parcele; legalnost se odlučuje u GO-3
                        log.debug("gis_lookup_ok ko={} brc={} legalan={}",
                                p.katastarskaOpcina(), p.brojCestice(), p.legalanObjekt());
                    });
        }
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

    private List<SsoEntity> materijaliziraj(RegistracijaRequest req, UUID idZahtjeva) {
        return switch (req.getScenarij()) {
            case S1_POSTOJECI_OBJEKT -> loadExisting(req);
            case S2_NOVI_OBJEKT_VANJSKI, S3_NOVI_OBJEKT_INTERNI -> createNew(req, idZahtjeva);
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

    private List<SsoEntity> createNew(RegistracijaRequest req, UUID idZahtjeva) {
        List<SsoEntity> result = new ArrayList<>(req.getSso().size());
        for (SsoRequest sr : req.getSso()) {
            SsoEntity sso = SsoEntity.create(
                    idZahtjeva, sr.getZupanija(), sr.getGrad(), sr.getUlica(),
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
