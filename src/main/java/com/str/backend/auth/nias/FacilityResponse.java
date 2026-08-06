package com.str.backend.auth.nias;

/**
 * Jedan red na popisu objekata NIAS korisnika.
 *
 * <p>Objekti dolaze iz dva izvora: eTurizam registra ({@code str.facility}) i naših uploadanih
 * skeniranih rješenja koja još nisu upisana u eTurizam ({@link FacilitySource#PRIVREMENO_RJESENJE}).
 * Drugi izvor nosi samo ono što je korisnik uz sken unio, pa su mu većina polja prazna.
 *
 * <p>{@code registracijskiBroj} je {@code null} kad objekt nema RB — tada frontend nudi
 * „Zatraži RB", inače „Prikaži".
 */
public record FacilityResponse(
        String id,
        String naziv,
        String vrstaSifra,
        String vrstaNaziv,
        String kategorija,
        String statusNaziv,
        Integer brKreveta,
        Integer brPomocnihKreveta,
        String zupanijaNaziv,
        String opcinaNaziv,
        String naseljeNaziv,
        String ulicaNaziv,
        String kucniBrojNaziv,
        String postanskiBroj,
        String punaAdresa,
        String registracijskiBroj,
        FacilitySource izvor
) {}
