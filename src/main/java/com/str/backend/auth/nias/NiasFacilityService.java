package com.str.backend.auth.nias;

import com.str.backend.categorization.CategorizationDecisionEntity;
import com.str.backend.categorization.CategorizationDecisionRepository;
import com.str.backend.categorization.CategorizationDecisionStatus;
import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.rn.RnRepository;
import com.str.backend.str.FacilityClaimVerifier;
import com.str.backend.str.StrFacilityRepository;
import com.str.backend.str.StrFacilityRepository.FacilityListingRow;
import com.str.backend.str.StrFacilityRepository.FacilityOwnershipRow;
import com.str.backend.str.StrFacilityRepository.ListingTotals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Popis objekata prijavljenog iznajmljivača, spojen iz dva izvora: eTurizam registra i naših
 * uploadanih skeniranih rješenja koja još nisu upisana u eTurizam.
 *
 * <p>Redoslijed: verificirani eTurizam objekti, zatim neverificirani (migrirani iz starog
 * sustava), zatim privremena rješenja. Redak je smještajna jedinica, a paginacija broji
 * <b>objekte</b> ({@code system_uuid}): stranica nosi najviše {@code size} objekata sa svim
 * njihovim jedinicama. Privremeno rješenje je zaseban objekt s jednom jedinicom, pa je
 * {@code total} zbroj eTurizam objekata i privremenih rješenja.
 */
@Service
public class NiasFacilityService {

    private static final Logger log = LoggerFactory.getLogger(NiasFacilityService.class);

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    /** Šifra vrste „Zahtjev za promjenu podataka" u eTurizmovu šifrarniku ({@code str.codebook_element}). */
    static final String CHANGE_REQUEST_TYPE_CODE = "DST_Z_PROMJ_POD";

    private final StrFacilityRepository facilityRepository;
    private final AccommodationTypeRepository accommodationTypeRepository;
    private final RnRepository rnRepository;
    private final CategorizationDecisionRepository decisionRepository;
    private final String eturizamExternalBaseUrl;
    /**
     * Id šifre {@link #CHANGE_REQUEST_TYPE_CODE}; šifrarnik se za rada ne mijenja, pa se čita jednom.
     * Pamti se samo pronađen — dok ga nema, svaki claim pita ponovo i upozorenje ostaje vidljivo.
     */
    private volatile Long changeRequestTypeId;

    public NiasFacilityService(StrFacilityRepository facilityRepository,
                               AccommodationTypeRepository accommodationTypeRepository,
                               RnRepository rnRepository,
                               CategorizationDecisionRepository decisionRepository,
                               @Value("${app.eturizam.external-base-url:}") String eturizamExternalBaseUrl) {
        this.facilityRepository = facilityRepository;
        this.accommodationTypeRepository = accommodationTypeRepository;
        this.rnRepository = rnRepository;
        this.decisionRepository = decisionRepository;
        this.eturizamExternalBaseUrl = eturizamExternalBaseUrl;
    }

    @Transactional(readOnly = true)
    public FacilityPageResponse list(String oib, Integer page, Integer size) {
        int pageIndex = page == null || page < 0 ? 0 : page;
        int pageSize = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);

        List<FacilityResponse> temporary = temporaryDecisions(oib);
        List<String> codes = accommodationTypeRepository.findAllCodes();

        // Prazan popis šifara bi dao IN () — nevažeći SQL. Bez našeg šifrarnika nema ni filtra,
        // pa ostaju samo privremena rješenja. To nije normalno stanje nego neispravno popunjen
        // šifrarnik (changeset 060 popunjava code po nazivu vrste, a naziv se među okolinama
        // razlikuje), i vidi se kao prazan dashboard — zato WARN, ne tiha prazna lista.
        if (codes.isEmpty()) {
            log.warn("accommodation_type nema ni jednu FS_* šifru — popis eTurizam objekata je prazan "
                    + "za sve korisnike; provjeriti str_rn.accommodation_type.code na ovoj okolini");
        }

        // long, pa page=999999999 ne prelije int u negativan OFFSET (Postgres bi na to pao s 500)
        long skip = (long) pageIndex * pageSize;
        List<FacilityListingRow> rows = codes.isEmpty()
                ? List.of()
                : facilityRepository.findListingByOib(oib, codes, pageSize, skip);

