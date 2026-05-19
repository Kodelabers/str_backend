package com.str.backend.registration.dto;

import java.util.UUID;

public record RegistrationResponse(String registrationNumber, UUID submissionId) {}
