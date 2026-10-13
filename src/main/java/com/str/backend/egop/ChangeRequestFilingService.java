package com.str.backend.egop;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.document.StrDocumentService;
import com.str.backend.document.StrDocumentType;
import com.str.backend.domain.RnStatus;
import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import com.str.backend.str.StrFacilityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Zapisuje pismeno „Zahtjev za promjenu podataka" uz upravo izdani RB: iznajmljivač je označio
 * da podaci preuzeti iz registra eTurizma nisu točni, pa ga forma nakon izdavanja preusmjerava na
 * eTurizmov obrazac. Pismeno u našem spisu bilježi da je taj zahtjev započet.
 *
 * <p>Isti put kao akt životnog ciklusa ({@link RnLifecycleFilingListener}) — render pa zapis u
 * {@code egop_pismeno} — ali <b>bez slanja u eGOP</b>: vrsta je u
 * {@link EgopAktiBezSifre#UVIJEK_BEZ_SIFRE}, pa je ni {@link EgopRetryJob} ne dira.
 *
 * <p>Jedno pismeno po RB-u: {@code act_ref} je stalan, pa ponovljeni poziv (dvoklik, ponovno
 * slanje) završi na postojećem retku zahvaljujući ključu {@code (submission_id,
 * vrsta_pismena_naziv, act_ref)}.
 *
 * <p>Bez {@code @Transactional}: čitanja su kratka, a {@link StrDocumentService} i
 * {@link EgopFilingStore} imaju vlastite {@code REQUIRES_NEW} transakcije.
 */
@Service
public class ChangeRequestFilingService {

    private static final Logger log = LoggerFactory.getLogger(ChangeRequestFilingService.class);

    static final StrDocumentType TYPE = StrDocumentType.ZAHTJEV_PROMJENE_PODATAKA;
    static final String ACT_REF = "ZAHTJEV_PROMJENE_PODATAKA";

    private final RnRepository rnRepository;
    private final AccommodationRepository accommodationRepository;
    private final StrDocumentService documentService;
    private final EgopFilingStore store;
    private final StrFacilityRepository facilityRepository;

    public ChangeRequestFilingService(RnRepository rnRepository,
                                      AccommodationRepository accommodationRepository,
                                      StrDocumentService documentService,
                                      EgopFilingStore store,
                                      StrFacilityRepository facilityRepository) {
        this.rnRepository = rnRepository;
        this.accommodationRepository = accommodationRepository;
        this.documentService = documentService;
        this.store = store;
        this.facilityRepository = facilityRepository;
    }

    /**
     * @param ownerOib vlasnik za kojeg se radi (OIB tvrtke u ime tvrtke), već provjeren u kontroleru
     * @throws ResourceNotFoundException RB ne postoji ili nije vlasnikov — isti 404, da se ne otkriva tuđi
     * @throws BusinessException         RB nije aktivan, nije izdan za objekt iz eTurizma ili je
     *                                   objekt neverificiran ({@code error.changeRequest.notVerified})
     */
    public void file(String rn, String ownerOib) {
        RnEntity entity = rnRepository.findById(rn)
                .filter(r -> r.getSubmissionId() != null && rnRepository.isOwnedByOib(rn, ownerOib))
                .orElseThrow(() -> new ResourceNotFoundException("rn not found: " + rn));
        // Zahtjev za promjenu ide uz upravo izdan broj; suspendiran ili povučen RB ga ne otvara.
        if (entity.getStatus() != RnStatus.ACTIVE) {
            throw new BusinessException("error.changeRequest.rnNotActive");
        }
        // Samo objekt preuzet iz eTurizma ima „podatke iz registra" — za novi objekt nema što mijenjati.
        Optional<String> facilityId = entity.getAccommodationId() == null ? Optional.empty()
                : accommodationRepository.findById(entity.getAccommodationId())
                        .map(AccommodationEntity::getFacilityId)
                        .filter(id -> !id.isBlank());
        if (facilityId.isEmpty()) {
            throw new BusinessException("error.changeRequest.notEturizamFacility");
        }
        // T7: neverificirani objekt se ne preusmjerava na TuStart — njegove podatke verificira
        // nadležno tijelo. Claim mu zato ne vraća adresu obrasca; ovo brani izravan poziv API-ja.
        if (isUnverifiedFacility(facilityId.get())) {
            throw new BusinessException("error.changeRequest.notVerified");
        }

        // Razlog nije dio ovog pismena; prazan string, da render ne čita revizijski trag RB-a.
        byte[] pdf = documentService.render(TYPE, rn, "");
        EgopPismenoEntity akt = store.saveAkt(EgopPismenoEntity.forAct(
                entity.getSubmissionId(), rn, ACT_REF, TYPE.vrstaPismenaNaziv(),
                EgopPismenoEntity.Smjer.ULAZNO, pdf));
        log.info("zahtjev_promjene_podataka_zapisan rn={} akt={}", rn, akt.getId());
    }

    /**
     * Isto pravilo kao claim objekta ({@code NiasFacilityService#claim}, polje {@code verificiran})
     * i predaja rješenja o kategorizaciji: zapis jedinice spremljen uz RB, {@code created_by =
     * 'optimit'} je neverificiran. Nepoznato (nema zapisa, nema autora, id nije broj) ne računa se
     * kao neverificirano — kao i na frontendu.
     */
    private boolean isUnverifiedFacility(String facilityId) {
        long id;
        try {
            id = Long.parseLong(facilityId.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        return facilityRepository.findOwnership(id)
                .map(row -> Boolean.FALSE.equals(row.getVerified()))
                .orElse(false);
    }
}
