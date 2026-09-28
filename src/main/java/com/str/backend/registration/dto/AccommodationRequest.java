package com.str.backend.registration.dto;

import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;

import java.time.LocalDate;

public interface AccommodationRequest {
    String name();
    String typeId();
    Long countyId();
    String cityId();
    String settlementId();
    String street();
    String streetNumber();
    /**
     * Id odabranog kućnog broja ({@code eturizam_test.ar_address.id}) iz
     * {@code GET /api/address/house-numbers}. Iz njega backend sam čita katastar, pa katastarska
     * općina i registarska šifra ne dolaze iz zahtjeva — v. {@code CadastreResolver}.
     * Prazno kad adresa nije odabrana iz registra.
     */
    Long kucniBrojId();
    String postalCode();
    int maxBeds();
    OfferType offerType();
    Offering offering();
    Boolean building();
    String floor();
    Boolean apartments();
    Boolean legalized();
    Boolean lessorResidence();
    Boolean coOwnerConsent();
    LocalDate consentDate();
    LocalDate consentWithdrawalDate();
    Boolean host();

    /** ID smještajnog objekta (unita) u eTurizmu; frontend ga vraća iz URL handoffa u submit. */
    String facilityId();

    /**
     * Kontakt iznajmljivača. Traženo 10.09.2026. (stavke 11-12): kontakt je obvezan, za postojeći
     * objekt se predpopuni iz eTurizma, a za novog subjekta se mora upisati i spremiti — dotad je
     * {@code lessor.email} na NIAS putu ostajao prazan, pa je kontakt blok u PDF-u bio prazan i
     * obavijest se nije imala kamo poslati.
     */
    String kontaktEmail();
    String kontaktMobitel();
    String kontaktTelefon();
    String kontaktOsoba();

    /**
     * Broj katastarske čestice koji je korisnik upisao. Uzima se <b>samo</b> kad ga registar za
     * odabrani kućni broj nema (na CDU 28,7 % adresa); kad ga ima, vrijedi registar i drukčija
     * vrijednost daje 400 — v. {@code CadastreResolver}.
     */
    String kcBroj();

    /**
     * Broj pomoćnih kreveta (kauč, rasklopni krevet i sl.) — <b>informativno</b>. Već je sadržan u
     * {@link #maxBeds()}, koji od stavke 2 nosi maksimalan broj gostiju (kreveti + pomoćni), pa se
     * ne dodaje ni u {@code max_beds} ni u {@code max_guests}. Neobavezno.
     */
    Integer auxiliaryBeds();

    /**
     * Kategorija koju je iznajmljivač ručno upisao pri izdavanju RB-a; STR je nema. Sprema se
     * kao tražena kategorija ({@code accommodation.requested_category}, VARCHAR(32)).
     */
    String kategorija();
}
