package com.str.backend.kp.api;

import java.util.List;
import java.util.UUID;

/** Poruke su zabilježene u transakciji radnje; {@link KpPorukaListener} ih nakon commita šalje. */
record KpPorukeZabiljezene(List<UUID> ids) {
}
