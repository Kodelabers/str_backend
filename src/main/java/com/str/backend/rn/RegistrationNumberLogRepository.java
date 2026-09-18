package com.str.backend.rn;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RegistrationNumberLogRepository extends JpaRepository<RegistrationNumberLogEntity, UUID> {

    List<RegistrationNumberLogEntity> findByRnOrderByOccurredAtAsc(String rn);

    /**
     * Zadnji prijelaz statusa — odatle akti uzimaju razlog. Revizijski trag je pouzdaniji izvor
     * od {@code ?reason=} parametra na zahtjevu, koji nitko ne provjerava.
     */
    Optional<RegistrationNumberLogEntity> findFirstByRnOrderByOccurredAtDesc(String rn);

    /**
     * Zadnji prijelaz u zadani status. Suspenzija je dvofazna, pa materijalni razlog (istek
     * suglasnosti, nalaz nadzora…) stoji na prijelazu u {@code SUSPENSION_PROPOSED}, a ne na
     * zadnjem prijelazu — taj nosi samo procesni okidač (istek roka, obustava).
     */
    Optional<RegistrationNumberLogEntity> findFirstByRnAndToStatusOrderByOccurredAtDesc(
            String rn, String toStatus);
}
