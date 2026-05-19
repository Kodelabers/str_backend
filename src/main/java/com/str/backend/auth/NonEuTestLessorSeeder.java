package com.str.backend.auth;

import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile({"local", "mock", "dev"})
public class NonEuTestLessorSeeder implements CommandLineRunner {

    private static final Logger LOG = LoggerFactory.getLogger(NonEuTestLessorSeeder.class);

    private final LessorRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;

    public NonEuTestLessorSeeder(LessorRepository repository,
                                 PasswordEncoder passwordEncoder,
                                 @Value("${app.auth.test-non-eu-lessor.username:testni.iznajmljivac}") String username,
                                 @Value("${app.auth.test-non-eu-lessor.password:Test123!}") String password) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (repository.findByUsername(username).isPresent()) {
            return;
        }
        LessorEntity e = LessorEntity.createNonEu(
                "John", "Doe",
                "5th Avenue", "742",
                "New York", "NY",
                "john.doe@example.com",
                username,
                passwordEncoder.encode(password));
        repository.save(e);
        LOG.info("Seeded non-EU test lessor: username='{}' (lessorId={})", username, e.getLessorId());
    }
}
