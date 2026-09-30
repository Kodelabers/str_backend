package com.str.backend.categorization;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

/**
 * Multipart tijelo predaje rješenja o kategorizaciji: sken i RB uz koji se predaje.
 *
 * <p>Metapodatke objekta korisnik više ne upisuje — objekt je već opisan u zahtjevu za RB, pa
 * ih servis prepisuje iz smještaja tog RB-a (v. {@link CategorizationDecisionService}).
 */
@Getter
@Setter
public class CategorizationDecisionRequest {

    @NotNull
    private MultipartFile datoteka;

    @NotBlank
    @Size(max = 20)
    private String registrationNumber;
}
