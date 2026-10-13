package com.str.backend.registration.dto;

import com.str.backend.categorization.CategorizationDecisionStatus;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Predpopuna obrasca za ponovni zahtjev za RB novog objekta nakon povlačenja (T15) —
 * {@code GET /api/nias/registrations/{rn}/ponovni-zahtjev} i
 * {@code GET /api/lessor/registrations/{rn}/ponovni-zahtjev}.
 *
 * <p>Podaci su oni spremljeni uz povučeni RB, a korisnik ih na obrascu smije pregledati i
 * promijeniti. Zahtjev se zatim predaje uobičajenim putem s {@code prethodniRb = povucenRb}.
 *
 * @param povucenRb        povučeni RB iz kojeg su podaci preuzeti
 * @param povucenDana      dan povlačenja ({@code registration_number.valid_to})
 * @param ponovniZahtjevDo zadnji dan na koji se ponovni zahtjev s preuzetim podacima smije predati
 * @param objekt           podaci objekta, v. {@link Objekt}
 * @param rjesenje         rješenje o kategorizaciji koje se prenosi na novi RB (predano ili
 *                         potvrđeno); {@code null} kad ga nema ili je odbijeno — tada ga obrazac
 *                         traži kao za novi objekt
 */
public record ReissuePrefillResponse(
        String povucenRb,
        LocalDate povucenDana,
        LocalDate ponovniZahtjevDo,
        Objekt objekt,
        Rjesenje rjesenje
) {

    /**
     * Podaci objekta u obliku obrasca ({@code FormValues} na frontendu) i tijela
     * {@code POST /api/generateRegistrationNumber}.
     *
     * <p>Adresa se u {@code str_rn.accommodation} čuva samo nazivima, pa se id-evi adresnog
     * registra razrješavaju naknadno (po nazivu, kaskadom). Razina koja se ne može jednoznačno
     * razriješiti, i sve ispod nje, ima {@code null} id — nazivi su uvijek tu, za kaskadu po nazivu
     * kakvu obrazac već radi za eTurizam objekte. {@code kucniBrojId} je bitan: bez njega se pri
     * predaji ne čita katastar.
     *
     * @param vrstaId    {@code accommodation_type.type_id} (razlikuje se po okolinama)
     * @param vrstaSifra stabilna šifra vrste ({@code FS_*}); obje vrijednosti backend prima kao {@code typeId}
     * @param brKreveta  maksimalan broj gostiju ({@code maxBeds})
     * @param kat        kat ({@code floor}), cijeli broj; {@code null} kad nije spremljen kao broj
     * @param katastarskaOpcina samo za prikaz — pri predaji je backend čita iz registra po {@code kucniBrojId}
     * @param kcBroj     broj katastarske čestice ({@code kcBroj})
     * @param offerType  isto polje i vrijednosti kao u tijelu zahtjeva
     *                   ({@code PRIMARY_RESIDENCE}/{@code SECONDARY_RESIDENCE}/{@code OTHER})
     * @param ponuda     {@code offering}: {@code WHOLE}/{@code PART}
     * @param building   GO izjave objekta spremljene uz povučeni RB — {@code building} …
     *                   {@code host} nose ista imena i tipove kao u tijelu zahtjeva, da ih obrazac
     *                   preslika 1:1
     * @param kontaktEmail kontakt s povučenog zahtjeva (snapshot iznajmljivača); obrazac ga smije
     *                   pregaziti svježijim podatkom. Kad je vlasnik pravna osoba (e-Zastupanja), sva
     *                   četiri kontakt polja su {@code null} — snapshot je kontakt tadašnjeg zastupnika.
     */
    public record Objekt(
            String naziv,
            Long vrstaId,
            String vrstaSifra,
            String vrstaNaziv,
            Integer brKreveta,
            Integer kat,
            Long zupanijaId,
            String zupanijaNaziv,
            Long opcinaId,
            String opcinaNaziv,
            Long naseljeId,
            String naseljeNaziv,
            Long ulicaId,
            String ulicaNaziv,
            Long kucniBrojId,
            String kucniBrojNaziv,
            String postanskiBroj,
            String katastarskaOpcina,
            String kcBroj,
            OfferType offerType,
            Offering ponuda,
            boolean building,
            boolean apartments,
            boolean legalized,
            Boolean lessorResidence,
            Boolean coOwnerConsent,
            LocalDate consentDate,
            LocalDate consentWithdrawalDate,
            Boolean host,
            String kontaktEmail,
            String kontaktMobitel,
            String kontaktTelefon,
            String kontaktOsoba
    ) {
    }

    /** Rješenje o kategorizaciji uz povučeni RB koje će se pri izdavanju prenijeti na novi RB. */
    public record Rjesenje(UUID decisionId, String nazivDatoteke, CategorizationDecisionStatus status) {
    }
}
