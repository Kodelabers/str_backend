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
 * <p>Poštanski broj i grad/općina provjeravaju se, ali se ne spremaju: {@code lessor} za njih nema
 * stupce (isto vrijedi i za adresu iz registra). Spremaju se ulica, kućni broj, mjesto i županija.
 *
 * @param zupanijaId županija iz šifrarnika ({@code /api/address/counties}) — o njoj ovisi GO-1
 * @param mbs        matični broj subjekta (9 znamenki, sudski registar); samo za tvrtku
 */
public record PodnositeljUnos(
        @Size(max = 500) String ulica,
        @Size(max = 16) String kucniBroj,
        @Pattern(regexp = "\\d{5}", message = "Poštanski broj mora imati 5 znamenki") String postanskiBroj,
        @Size(max = 128) String mjesto,
        @Size(max = 128) String opcina,
        @Min(1) Long zupanijaId,
        @Pattern(regexp = "\\d{9}", message = "MBS mora imati 9 znamenki") String mbs
) {
}
