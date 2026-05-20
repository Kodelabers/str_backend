package com.str.backend.lessor;

import java.util.UUID;

// TODO: if response-body logging is ever enabled (Actuator HttpExchangeRepository,
//       request interceptors), ensure this record is excluded — temporaryPassword
//       is plaintext and shown to the user exactly once.
public record LessorRegistrationResponse(
        UUID lessorId,
        String username,
        String temporaryPassword
) {}