        // Ukupno nosi svaki redak stranice; prazna stranica (iza zadnjeg objekta ili bez objekata)
        // ga ne nosi, pa se tek tada pita zasebno.
        long eturizamObjects;
        long eturizamUnits;
        if (!rows.isEmpty()) {
            eturizamObjects = rows.getFirst().getTotalObjects();
            eturizamUnits = rows.getFirst().getTotalUnits();
        } else if (codes.isEmpty()) {
            eturizamObjects = 0;
            eturizamUnits = 0;
        } else {
            ListingTotals totals = facilityRepository.countListingByOib(oib, codes);
            eturizamObjects = totals.getObjects();
            eturizamUnits = totals.getUnits();
        }

        List<FacilityResponse> items = new ArrayList<>(fromEturizam(rows));
        long objectsOnPage = rows.stream().map(FacilityListingRow::getSystemUuid).distinct().count();

        // Privremena rješenja dolaze iza svih eTurizam objekata: na stranicu ulaze tek kad je
        // eTurizam iscrpljen, od pomaka koji preostaje nakon njegovih objekata.
        for (long i = Math.max(skip - eturizamObjects, 0);
             i < temporary.size() && objectsOnPage < pageSize; i++, objectsOnPage++) {
            items.add(temporary.get((int) i));
        }

