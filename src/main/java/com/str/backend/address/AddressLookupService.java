package com.str.backend.address;

import com.str.backend.address.dto.CountryResponse;
import com.str.backend.address.dto.CountyResponse;
import com.str.backend.address.dto.HouseNumberResponse;
import com.str.backend.address.dto.MunicipalityResponse;
import com.str.backend.address.dto.SettlementResponse;
import com.str.backend.address.dto.StreetResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class AddressLookupService {

    private final CountryRepository countryRepository;
    private final CountyRepository countyRepository;
    private final MunicipalityRepository municipalityRepository;
    private final SettlementRepository settlementRepository;
    private final StreetRepository streetRepository;
    private final HouseNumberRepository houseNumberRepository;

    public AddressLookupService(CountryRepository countryRepository,
                                CountyRepository countyRepository,
                                MunicipalityRepository municipalityRepository,
                                SettlementRepository settlementRepository,
                                StreetRepository streetRepository,
                                HouseNumberRepository houseNumberRepository) {
        this.countryRepository = countryRepository;
        this.countyRepository = countyRepository;
        this.municipalityRepository = municipalityRepository;
        this.settlementRepository = settlementRepository;
        this.streetRepository = streetRepository;
        this.houseNumberRepository = houseNumberRepository;
    }

    public List<CountryResponse> findCountries(String q) {
        List<CountryEntity> entities = (q == null || q.isBlank())
                ? countryRepository.findByActiveTrueOrderByName()
                : countryRepository.findByActiveTrueAndNameContainingIgnoreCaseOrderByName(q);
        return entities.stream()
                .map(e -> new CountryResponse(e.getId(), e.getName(), e.getIso2Alpha()))
                .toList();
    }

    public List<CountyResponse> findCounties(String q) {
        List<CountyEntity> entities = (q == null || q.isBlank())
                ? countyRepository.findAllByOrderByZuRb()
                : countyRepository.findByNameContainingIgnoreCaseOrderByZuRb(q);
        return entities.stream()
                .map(e -> new CountyResponse(e.getId(), e.getName()))
                .toList();
    }

    public List<MunicipalityResponse> findMunicipalities(Long countyId, String q) {
        return municipalityRepository.findByCountyIdOrderByName(countyId, normalize(q)).stream()
                .map(e -> new MunicipalityResponse(e.getId(), e.getName(), e.getTypeCode()))
                .toList();
    }

    public List<SettlementResponse> findSettlements(Long municipalityId, String q) {
        String filter = (q == null || q.isBlank()) ? null : q;
        return settlementRepository.findByMunicipalityIdOrderByName(municipalityId, filter).stream()
                .map(p -> new SettlementResponse(p.getId(), p.getName(), p.getPostalCode()))
                .toList();
    }

    public List<StreetResponse> findStreets(Long settlementId, String q) {
        return streetRepository.findBySettlementIdOrderByName(settlementId, normalize(q)).stream()
                .map(e -> new StreetResponse(e.getId(), e.getName(), e.getTypeCode()))
                .toList();
    }

    public List<HouseNumberResponse> findHouseNumbers(Long streetId, String q) {
        return houseNumberRepository.findByStreetIdOrderByName(streetId, normalize(q)).stream()
                .map(e -> new HouseNumberResponse(e.getId(), e.getName()))
                .toList();
    }

    // Postgres can't infer a type for a null JPQL parameter inside LOWER(CONCAT(..., :q, ...)),
    // and binds it as bytea — so JPQL queries that branch on `:q = ''` get an empty sentinel
    // instead of null. Native queries (findSettlements) handle this with CAST(:q AS text).
    private static String normalize(String q) {
        return (q == null || q.isBlank()) ? "" : q;
    }
}
