package com.str.backend.prefill;

import com.str.backend.address.HouseNumberRepository;
import com.str.backend.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class RegistrationNumberPrefillService {

    private final RegistrationNumberPrefillRepository repository;
    private final HouseNumberRepository houseNumberRepository;

    public RegistrationNumberPrefillService(RegistrationNumberPrefillRepository repository,
                                            HouseNumberRepository houseNumberRepository) {
        this.repository = repository;
        this.houseNumberRepository = houseNumberRepository;
    }

    @Transactional
    public UUID store(String oib,
                      String firstName,
                      String lastName,
                      String addressCode,
                      Integer maxBedCount,
                      Integer maxGuestCount) {
        RegistrationNumberPrefillEntity saved = repository.save(
                RegistrationNumberPrefillEntity.create(oib, firstName, lastName, addressCode, maxBedCount, maxGuestCount));
        return saved.getPrefillId();
    }

    @Transactional(readOnly = true)
    public RegistrationNumberPrefillResponse resolve(UUID prefillId) {
        RegistrationNumberPrefillEntity e = repository.findById(prefillId)
                .orElseThrow(() -> new ResourceNotFoundException("Prefill payload not found"));

        String county = null;
        String municipality = null;
        String settlement = null;
        String street = null;
        String streetNumber = null;

        if (e.getAddressCode() != null) {
            HouseNumberRepository.FullAddressProjection addr = houseNumberRepository
                    .resolveAddressHierarchy(e.getAddressCode())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Adresa za zadanu šifru adrese nije pronađena"));
            county = addr.getCounty();
            municipality = addr.getMunicipality();
            settlement = addr.getSettlement();
            street = addr.getStreet();
            streetNumber = addr.getStreetNumber();
        }

        return new RegistrationNumberPrefillResponse(
                e.getOib(),
                e.getFirstName(),
                e.getLastName(),
                e.getMaxBedCount(),
                e.getMaxGuestCount(),
                county,
                municipality,
                settlement,
                street,
                streetNumber);
    }
}
