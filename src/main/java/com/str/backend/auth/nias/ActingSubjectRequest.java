package com.str.backend.auth.nias;

import jakarta.validation.constraints.NotBlank;

/**
 * OIB subjekta u čije ime korisnik želi djelovati — samo prijedlog, provjerava ga e-Ovlaštenja.
 *
 * @param izvorReg registar subjekta iz popisa ({@code ActingSubjectOption.izvorReg}: 1 tvrtka, 2 obrt,
 *                 3 OPG); bez njega tvrtka, kao kod upisa OIB-a. Nije potpisan — potpisani odgovor
 *                 e-Ovlaštenja mora navoditi isti subjekt u istom registru.
 */
public record ActingSubjectRequest(@NotBlank String oib, String izvorReg) {
}
