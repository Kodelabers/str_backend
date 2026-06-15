package com.str.backend.lessor;

import com.str.backend.address.CountryEntity;
import com.str.backend.address.CountryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

@Service
@Transactional
public class LessorRegistrationService {

    private final LessorRepository lessorRepository;
    private final LessorDocumentRepository lessorDocumentRepository;
    private final PasswordEncoder passwordEncoder;
    private final CountryRepository countryRepository;

    public LessorRegistrationService(LessorRepository lessorRepository,
                                     LessorDocumentRepository lessorDocumentRepository,
                                     PasswordEncoder passwordEncoder,
                                     CountryRepository countryRepository) {
        this.lessorRepository = lessorRepository;
        this.lessorDocumentRepository = lessorDocumentRepository;
        this.passwordEncoder = passwordEncoder;
        this.countryRepository = countryRepository;
    }

    public LessorRegistrationResponse register(LessorRegistrationRequest req) throws IOException {
        if (!req.getPassword().equals(req.getPasswordPotvrda())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lessor.password.mismatch");
        }
        if (req.getIspravaPrednja().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lessor.document.empty");
        }
        boolean hasBack = req.getIspravaStraznja() != null && !req.getIspravaStraznja().isEmpty();
        if ("PASSPORT".equals(req.getVrstaIsprave()) && hasBack) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "lessor.document.passport.noBack");
        }
        if ("ID_CARD".equals(req.getVrstaIsprave()) && !hasBack) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "lessor.document.idcard.backRequired");
        }

        String username = req.getEmail().trim().toLowerCase();
        if (lessorRepository.findByEmail(username).isPresent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lessor.registration.invalid");
        }

        requireActiveCountry(req.getZemljaPrebivalistaId(), "lessor.country.invalid");

        String hash = passwordEncoder.encode(req.getPassword());

        LessorEntity lessor = LessorEntity.createNonEuRegistration(
                req.getIme(), req.getPrezime(),
                req.getStalnaAdresa(), username,
                username, hash,
                req.getDatumRodjenja(), req.getZemljaPrebivalistaId(),
                req.getPorezniBroj(), req.getTelefon()
        );
        if (req.isVlasnikJePravnaOsoba()) {
            if (isBlank(req.getNazivPravneOsobe()) || req.getDrzavaSjedistaId() == null
                    || isBlank(req.getGradSjedista()) || isBlank(req.getMaticniBrojPravneOsobe())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "lessor.legalEntity.incomplete");
            }
            requireActiveCountry(req.getDrzavaSjedistaId(), "lessor.legalEntity.country.invalid");
            lessor.applyLegalEntityOwner(
                    req.getNazivPravneOsobe().trim(), req.getDrzavaSjedistaId(),
                    req.getGradSjedista().trim(), req.getMaticniBrojPravneOsobe().trim());
        }
        lessorRepository.save(lessor);

        byte[] back = req.getIspravaStraznja() != null && !req.getIspravaStraznja().isEmpty()
                ? req.getIspravaStraznja().getBytes() : null;
        LessorDocumentEntity doc = LessorDocumentEntity.create(
                lessor.getLessorId(),
                req.getVrstaIsprave(), req.getBrojIsprave(),
                req.getIspravaPrednja().getBytes(), back
        );
        lessorDocumentRepository.save(doc);

        return new LessorRegistrationResponse(lessor.getLessorId(), username);
    }

    private void requireActiveCountry(Integer countryId, String errorKey) {
        boolean valid = countryId != null
                && countryRepository.findById(countryId.longValue())
                        .filter(CountryEntity::isActive)
                        .isPresent();
        if (!valid) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, errorKey);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
