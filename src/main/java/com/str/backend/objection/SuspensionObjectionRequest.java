package com.str.backend.objection;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

/** Multipart tijelo predaje prigovora: dokument prigovora i RB na koji se odnosi. */
@Getter
@Setter
public class SuspensionObjectionRequest {

    @NotNull
    private MultipartFile datoteka;

    @NotBlank
    @Size(max = 20)
    private String registrationNumber;
}
