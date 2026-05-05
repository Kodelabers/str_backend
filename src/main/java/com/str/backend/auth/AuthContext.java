package com.str.backend.auth;

public interface AuthContext {

    AuthenticatedUser currentUser();

    record AuthenticatedUser(String oib, String firstName, String lastName, String email) {
    }
}
