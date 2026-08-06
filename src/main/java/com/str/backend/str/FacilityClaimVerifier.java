package com.str.backend.str;

import com.str.backend.exception.BusinessException;
import com.str.backend.lookup.AccommodationTypeEntity;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.rn.RnRepository;
import com.str.backend.str.StrFacilityRepository.FacilityOwnershipRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Provjerava tvrdnju „ovaj zahtjev se odnosi na taj postojeći eTurizam objekt".
 *
 * <p>Postoje tri razloga zašto to ne smije ostati samo na frontendu:
 * <ul>
 *   <li><b>Vlasništvo.</b> Nakon dodjele RB-a {@code FacilityRegistrationNumberWriteBack} upisuje
 *       RB u {@code str.facility} za poslani {@code facilityId}. Bez provjere se može poslati
 *       tuđi ID i RB završi na tuđem objektu u tuđem registru.</li>
 *   <li><b>Vrsta.</b> Klijentova primjedba: za postojeći objekt se vrsta ne smije mijenjati.
 *       Šifra podvrste u eTurizmu ({@code FS_*}) je ista kao {@code accommodation_type.code},
 *       pa je usporedba direktna.</li>
 *   <li><b>Kapacitet.</b> Broj kreveta je u eTurizmu kategoriziran rješenjem, ne slobodan unos.</li>
 *   <li><b>Dvostruki RB.</b> Objekt koji već ima stojeći RB ne smije dobiti drugi. Postojeći
 *       {@code checkDuplicateLocation} to ne pokriva: gleda adresu (županija + grad + ulica + kbr),
 *       a eTurizam adrese su rijetko strukturirane — ulica i kućni broj su najčešće prazni, pa se
 *       dva zahtjeva za isti objekt ne prepoznaju kao ista lokacija. Opozvani RB ne blokira novi
 *       zahtjev, jer je {@code WITHDRAWN} trajan i objekt smije proći novi postupak.</li>
 * </ul>
 *
 * <p>Broj gostiju se namjerno ne provjerava — eTurizam ga za objekte u domaćinstvu ne vodi
 * ({@code CAT_BROJ_GOSTIJU} postoji samo na razini jedinica hotela i sličnih objekata).
 */
@Service
public class FacilityClaimVerifier {

    private final StrFacilityRepository facilityRepository;
    private final AccommodationTypeRepository accommodationTypeRepository;
    private final RnRepository rnRepository;

    public FacilityClaimVerifier(StrFacilityRepository facilityRepository,
                                 AccommodationTypeRepository accommodationTypeRepository,
                                 RnRepository rnRepository) {
        this.facilityRepository = facilityRepository;
        this.accommodationTypeRepository = accommodationTypeRepository;
        this.rnRepository = rnRepository;
    }

    /**
     * @param oib                 OIB podnositelja (iz NIAS asertacije, ne iz tijela zahtjeva)
     * @param facilityId          {@code str.facility.id} iz tuStart handoffa; {@code null} ili
     *                            prazno znači novi objekt i provjera se preskače
     * @param accommodationTypeId razriješena vrsta iz zahtjeva
     * @param maxBeds             broj kreveta iz zahtjeva
     */
    @Transactional(readOnly = true)
    public void verify(String oib, String facilityId, Long accommodationTypeId, int maxBeds) {
        if (facilityId == null || facilityId.isBlank()) {
            return;
        }
        long id;
        try {
            id = Long.parseLong(facilityId.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException("error.facility.unknown");
        }

        FacilityOwnershipRow facility = facilityRepository.findOwnership(id)
                .orElseThrow(() -> new BusinessException("error.facility.unknown"));

        if (oib == null || !oib.equals(facility.getOib())) {
            throw new BusinessException("error.facility.notOwned");
        }
        if (Boolean.FALSE.equals(facility.getActive())) {
            throw new BusinessException("error.facility.inactive");
        }
        if (!rnRepository.findRnsByFacilityIds(List.of(facilityId.trim())).isEmpty()) {
            throw new BusinessException("error.facility.alreadyRegistered");
        }

        String expectedCode = facility.getSubtypeCode();
        String submittedCode = resolveSubmittedCode(accommodationTypeId);
        if (expectedCode != null && submittedCode != null && !expectedCode.equalsIgnoreCase(submittedCode)) {
            throw new BusinessException("error.facility.type.mismatch");
        }

        Integer expectedBeds = facility.getBeds();
        if (expectedBeds != null && expectedBeds > 0 && expectedBeds != maxBeds) {
            throw new BusinessException("error.facility.beds.mismatch");
        }
    }

    /**
     * Vrsta iz zahtjeva u {@code FS_*} šifru. Vrsta bez šifre (npr. hotel — šifru imaju samo
     * vrste privatnog smještaja, changeset 060) daje {@code null}, pa se usporedba preskače
     * umjesto da lažno padne.
     */
    private String resolveSubmittedCode(Long accommodationTypeId) {
        if (accommodationTypeId == null) {
            return null;
        }
        return accommodationTypeRepository.findById(accommodationTypeId)
                .map(AccommodationTypeEntity::getCode)
                .orElse(null);
    }
}
