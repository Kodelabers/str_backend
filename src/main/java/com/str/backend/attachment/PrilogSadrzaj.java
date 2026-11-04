package com.str.backend.attachment;

/** Prilog spreman za preuzimanje ili za privitak e-pošte. */
public record PrilogSadrzaj(String naziv, String contentType, byte[] sadrzaj) {
}
