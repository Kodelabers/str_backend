package com.str.backend.auth;

import org.springframework.stereotype.Component;

/**
 * Mock auth — placeholder until NIAS/eIDAS integration lands.
 * Returns a fixed iznajmljivač that exists in str.subject (jips=12312312316, PERO PERIĆ).
 */
@Component
public class MockAuthContext implements AuthContext {

    @Override
    public AuthenticatedUser currentUser() {
        return new AuthenticatedUser("12312312316", "PERO", "PERIĆ", "pero.peric@example.hr");
    }
}
