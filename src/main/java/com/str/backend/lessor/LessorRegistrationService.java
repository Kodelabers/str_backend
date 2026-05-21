package com.str.backend.lessor;

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

    public LessorRegistrationService(LessorRepository lessorRepository,
                                     LessorDocumentRepository lessorDocumentRepository,
                                     PasswordEncoder passwordEncoder) {
        this.lessorRepository = lessorRepository;
        this.lessorDocumentRepository = lessorDocumentRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public LessorRegistrationResponse register(LessorRegistrationRequest req) throws IOException {
        if (!req.getPassword().equals(req.getPasswordPotvrda())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lessor.password.mismatch");
        }
        if (req.getIspravaPrednja().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lessor.document.empty");
        }

        String username = req.getEmail().trim().toLowerCase();
        if (lessorRepository.findByEmail(username).isPresent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lessor.registration.invalid");
        }

        String hash = passwordEncoder.encode(req.getPassword());

        LessorEntity lessor = LessorEntity.createNonEuRegistration(
                req.getIme(), req.getPrezime(),
                req.getStalnaAdresa(), username,
                username, hash,
                req.getDatumRodjenja(), req.getZemljaPrebivalistaId(),
                req.getPorezniBroj(), req.getTelefon()
        );
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
}
