package com.str.backend.auth.nias;

import java.util.List;

/**
 * Mjerodavni podaci jednog eTurizam objekta za predpopunu forme zahtjeva za RB, uz popis polja
 * koja se za taj objekt ne smiju mijenjati.
 *
 * <p>Postoji zato što tuStart handoff podatke šalje kroz query string, koji korisnik može
 * urediti prije nego forma ode na submit. Frontend zato ne smije zaključavati polja po tome
 * što je stiglo u URL-u, nego po ovom odgovoru — {@code zakljucanaPolja} je izračunato istom
 * logikom kojom {@code FacilityClaimVerifier} odbija zahtjev, pa se to dvoje ne može razići.
 *
 * <p>Popis nosi <b>nazive polja iz tijela</b> {@code POST /api/generateRegistrationNumber}
 * (npr. {@code typeId}, {@code maxBeds}), da ih frontend može izravno preslikati na svoje inpute.
 * Polje koje eTurizam ne zna (prazna ulica, naziv {@code -}) namjerno <b>nije</b> na popisu.
 * Obrazac ga za postojeći objekt ostavlja prazno i neobavezno, a backend ga pri izdavanju RB-a
 * sam dopunjava iz eTurizma kad god može.
 *
 * <p>{@code brKreveta} je <b>maksimalan broj gostiju</b> — kreveti + pomoćni kreveti iz eTurizma
 * (v. {@code FacilityClaimVerifier.maxGuests}). Ime polja ostaje zbog ugovora s frontendom.
 */
public record FacilityClaimResponse(
        String id,
        String naziv,
        String vrstaSifra,
        Integer brKreveta,
        String zupanijaNaziv,
        String opcinaNaziv,
        String naseljeNaziv,
        String ulicaNaziv,
        String kucniBrojNaziv,
        String postanskiBroj,
        /**
         * Kontakt objekta iz eTurizma — predpopuna kontakt bloka (stavke 10-11 sa sastanka
         * 10.09.2026.). Namjerno <b>nije</b> u {@code zakljucanaPolja}: kontakt je promjenjiv
         * podatak, ne identitet objekta, pa zastarjeli e-mail mora biti ispravljiv.
         */
        String kontaktEmail,
        String kontaktTelefon,
        List<String> zakljucanaPolja,
        /**
         * Smije li objekt te vrste uopće dobiti RB ({@code FacilityClaimVerifier#isRegistrableType}).
         * {@code false} za vrstu koja nije privatni smještaj (hotel, kamp, restoran, agencija…) —
         * popis objekata takve ne prikazuje, ali do njih se može doći tuStart URL-om. Frontend
         * tada blokira obrazac, a predaju bi ionako odbio verifier.
         */
        boolean vrstaDopustena,
        /**
         * Adresa eTurizmova obrasca „Zahtjev za promjenu podataka" za ovaj objekt
         * ({@code facility.document_id} + id šifre {@code DST_Z_PROMJ_POD}), na okolini iz
         * {@code app.eturizam.external-base-url}. {@code null} kad se ne može složiti — frontend
         * tada ne nudi kvačicu „podaci iz registra nisu točni".
         */
        String zahtjevPromjenaUrl) {
}
