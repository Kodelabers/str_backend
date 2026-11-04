package com.str.backend.objection;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Multipart tijelo predaje prigovora: dokument prigovora, dodatne datoteke i RB na koji se odnosi.
 *
 * <p>{@code datoteka} je dokument prigovora (PDF ili Word) i ostaje u
 * {@code suspension_objection.file_content}, kao i prije. {@code datoteke} (ponovljeni dio
 * {@code datoteke}, neobavezno) su ostale datoteke prigovora (T17): PDF, JPG, PNG, DOC ili DOCX, do
 * ukupno 5 datoteka s dokumentom; spremaju se u {@code pismeno_prilog} kao {@code OBJECTION}.
 */
@Getter
@Setter
public class SuspensionObjectionRequest {

    @NotNull
    private MultipartFile datoteka;

    private List<MultipartFile> datoteke;

    @NotBlank
    @Size(max = 20)
    private String registrationNumber;
}