        return new FacilityPageResponse(items, pageIndex, pageSize,
                eturizamObjects + temporary.size(), eturizamUnits + temporary.size());
    }

    /**
     * Mjerodavni podaci jednog objekta + polja koja se za njega ne smiju mijenjati.
     *
     * <p>Tuđi i nepostojeći objekt daju isti 404: postojanje tuđeg zapisa nije podatak koji
     * ovaj endpoint smije otkriti, a i sam submit bi ga odbio ({@code error.facility.notOwned}).
     * Vlastiti objekt koji ne posluje daje 400 {@code error.facility.inactive}.
     */
    @Transactional(readOnly = true)
    public FacilityClaimResponse claim(String oib, String facilityId) {
        long id;
        try {
            id = Long.parseLong(facilityId.trim());
        } catch (NumberFormatException e) {
            throw new ResourceNotFoundException("facility not found: " + facilityId);
        }
        FacilityOwnershipRow row = facilityRepository.findOwnership(id)
                .filter(r -> oib.equals(r.getOib()))
                .orElseThrow(() -> new ResourceNotFoundException("facility not found: " + facilityId));
        // Nakon provjere vlasništva: vlasniku smije reći da objekt ne posluje, tuđem ne smije
        // otkriti ni da postoji. Bez ovoga bi se forma predpopunila, a submit bi pao na verifieru.
        if (!FacilityClaimVerifier.isActive(row)) {
            throw new BusinessException("error.facility.inactive");
        }

        return new FacilityClaimResponse(
                String.valueOf(id),
                // Ne sirovi facility.name: kad je to popunjivač ili ime vlasnika, objekt zapravo
                // nema naziv. Vratiti ga značilo bi da frontend predpopuni ime osobe u polje
                // „naziv objekta", a polje istovremeno nije na popisu zaključanih.
                FacilityClaimVerifier.objectName(row),
                row.getSubtypeCode(),
                // Maksimalan broj gostiju (kreveti + pomoćni), isti račun kojim verifier
                // zaključava i provjerava `maxBeds`.
                FacilityClaimVerifier.maxGuests(row),
                row.getCountyName(),
                row.getMunicipalityName(),
                row.getSettlementName(),
                row.getStreetName(),
                row.getHouseNumber(),
                row.getPostalCode(),
                row.getContactEmail(),
                row.getContactPhone(),
                FacilityClaimVerifier.lockedFields(row),
                FacilityClaimVerifier.isRegistrableType(row, accommodationTypeRepository),
                changeRequestUrl(row.getDocumentId()),
                row.getVerified());
    }

    /**
     * Adresa eTurizmova obrasca „Zahtjev za promjenu podataka" za ovaj objekt — onamo se korisnik
     * preusmjerava nakon izdavanja RB-a kad kaže da podaci iz registra nisu točni.
     *
     * <p>{@code null} kad je ne možemo složiti (okolina bez eTurizma, zapis bez dokumenta, šifra
     * nije u šifrarniku). Frontend tada ne nudi ni kvačicu, pa korisnik ne može tražiti
     * preusmjeravanje koje se ne bi dogodilo.
     */
    private String changeRequestUrl(Long documentId) {
        if (eturizamExternalBaseUrl == null || eturizamExternalBaseUrl.isBlank() || documentId == null) {
            return null;
        }
        Long requestTypeId = changeRequestTypeId;
        if (requestTypeId == null) {
            requestTypeId = facilityRepository.findCodebookElementId(CHANGE_REQUEST_TYPE_CODE).orElse(null);
            if (requestTypeId == null) {
                log.warn("codebook_element {} nije pronađen — zahtjev za promjenu podataka se ne nudi",
                        CHANGE_REQUEST_TYPE_CODE);
                return null;
            }
            changeRequestTypeId = requestTypeId;
        }
        return changeRequestUrl(eturizamExternalBaseUrl, documentId, requestTypeId);
    }

    static String changeRequestUrl(String baseUrl, long documentId, long requestTypeId) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + "/tu-start/podnesi-novi-zahtjev-za-promjenu-podataka/" + documentId
                + "?idZahtjeva=" + requestTypeId;
    }

    private List<FacilityResponse> fromEturizam(List<FacilityListingRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<String> withdrawn = withdrawnEturizamRns(rows);
        Map<String, String> ownRns = ownRegistrationNumbers(rows, withdrawn);

        List<FacilityResponse> items = new ArrayList<>(rows.size());
        for (FacilityListingRow row : rows) {
            String facilityId = String.valueOf(row.getFacilityId());
            String rn = eturizamRn(row, withdrawn);
            // Kapacitet migriranog objekta s više jedinica je kapacitet objekta, ne jedinice (B-3)
            boolean objectLevel = Boolean.TRUE.equals(row.getObjectLevelCapacity());
            items.add(new FacilityResponse(
                    facilityId,
                    row.getName(),
                    row.getSubtypeCode(),
                    row.getSubtypeName(),
                    row.getCategoryName(),
                    row.getStatusName(),
                    objectLevel ? null : row.getBeds(),
                    objectLevel ? null : row.getAuxiliaryBeds(),
                    row.getCountyName(),
                    row.getMunicipalityName(),
                    row.getSettlementName(),
                    row.getStreetName(),
                    row.getHouseNumber(),
                    row.getPostalCode(),
                    row.getFullAddress(),
                    rn != null ? rn : ownRns.get(facilityId),
                    row.getContactEmail(),
                    row.getContactPhone(),
                    FacilitySource.ETURIZAM,
                    row.getSystemUuid(),
                    row.getVerified(),
                    objectLevel ? row.getBeds() : null,
                    objectLevel ? row.getAuxiliaryBeds() : null));
        }
        return items;
    }

    /**
     * Brojevi iz {@code str.facility} koje je STR povukao. Takav broj eTurizam drži dok ga brisanje
     * pri povlačenju ne ukloni (ili ako ono nije prošlo) — on nije broj objekta, pa objekt mora
     * moći tražiti novi.
     */
    private Set<String> withdrawnEturizamRns(List<FacilityListingRow> rows) {
        List<String> rns = rows.stream()
                .map(r -> blankToNull(r.getRegistrationNumber()))
                .filter(Objects::nonNull)
                .toList();
        return rns.isEmpty() ? Set.of() : Set.copyOf(rnRepository.findWithdrawnRns(rns));
    }

    /** Broj objekta iz eTurizma; {@code null} kad ga nema ili je to broj koji je STR povukao. */
    private static String eturizamRn(FacilityListingRow row, Set<String> withdrawn) {
        String rn = blankToNull(row.getRegistrationNumber());
        return rn == null || withdrawn.contains(rn) ? null : rn;
    }

    /** RB-ovi koje je STR izdao, za slučaj da write-back u {@code str.facility} nije prošao. */
    private Map<String, String> ownRegistrationNumbers(List<FacilityListingRow> rows, Set<String> withdrawn) {
        List<String> ids = rows.stream()
                .filter(r -> eturizamRn(r, withdrawn) == null)
                .map(r -> String.valueOf(r.getFacilityId()))
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> byFacility = new HashMap<>();
        rnRepository.findRnsByFacilityIds(ids)
                .forEach(row -> byFacility.put(row.getFacilityId(), row.getRn()));
        return byFacility;
    }

    private List<FacilityResponse> temporaryDecisions(String oib) {
        return decisionRepository
                .findByLessorOibAndFacilityIdIsNullAndRnIsNullAndStatusNotOrderByUploadedAtDesc(
                        oib, CategorizationDecisionStatus.REJECTED)
                .stream()
                .map(NiasFacilityService::toResponse)
                .toList();
    }

    private static FacilityResponse toResponse(CategorizationDecisionEntity d) {
        String id = d.getDecisionId().toString();
        return new FacilityResponse(
                id,
                d.getObjectName() != null ? d.getObjectName() : d.getFileName(),
                d.getAccommodationTypeCode(),
                null,
                null,
                null,
                d.getMaxBeds(),
                null,
                null, null, null, null, null, null,
                d.getAddressText(),
                null,
                null,
                null,
                FacilitySource.PRIVREMENO_RJESENJE,
                id,
                null,
                null,
                null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
