package com.str.backend.email;

/** Prilog akta u privitku obavijesti o promjeni statusa RB-a (T17). */
public record MailPrilog(String naziv, String contentType, byte[] sadrzaj) {
}
