package com.str.backend.iznajmljivac;

import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.iznajmljivac.dto.CreateIznajmljivacRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class IznajmljivacService {

    private static final Logger log = LoggerFactory.getLogger(IznajmljivacService.class);

    private final IznajmljivacRepository repository;

    public IznajmljivacService(IznajmljivacRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public IznajmljivacEntity register(CreateIznajmljivacRequest req) {
        if (req.korisnickoIme() != null && repository.existsByKorisnickoIme(req.korisnickoIme())) {
            throw new BusinessException("korisnicko ime already taken: " + req.korisnickoIme());
        }

        IznajmljivacEntity e = IznajmljivacEntity.create(
                req.ime(), req.prezime(), req.ulica(), req.kucniBroj(),
                req.mjesto(), req.zupanija(), req.email());

        if (req.oibZastupnika() != null || req.nazivPravneOsobe() != null) {
            e.setLegalEntity(req.oibZastupnika(), req.nazivPravneOsobe(), null, null, null);
        }
        if (req.imeKontakta() != null || req.brojTelefona() != null || req.brojMobitela() != null) {
            e.setContact(req.imeKontakta(), req.brojTelefona(), req.brojMobitela());
        }
        if (req.korisnickoIme() != null) {
            e.setCredentials(req.korisnickoIme(), null);
        }

        repository.save(e);
        log.info("iznajmljivac_registered id={} email={}", e.getIdIznajmljivaca(), e.getEmail());
        return e;
    }

    @Transactional(readOnly = true)
    public IznajmljivacEntity dohvati(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("iznajmljivac not found: " + id));
    }
}
