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
                ? countyRepository.findByActiveTrueOrderByName()
                : countyRepository.findByActiveTrueAndNameContainingIgnoreCaseOrderByName(q);
        return entities.stream()
                .map(e -> new CountyResponse(e.getId(), e.getName()))
                .toList();
    }

    public List<MunicipalityResponse> findMunicipalities(Long countyId, String q) {
        List<MunicipalityEntity> entities = (q == null || q.isBlank())
                ? municipalityRepository.findByActiveTrueAndCountyIdOrderByName(countyId)
                : municipalityRepository.findByActiveTrueAndCountyIdAndNameContainingIgnoreCaseOrderByName(countyId, q);
        return entities.stream()
                .map(e -> new MunicipalityResponse(e.getId(), e.getName(), e.getTypeCode()))
                .toList();
    }

    public List<SettlementResponse> findSettlements(Long municipalityId, String q) {
        List<SettlementEntity> entities = (q == null || q.isBlank())
                ? settlementRepository.findByActiveTrueAndMunicipalityIdOrderByName(municipalityId)
                : settlementRepository.findByActiveTrueAndMunicipalityIdAndNameContainingIgnoreCaseOrderByName(municipalityId, q);
        return entities.stream()
                .map(e -> new SettlementResponse(e.getId(), e.getName(), e.getPostalCode()))
                .toList();
    }

    public List<StreetResponse> findStreets(Long settlementId, String q) {
        List<StreetEntity> entities = (q == null || q.isBlank())
                ? streetRepository.findByActiveTrueAndSettlementIdOrderByName(settlementId)
                : streetRepository.findByActiveTrueAndSettlementIdAndNameContainingIgnoreCaseOrderByName(settlementId, q);
        return entities.stream()
                .map(e -> new StreetResponse(e.getId(), e.getName(), e.getTypeCode()))
                .toList();
    }

    public List<HouseNumberResponse> findHouseNumbers(Long streetId, String q) {
        List<HouseNumberEntity> entities = (q == null || q.isBlank())
                ? houseNumberRepository.findByActiveTrueAndStreetIdOrderByName(streetId)
                : houseNumberRepository.findByActiveTrueAndStreetIdAndNameContainingIgnoreCaseOrderByName(streetId, q);
        return entities.stream()
                .map(e -> new HouseNumberResponse(e.getId(), e.getName()))
                .toList();
    }
}
