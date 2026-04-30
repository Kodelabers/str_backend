package com.str.backend.registration.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LessorRequest {

    // --- Basic info ---
    @NotBlank @Size(max = 128)
    private String firstName;

    @NotBlank @Size(max = 128)
    private String lastName;

    @NotBlank @Email
    private String email;

    // --- Lessor address ---
    @NotBlank @Size(max = 128)
    private String street;

    @NotBlank @Size(max = 16)
    private String streetNumber;

    @NotBlank @Size(max = 128)
    private String place;

    @NotBlank @Size(max = 128)
    private String county;

    // --- Contact ---
    private String contactName;

    @Size(max = 32)
    private String phoneNumber;

    @Size(max = 32)
    private String mobileNumber;

    @Size(max = 1024)
    private String contactNote;

    // --- Legal entity ---
    @Size(max = 11)
    private String representativeOib;

    @Size(max = 255)
    private String legalEntityName;

    private String legalRepresentativeName;

    @Email
    private String representativeEmail;

    @Size(max = 32)
    private String representativePhone;

    @Size(max = 255)
    private String representativeAddress;

    // --- Internal ---
    @Size(max = 64)
    private String officialPersonId;
}
