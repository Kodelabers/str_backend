package com.str.backend.prefill;

import com.str.backend.address.HouseNumberRepository;
import com.str.backend.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class RegistrationPrefillService {

    private final RegistrationPrefillRepository repository;
    private final HouseNumberRepository houseNumberRepository;

    public RegistrationPrefillService(RegistrationPrefillRepository repository,
                                      HouseNumberRepository houseNumberRepository) {
        this.repository = repository;
        this.houseNumberRepository = houseNumberRepository;
    }

    @Transactional
    public UUID store(String oib,
                      String ime,
                      String prezime,
                      Long kucniBrojSifra,
                      Integer brojKreveta,
                      Integer brojGostiju) {
        RegistrationPrefillEntity saved = repository.save(
                RegistrationPrefillEntity.create(oib, ime, prezime, kucniBrojSifra, brojKreveta, brojGostiju));
        return saved.getPrefillId();
    }

    @Transactional(readOnly = true)
    public RegistrationPrefillResponse resolve(UUID prefillId) {
        RegistrationPrefillEntity e = repository.findById(prefillId)
                .orElseThrow(() -> new ResourceNotFoundException("Prefill payload not found"));

        String zupanija = null;
        String opcina = null;
        String naselje = null;
        String ulica = null;
        String kucniBroj = null;

        if (e.getKucniBrojSifra() != null) {
            HouseNumberRepository.FullAddressProjection addr = houseNumberRepository
                    .resolveAddressHierarchy(e.getKucniBrojSifra())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Adresa za zadanu šifru kućnog broja nije pronađena"));
            zupanija = addr.getCounty();
            opcina = addr.getMunicipality();
            naselje = addr.getSettlement();
            ulica = addr.getStreet();
            kucniBroj = addr.getStreetNumber();
        }

        return new RegistrationPrefillResponse(
                e.getOib(),
                e.getIme(),
                e.getPrezime(),
                e.getBrojKreveta(),
                e.getBrojGostiju(),
                zupanija,
                opcina,
                naselje,
                ulica,
                kucniBroj);
    }
}
