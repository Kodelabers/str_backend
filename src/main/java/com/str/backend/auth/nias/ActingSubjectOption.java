package com.str.backend.auth.nias;

/**
 * Tvrtka u izborniku „Djelujem u ime" ({@code GET /api/nias/acting-subject/options}). Samo prijedlog:
 * odabir ide kroz {@code POST /api/nias/acting-subject} s potpisanom provjerom e-Ovlaštenja.
 *
 * @param naziv    naziv iz e-Ovlaštenja; može biti {@code null}, tada se prikazuje OIB
 * @param izvorReg registar subjekta (1 tvrtka, 2 obrt, 3 OPG) — vraća se uz odabir
 */
public record ActingSubjectOption(String oib, String naziv, String izvorReg) {
}
