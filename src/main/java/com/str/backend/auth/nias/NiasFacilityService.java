package com.str.backend.auth.nias;

import com.str.backend.categorization.CategorizationDecisionEntity;
import com.str.backend.categorization.CategorizationDecisionRepository;
import com.str.backend.categorization.CategorizationDecisionStatus;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.rn.RnRepository;
import com.str.backend.str.StrFacilityRepository;
import com.str.backend.str.StrFacilityRepository.FacilityListingRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Popis objekata prijavljenog iznajmljivača, spojen iz dva izvora: eTurizam registra i naših
 * uploadanih skeniranih rješenja koja još nisu upisana u eTurizam.
 *
 * <p>Privremena rješenja idu na početak popisa — čekaju radnju nadležnog tijela, a ima ih malo.
 * Paginacija ih uračunava, pa je {@code total} zbroj obaju izvora i stranica nikad ne vrati više
 * od {@code size} redaka.
 */
@Service
public class NiasFacilityService {

    private static final Logger log = LoggerFactory.getLogger(NiasFacilityService.class);

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final StrFacilityRepository facilityRepository;
    private final AccommodationTypeRepository accommodationTypeRepository;
    private final RnRepository rnRepository;
    private final CategorizationDecisionRepository decisionRepository;

    public NiasFacilityService(StrFacilityRepository facilityRepository,
                               AccommodationTypeRepository accommodationTypeRepository,
                               RnRepository rnRepository,
                               CategorizationDecisionRepository decisionRepository) {
        this.facilityRepository = facilityRepository;
        this.accommodationTypeRepository = accommodationTypeRepository;
        this.rnRepository = rnRepository;
        this.decisionRepository = decisionRepository;
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
        long eturizamTotal = codes.isEmpty() ? 0 : facilityRepository.countListingByOib(oib, codes);

        // long, pa page=999999999 ne prelije int u negativan OFFSET (Postgres bi na to pao s 500)
        long skip = (long) pageIndex * pageSize;
        List<FacilityResponse> items = new ArrayList<>(pageSize);

        for (long i = skip; i < temporary.size() && items.size() < pageSize; i++) {
            items.add(temporary.get((int) i));
        }
        if (items.size() < pageSize && !codes.isEmpty() && skip - temporary.size() <= Integer.MAX_VALUE) {
            int facilityOffset = (int) Math.max(skip - temporary.size(), 0);
            items.addAll(fromEturizam(oib, codes, pageSize - items.size(), facilityOffset));
        }

        return new FacilityPageResponse(items, pageIndex, pageSize, temporary.size() + eturizamTotal);
    }

    private List<FacilityResponse> fromEturizam(String oib, List<String> codes, int limit, int offset) {
        List<FacilityListingRow> rows = facilityRepository.findListingByOib(oib, codes, limit, offset);
        Map<String, String> ownRns = ownRegistrationNumbers(rows);

        List<FacilityResponse> items = new ArrayList<>(rows.size());
        for (FacilityListingRow row : rows) {
            String facilityId = String.valueOf(row.getFacilityId());
            String rn = blankToNull(row.getRegistrationNumber());
            items.add(new FacilityResponse(
                    facilityId,
                    row.getName(),
                    row.getSubtypeCode(),
                    row.getSubtypeName(),
                    row.getCategoryName(),
                    row.getStatusName(),
                    row.getBeds(),
                    row.getAuxiliaryBeds(),
                    row.getCountyName(),
                    row.getMunicipalityName(),
                    row.getSettlementName(),
                    row.getStreetName(),
                    row.getHouseNumber(),
                    row.getPostalCode(),
                    row.getFullAddress(),
                    rn != null ? rn : ownRns.get(facilityId),
                    FacilitySource.ETURIZAM));
        }
        return items;
    }

    /** RB-ovi koje je STR izdao, za slučaj da write-back u {@code str.facility} nije prošao. */
    private Map<String, String> ownRegistrationNumbers(List<FacilityListingRow> rows) {
        List<String> ids = rows.stream()
                .filter(r -> blankToNull(r.getRegistrationNumber()) == null)
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
                .findByLessorOibAndFacilityIdIsNullAndStatusNotOrderByUploadedAtDesc(
                        oib, CategorizationDecisionStatus.REJECTED)
                .stream()
                .map(NiasFacilityService::toResponse)
                .toList();
    }

    private static FacilityResponse toResponse(CategorizationDecisionEntity d) {
        return new FacilityResponse(
                d.getDecisionId().toString(),
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
                FacilitySource.PRIVREMENO_RJESENJE);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
