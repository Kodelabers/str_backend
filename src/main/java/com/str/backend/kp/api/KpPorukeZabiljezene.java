package com.str.backend.kp.api;

import java.util.List;
import java.util.UUID;

/**
 * Poruke su zabilježene u transakciji radnje službenika. {@link KpPorukaListener} ih nakon commita šalje
 * njegovom sesijom. {@code sessionId} se ne logira — to je pristup korisnikovoj sesiji.
 */
record KpPorukeZabiljezene(List<UUID> ids, String sessionId) {

    @Override
    public String toString() {
        return "KpPorukeZabiljezene[ids=" + ids + "]";
    }
}
