package com.str.backend.objection;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Čitanje prigovora za eGOP tok, u <b>vlastitoj kratkoj transakciji</b> — kao {@code RnLifecycleLookup}:
 * pozivatelji rade nakon commita ili iz retry joba i između čitanja zovu eGOP, pa ne smiju držati
 * transakciju, a u {@code AFTER_COMMIT} fazi zadani {@code REQUIRED} pokušao bi nastaviti završenu.
 */
@Component
public class SuspensionObjectionLookup {

    private final SuspensionObjectionRepository repository;

    public SuspensionObjectionLookup(SuspensionObjectionRepository repository) {
        this.repository = repository;
    }

    /** Prigovor s dokumentom ({@code file_content}); dokument je nepromjenjiv, pa svaki pokušaj šalje iste bajtove. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<SuspensionObjectionEntity> find(UUID objectionId) {
        return repository.findById(objectionId);
    }
}
