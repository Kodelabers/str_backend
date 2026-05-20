package com.str.backend.lessor;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.UUID;

@Service
@Transactional
public class LessorRegistrationService {

    private static final String PASSWORD_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int PASSWORD_LENGTH = 12;
    private static final SecureRandom RNG = new SecureRandom();

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
        if (lessorRepository.findByEmail(req.getEmail()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email već registriran");
        }
        if (req.getIspravaPrednja().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Prednja slika isprave je prazna");
        }

        String username = generateUniqueUsername(req.getEmail());
        String plaintextPassword = generateRandomPassword();
        String hash = passwordEncoder.encode(plaintextPassword);

        LessorEntity lessor = LessorEntity.createNonEuRegistration(
                req.getIme(), req.getPrezime(),
                req.getStalnaAdresa(), req.getEmail(),
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

        return new LessorRegistrationResponse(lessor.getLessorId(), username, plaintextPassword);
    }

    private String generateUniqueUsername(String email) {
        String base = email.split("@")[0]
                .toLowerCase()
                .replaceAll("[^a-z0-9._-]", "");
        if (base.isBlank()) {
            base = "user";
        }
        // Append 8 random hex chars to eliminate the check-then-act race against the
        // unique constraint on username. Sequential suffix loops are not safe under
        // concurrent registration requests.
        String randomSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return base + "_" + randomSuffix;
    }

    private String generateRandomPassword() {
        StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            sb.append(PASSWORD_CHARS.charAt(RNG.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }
}
