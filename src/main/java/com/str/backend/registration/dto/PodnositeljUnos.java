package com.str.backend.registration.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Adresa podnositelja (fizičke osobe ili sjedište tvrtke) i MBS tvrtke koje je korisnik upisao
 * na obrascu. Obrazac ih nudi samo kad ih registar nije vratio, a backend ih i koristi samo tada —
 * kad registar podatak ima, mjerodavan je registar ({@code lessor.SubjectProfileService}).
 *
 * <p>Sva polja su neobavezna na razini zahtjeva: jesu li potrebna zna tek servis, nakon upita
 * registru. Duljine prate stupce {@code str_rn.lessor}.
 *
 * <p><b>Lanac adrese (T4).</b> Kad je adresa potrebna, {@code zupanijaId}, {@code opcinaId} i
 * {@code naseljeId} su obavezni i moraju biti povezani u adresnom registru (općina u županiji,
 * naselje u općini); nazivi općine, naselja i županije uzimaju se iz registra, a poslani tekst
 * ({@code opcina}, {@code mjesto}) se zanemaruje. Ulica i kućni broj se odabiru iz registra
 * ({@code ulicaId}, {@code kucniBrojId} — tada moraju pripadati naselju odnosno ulici i naziv je iz
 * registra) ili, kad ih registar nema, upisuju tekstom ({@code ulica}, {@code kucniBroj}, dopušten je
 * i „bb"). {@code kucniBrojId} traži {@code ulicaId}. Poštanski broj mora biti jedan od brojeva
 * naselja iz {@code /api/address/settlements}, osim kad ih naselje nema. Prekid lanca je 400
 * {@code details.code = ADDRESS_INCONSISTENT}, nedostajuća razina 400 {@code ADDRESS_INCOMPLETE};
 * {@code details.field} je polje, npr. {@code podnositelj.naseljeId} — v.
 * {@code address.AddressHierarchyValidator}. Zahtjev bez id-eva (stari klijent, stari nacrt) kojem
 * je adresa potrebna zato dobiva {@code ADDRESS_INCOMPLETE}, ne tiho prihvaćen slobodni tekst.
 *
 * <p>Poštanski broj i grad/općina provjeravaju se, ali se ne spremaju: {@code lessor} za njih nema
 * stupce (isto vrijedi i za adresu iz registra). Spremaju se ulica, kućni broj, mjesto i županija.
 *
 * @param zupanijaId  županija iz šifrarnika ({@code /api/address/counties}) — o njoj ovisi GO-1
 * @param mbs         matični broj subjekta (9 znamenki, sudski registar); samo za tvrtku
 * @param opcinaId    grad/općina iz {@code /api/address/municipalities?zupanijaId=}
 * @param naseljeId   naselje iz {@code /api/address/settlements?opcinaId=}
 * @param ulicaId     ulica iz {@code /api/address/streets?naseljeId=}; {@code null} kad je upisana tekstom
 * @param kucniBrojId kućni broj iz {@code /api/address/house-numbers?ulicaId=}; {@code null} kad je
 *                    upisan tekstom
 */
public record PodnositeljUnos(
        @Size(max = 500) String ulica,
        @Size(max = 16) String kucniBroj,
        @Pattern(regexp = "\\d{5}", message = "Poštanski broj mora imati 5 znamenki") String postanskiBroj,
        @Size(max = 128) String mjesto,
        @Size(max = 128) String opcina,
        @Min(1) Long zupanijaId,
        @Pattern(regexp = "\\d{9}", message = "MBS mora imati 9 znamenki") String mbs,
        @Min(1) Long opcinaId,
        @Min(1) Long naseljeId,
        @Min(1) Long ulicaId,
        @Min(1) Long kucniBrojId
) {

    /** Unos bez id-eva adresnog registra (samo tekst i županija). */
    public PodnositeljUnos(String ulica, String kucniBroj, String postanskiBroj, String mjesto, String opcina,
                           Long zupanijaId, String mbs) {
        this(ulica, kucniBroj, postanskiBroj, mjesto, opcina, zupanijaId, mbs, null, null, null, null);
    }
}
