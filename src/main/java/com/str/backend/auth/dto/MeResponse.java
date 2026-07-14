package com.str.backend.auth.dto;

import java.util.UUID;

/**
 * Unificirani "who am I" odgovor za sve tokove prijave.
 * <ul>
 *   <li>{@code authType = "NIAS"} — identitet iz NIAS SAML assertiona ({@code lessorId}/{@code username} su null).</li>
 *   <li>{@code authType = "LOCAL"} — non-EU username/password korisnik iz {@code LessorEntity}.</li>
 * </ul>
 */
public record MeResponse(
        String authType,
        String oib,
        UUID lessorId,
        String username,
        String firstName,
        String lastName,
        String role,
        String email
) {}
