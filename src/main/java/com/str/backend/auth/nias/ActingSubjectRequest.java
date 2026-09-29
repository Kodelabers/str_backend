package com.str.backend.auth.nias;

import jakarta.validation.constraints.NotBlank;

/** OIB tvrtke u čije ime korisnik želi djelovati — samo prijedlog, provjerava ga e-Ovlaštenja. */
public record ActingSubjectRequest(@NotBlank String oib) {
}
