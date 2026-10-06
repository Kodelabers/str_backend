package com.str.backend.auth.nias;

/**
 * Jedan red na popisu objekata NIAS korisnika — jedna smještajna jedinica.
 *
 * <p>Objekti dolaze iz dva izvora: eTurizam registra ({@code str.facility}) i naših uploadanih
 * skeniranih rješenja koja još nisu upisana u eTurizam ({@link FacilitySource#PRIVREMENO_RJESENJE}).
 * Drugi izvor nosi samo ono što je korisnik uz sken unio, pa su mu većina polja prazna.
 *
 * <p>U eTurizmu je zapis {@code str.facility} smještajna jedinica, a više jedinica čini objekt
 * ({@code system_uuid}). Redovi istog objekta dolaze uzastopno i dijele {@code objektId}, pa ih
 * frontend grupira; paginacija i {@code total} broje objekte.
 *
 * <p>{@code registracijskiBroj} je {@code null} kad jedinica nema RB — tada frontend nudi
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
        /**
         * Kontakt objekta iz eTurizma, za predpopunu forme (traženo 10.09.2026., stavka 11).
         * Nije zaključan podatak — korisnik ga smije ispraviti.
         */
        String kontaktEmail,
        String kontaktTelefon,
        FacilitySource izvor,
        /**
         * Objekt kojem jedinica pripada: {@code system_uuid} za eTurizam, a za privremeno rješenje
         * njegov vlastiti {@code id} (svako rješenje je zaseban objekt s jednom jedinicom).
         */
        String objektId,
        /**
         * {@code true} — objekt obrađen u novom eTurizmu; {@code false} — migriran iz starog
         * sustava i još neprovjeren; {@code null} za privremeno rješenje, koje nije u eTurizmu.
         */
        Boolean verificiran
) {}
