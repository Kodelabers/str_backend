package com.str.backend.str;

import com.str.backend.address.CountyNames;
import com.str.backend.exception.BusinessException;
import com.str.backend.lookup.AccommodationTypeEntity;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.rn.RnRepository;
import com.str.backend.str.StrFacilityRepository.FacilityOwnershipRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Provjerava tvrdnju „ovaj zahtjev se odnosi na taj postojeći eTurizam objekt".
 *
 * <p>Postoje četiri razloga zašto to ne smije ostati samo na frontendu:
 * <ul>
 *   <li><b>Vlasništvo.</b> Nakon dodjele RB-a {@code FacilityRegistrationNumberWriteBack} upisuje
 *       RB u {@code str.facility} za poslani {@code facilityId}. Bez provjere se može poslati
 *       tuđi ID i RB završi na tuđem objektu u tuđem registru.</li>
 *   <li><b>Vrsta, kapacitet, adresa i naziv.</b> Primjedba s UAT-a: za postojeći objekt se gornji
 *       podaci ne smiju mijenjati. Šifra podvrste u eTurizmu ({@code FS_*}) je ista kao
 *       {@code accommodation_type.code}, pa je usporedba direktna; broj kreveta (osnovni +
 *       pomoćni) kategoriziran je rješenjem, a ne slobodan unos.</li>
 *   <li><b>Dvostruki RB.</b> Objekt koji već ima stojeći RB ne smije dobiti drugi. Postojeći
 *       {@code checkDuplicateLocation} to ne pokriva: gleda adresu (županija + grad + ulica + kbr),
 *       a eTurizam adrese su rijetko strukturirane — ulica i kućni broj su najčešće prazni, pa se
 *       dva zahtjeva za isti objekt ne prepoznaju kao ista lokacija. Opozvani RB ne blokira novi
 *       zahtjev, jer je {@code WITHDRAWN} trajan i objekt smije proći novi postupak.</li>
 *   <li><b>Vrsta objekta.</b> Popis objekata prikazuje samo privatni smještaj, ali tuStart URL
 *       može nositi bilo koji vlastiti objekt (hotel, restoran, agenciju) — v.
 *       {@link #isRegistrableType}.</li>
 * </ul>
 *
 * <p><b>Usporedba se preskače kad eTurizam podatak ne zna.</b> Adrese u {@code str.address} su
 * rijetko strukturirane (ulica popunjena u 217 od 285.874 redaka, v. {@code docs/ETURIZAM-OBJEKTI.md}),
 * a naziv je često {@code -}. Stroga jednakost nad praznim izvorom proizvela bi lažne 400 na
 * ispravnim zahtjevima, pa vrijedi pravilo: usporedi samo ono što izvor stvarno zna. Popis polja
 * koja iz toga stvarno jesu zaključana vraća {@link #lockedFields(FacilityOwnershipRow)} — frontend
 * po njemu onemogući točno ta polja umjesto da pogađa iz tuStart URL parametara.
 *
 * <p>Maksimalan broj gostiju ({@code maxBeds} u zahtjevu) izvodi se iz kapaciteta: kreveti +
 * pomoćni kreveti, v. {@link #maxGuests(FacilityOwnershipRow)}. {@code CAT_BROJ_GOSTIJU} se ne
 * koristi — eTurizam ga za objekte u domaćinstvu ne vodi (postoji samo na razini jedinica hotela
 * i sličnih objekata).
 */
@Service
public class FacilityClaimVerifier {

    /**
     * Nazivi polja u tijelu {@code POST /api/generateRegistrationNumber} — frontend po njima
     * onemogući unos, pa moraju biti točno ta imena, ne interni nazivi kolona.
     */
    public static final String FIELD_TYPE = "typeId";
    public static final String FIELD_BEDS = "maxBeds";
    public static final String FIELD_NAME = "name";
    public static final String FIELD_COUNTY = "countyId";
    public static final String FIELD_CITY = "cityId";
    public static final String FIELD_SETTLEMENT = "settlementId";
    public static final String FIELD_STREET = "street";
    public static final String FIELD_STREET_NUMBER = "streetNumber";

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
     * Podaci iz zahtjeva koji se uspoređuju s eTurizmom. Svi smiju biti null: obrazac za postojeći
     * objekt šalje samo ono što eTurizam zna. Izostavljen podatak se ne uspoređuje — izostavljanjem
     * se ne može podmetnuti drukčija vrijednost, nego samo ostaviti prazno.
     * {@code maxBeds} je maksimalan broj gostiju (kreveti + pomoćni kreveti, stavka 2).
     */
    public record Claim(Long accommodationTypeId, Integer maxBeds, String name, String county,
                        String city, String settlement, String street, String streetNumber) {
    }

    /**
     * @param oib        OIB podnositelja (iz NIAS asertacije, ne iz tijela zahtjeva)
     * @param facilityId {@code str.facility.id} iz tuStart handoffa; {@code null} ili prazno
     *                   znači novi objekt i provjera se preskače
     * @param claim      podaci iz zahtjeva
     * @return provjereni eTurizam zapis — iz njega pozivatelj dopunjava ono što zahtjev nije
     *         donio; prazno za novi objekt
     */
    @Transactional(readOnly = true)
    public Optional<FacilityOwnershipRow> verify(String oib, String facilityId, Claim claim) {
        if (facilityId == null || facilityId.isBlank()) {
            return Optional.empty();
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
        if (!isActive(facility)) {
            throw new BusinessException("error.facility.inactive");
        }
        if (!rnRepository.findRnsByFacilityIds(List.of(facilityId.trim())).isEmpty()) {
            throw new BusinessException("error.facility.alreadyRegistered");
        }
        // Prije usporedbi: vrsta koja nije privatni smještaj (hotel, restoran, agencija) ne dobiva
        // RB bez obzira na ostalo. Ne smije ovisiti o tome je li zahtjev vrstu uopće poslao —
        // inače bi izostavljen typeId preskočio i ovu provjeru i zabranu u RnService.issue().
        if (!isRegistrableType(facility, accommodationTypeRepository)) {
            throw new BusinessException("error.facility.type.notAllowed");
        }

        if (differs(facility.getSubtypeCode(), resolveSubmittedCode(claim.accommodationTypeId()))) {
            throw new BusinessException("error.facility.type.mismatch");
        }

        Integer expectedGuests = maxGuests(facility);
        if (expectedGuests != null && claim.maxBeds() != null && !expectedGuests.equals(claim.maxBeds())) {
            throw new BusinessException("error.facility.beds.mismatch");
        }

        if (differs(objectName(facility), claim.name())) {
            throw new BusinessException("error.facility.name.mismatch");
        }

        if (differsCounty(facility.getCountyName(), claim.county())
                || differs(facility.getMunicipalityName(), claim.city())
                || differs(facility.getSettlementName(), claim.settlement())
                || differs(facility.getStreetName(), claim.street())
                || differs(facility.getHouseNumber(), claim.streetNumber())) {
            throw new BusinessException("error.facility.address.mismatch");
        }
        return Optional.of(facility);
    }

    /**
     * Smije li objekt te vrste dobiti RB: vrsta mora biti poznata vrsta privatnog smještaja iz
     * šifrarnika (ista koju prikazuje popis objekata, {@code findAllCodes}) i dopuštena
     * ({@code registrationNumberAllowed}).
     *
     * <p>Kad eTurizam vrstu ne zna, zabrana se ne može utemeljiti, pa RB nije blokiran — postojeći
     * objekt mora u svakom trenutku moći dobiti RB, a {@code RnService.issue()} provjerava vrstu
     * čim je poznata.
     */
    public static boolean isRegistrableType(FacilityOwnershipRow facility,
                                            AccommodationTypeRepository accommodationTypeRepository) {
        String code = facility.getSubtypeCode();
        if (!known(code)) {
            return true;
        }
        return accommodationTypeRepository.findByCodeIgnoreCase(code.trim())
                .map(AccommodationTypeEntity::isRegistrationNumberAllowed)
                .orElse(false);
    }

    /**
     * Posluje li jedinica i je li aktualna: zapis je aktivan, poslovni status je {@code FBS_ACTIVE}
     * <b>i</b> zapis je aktualan po pravilima eTurizma ({@code StrFacilityRepository.RANGIRANE_JEDINICE_OD}).
     * Isto pravilo kao popis objekata ({@code StrFacilityRepository.PRIKAZ_ZA_OIB}) — jedinica koja se
     * na popisu ne vidi ne smije proći ni kroz tuStart handoff s {@code facilityId}.
     *
     * <p>Samo {@code facility.active} nije dovoljan: to je zastavica verzije zapisa, pa je na CDU
     * „Odjavljen" gotovo polovica zapisa s {@code active = true}. Objekt bez statusa ne prolazi —
     * ne zna se da posluje, a RB bi se upisao natrag u eTurizam. Ne prolazi ni zapis koji nije
     * aktualan (stara verzija, predmet u obradi, migrirana kopija koju je zamijenio noviji predmet):
     * RB bi završio na zapisu koji eTurizam više ne smatra objektom.
     */
    public static boolean isActive(FacilityOwnershipRow facility) {
        return Boolean.TRUE.equals(facility.getActive())
                && StrFacilityRepository.ACTIVE_BUSINESS_STATUS.equals(facility.getBusinessStatusCode())
                && Boolean.TRUE.equals(facility.getCurrent());
    }

    /**
     * Maksimalan broj gostiju objekta iz eTurizma: kreveti + pomoćni kreveti (stavka 2 sa
     * sastanka). Jedno mjesto za taj račun — po njemu se predpopunjava claim, zaključava polje i
     * provjerava predaja, pa se ta tri ne mogu razići.
     *
     * <p>{@code null} kad eTurizam ne zna broj kreveta jedinice: tada polje nije zaključano i
     * usporedba se preskače. Pomoćni kreveti bez kreveta ne čine kapacitet, pa se sami ne računaju.
     * Ne zna ga ni kad su kreveti kapacitet cijelog migriranog objekta
     * ({@link FacilityOwnershipRow#getObjectLevelCapacity()}): zaključati ga značilo bi upisati
     * kapacitet objekta uz registracijski broj jedinice (B-3, P-22).
     */
    public static Integer maxGuests(FacilityOwnershipRow facility) {
        if (Boolean.TRUE.equals(facility.getObjectLevelCapacity())) {
            return null;
        }
        Integer beds = facility.getBeds();
        if (beds == null || beds <= 0) {
            return null;
        }
        Integer auxiliary = facility.getAuxiliaryBeds();
        return beds + (auxiliary != null && auxiliary > 0 ? auxiliary : 0);
    }

    /**
     * Polja koja su za ovaj objekt stvarno zaključana — ona za koja eTurizam ima podatak, pa bi
     * ih {@link #verify} odbio da stignu izmijenjena. Frontend po ovom popisu onemogući unos.
     */
    public static List<String> lockedFields(FacilityOwnershipRow facility) {
        List<String> locked = new ArrayList<>();
        if (known(facility.getSubtypeCode())) locked.add(FIELD_TYPE);
        if (maxGuests(facility) != null) locked.add(FIELD_BEDS);
        if (known(objectName(facility))) locked.add(FIELD_NAME);
        if (known(facility.getCountyName())) locked.add(FIELD_COUNTY);
        if (known(facility.getMunicipalityName())) locked.add(FIELD_CITY);
        if (known(facility.getSettlementName())) locked.add(FIELD_SETTLEMENT);
        if (known(facility.getStreetName())) locked.add(FIELD_STREET);
        if (known(facility.getHouseNumber())) locked.add(FIELD_STREET_NUMBER);
        return locked;
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

    /**
     * Naziv objekta iz eTurizma, ili {@code null} kad ga eTurizam zapravo nema.
     *
     * <p>Uz popunjivač ({@code -}, prazno) kao „nema naziva" broji se i slučaj kad je
     * {@code facility.name} <b>ime samog iznajmljivača</b>. Na CDU je to 27.912 od 242.468 aktivnih
     * objekata (11,5 %) — 5.470 jednakih {@code subject_version.name} i 22.442 jednakih
     * „ime prezime". Zaključati tu vrijednost značilo bi da tih 28 tisuća vlasnika ne može upisati
     * stvarni naziv objekta, a upravo je to polje koje bi trebali popuniti.
     *
     * <p>Naziv koji je vrsta smještaja („Apartman", „Studio apartman" — na CDU preko 12 tisuća
     * zapisa) se <b>ne</b> preskače: to jest vrijednost koju je eTurizam upisao kao naziv, pa se
     * po zahtjevu naručitelja zaključava. Smije li se i to mijenjati, poslovna je odluka.
     */
    public static String objectName(FacilityOwnershipRow facility) {
        String name = facility.getName();
        if (!known(name)) {
            return null;
        }
        if (matches(name, facility.getOwnerName()) || matches(name, facility.getOwnerFullName())) {
            return null;
        }
        return name;
    }

    private static boolean matches(String a, String b) {
        return known(b) && normalize(a).equals(normalize(b));
    }

    /**
     * Isto za županiju, ali po {@link CountyNames#key}: adresni registar piše „… županija", a
     * eTurizam ne nužno — bez toga bi ispravan zahtjev pao na lažnoj razlici adrese.
     */
    private static boolean differsCounty(String expected, String submitted) {
        String a = CountyNames.key(expected);
        String b = CountyNames.key(submitted);
        return known(a) && known(b) && !a.equals(b);
    }

    /** Razlikuju li se, uz pravilo „nepoznato se ne uspoređuje". */
    private static boolean differs(String expected, String submitted) {
        return known(expected) && known(submitted) && !normalize(expected).equals(normalize(submitted));
    }

    /** Vrijednost iz eTurizma ako je stvarno poznata (v. {@link #known}), inače {@code null}. */
    public static String knownOrNull(String value) {
        return known(value) ? value.trim() : null;
    }

    /**
     * Je li vrijednost upotrebljiva za usporedbu. Uz prazno, kao nepoznato se broji i vrijednost
     * <b>bez ijednog slova ili znamenke</b> — {@code -}, {@code --}, {@code .}, {@code —}. To su u
     * {@code str.facility.name} uobičajeni popunjivači, a zaključati ih značilo bi zabraniti
     * korisniku da upiše stvarni naziv objekta.
     *
     * <p>Namjerno kao pravilo, ne kao popis: popis bi se razišao sa stvarnim podacima čim se pojavi
     * novi oblik popunjivača, a ovako svaki takav upada sam.
     */
    private static boolean known(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.chars().anyMatch(Character::isLetterOrDigit);
    }

    /** Bez razlike u velikim/malim slovima, rubnim i višestrukim razmacima. */
    private static String normalize(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
