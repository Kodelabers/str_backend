package com.str.backend.registries.oib;

import com.fasterxml.jackson.databind.JsonNode;
import com.str.backend.exception.ExternalRegistryException;
import com.str.backend.lessor.RegistryLegalEntity;
import com.str.backend.lessor.RegistrySubject;
import com.str.backend.lessor.SubjectDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Jedino mjesto koje poznaje shemu odgovora {@code GET /pretraga-registra/oib/FO|PO/{oib}} (OIB
 * sustav Porezne uprave, preko {@code str-internal-api}).
 *
 * <p><b>Oblik odgovora.</b> Podaci o osobi su pod {@code fizickaOsoba.dohvatiFA} …
 * {@code dohvatiFI} — svaka varijanta je druga metoda OIB sustava s drukčijim podskupom polja
 * (npr. FE nema adresu, FH nema ime). Koju servis puni ovisi o metodi koju {@code str-internal-api}
 * zove, pa se ne pretpostavlja nijedna: ime se uzima iz prve varijante koja ga ima, adresa iz
 * prve koja ima {@code adresaPrebivalista}. Redoslijed ide od najpotpunije varijante.
 *
 * <p><b>Što shema nema:</b> kontakt (e-mail, telefon) i županiju. Kontakt korisnik upisuje na
 * formi; županija se izvodi iz {@code opcina} ({@code lessor.SubjectProfileService}), jer o njoj
 * ovisi GO-1 (status domaćina).
 *
 * <p><b>Greške.</b> {@code greske.imaGresaka=true} bez podataka o osobi tretira se kao kvar
 * registra (503), jer šifre grešaka još nisu poznate — među njima je vjerojatno i „OIB ne
 * postoji", koji bi trebao biti 400. Šifre se zato stavljaju u poruku iznimke, pa ih prvi stvarni
 * poziv otkrije u logu ({@code external_registry_error}).
 */
final class OibRegistryResponseMapper {

    /** Varijante s imenom i prezimenom, od najpotpunije. */
    private static final List<String> NAME_VARIANTS =
            List.of("dohvatiFA", "dohvatiFB", "dohvatiFF", "dohvatiFG", "dohvatiFC", "dohvatiFD", "dohvatiFE");

    /** Varijante s adresom prebivališta, od najpotpunije. */
    private static final List<String> ADDRESS_VARIANTS =
            List.of("dohvatiFA", "dohvatiFB", "dohvatiFF", "dohvatiFG", "dohvatiFH", "dohvatiFI");

    /** Varijante pravne osobe — sve imaju naziv, MBS i sjedište, pa redoslijed nije bitan. */
    private static final List<String> LEGAL_VARIANTS =
            List.of("dohvatiPA", "dohvatiPB", "dohvatiPC", "dohvatiPD", "dohvatiPE");

    private OibRegistryResponseMapper() {}

    /**
     * @return subjekt; {@link Optional#empty()} kad odgovor nema podataka o osobi ni grešaka
     * @throws ExternalRegistryException kad odgovor javlja greške, a podataka o osobi nema
     */
    static Optional<RegistrySubject> toSubject(String oib, JsonNode body) {
        JsonNode person = body.path("fizickaOsoba");
        Optional<JsonNode> named = firstWith(person, NAME_VARIANTS, v -> hasText(v, "ime") || hasText(v, "prezime"));
        Optional<JsonNode> address = firstWith(person, ADDRESS_VARIANTS, v -> v.path("adresaPrebivalista").isObject())
                .map(v -> v.path("adresaPrebivalista"));

        if (named.isEmpty() && address.isEmpty()) {
            List<String> errorCodes = errorCodes(body);
            if (!errorCodes.isEmpty()) {
                throw new ExternalRegistryException(OibRegistryHttpClient.REGISTRY,
                        "OIB sustav vratio greške bez podataka o osobi: " + errorCodes);
            }
            return Optional.empty();
        }

        JsonNode a = address.orElse(null);
        return Optional.of(new RegistrySubject(
                oib,
                named.map(v -> text(v, "ime")).orElse(null),
                named.map(v -> text(v, "prezime")).orElse(null),
                null,
                a == null ? null : text(a, "ulica"),
                a == null ? null : streetNumber(a),
                a == null ? null : text(a, "naselje"),
                a == null ? null : text(a, "brojPoste"),
                a == null ? null : text(a, "opcina"),
                null,
                SubjectDataSource.OIB_REGISTAR));
    }

    /**
     * Pravna osoba ({@code /PO/{oib}}): naziv, MBS i adresa sjedišta. Kao kod fizičke osobe, svaki
     * se podatak uzima iz prve varijante {@code pravnaOsoba.dohvatiP*} koja ga ima (test okolina puni
     * PB). Zastupnici ({@code ovlasteniFO}, samo u PE) se ne čitaju — zastupnik je iz eTurizma.
     *
     * @return tvrtka; {@link Optional#empty()} kad odgovor nema podataka o tvrtki ni grešaka
     * @throws ExternalRegistryException kad odgovor javlja greške, a podataka o tvrtki nema
     */
    static Optional<RegistryLegalEntity> toLegalEntity(String oib, JsonNode body) {
        JsonNode company = body.path("pravnaOsoba");
        Optional<JsonNode> named = firstWith(company, LEGAL_VARIANTS, v -> hasText(v, "nazivTvrtke"));
        Optional<JsonNode> registered = firstWith(company, LEGAL_VARIANTS, v -> hasText(v, "mbs"));
        Optional<JsonNode> seat = firstWith(company, LEGAL_VARIANTS, v -> v.path("adresaSjedista").isObject())
                .map(v -> v.path("adresaSjedista"));

        if (named.isEmpty() && seat.isEmpty()) {
            List<String> errorCodes = errorCodes(body);
            if (!errorCodes.isEmpty()) {
                throw new ExternalRegistryException(OibRegistryHttpClient.REGISTRY,
                        "OIB sustav vratio greške bez podataka o tvrtki: " + errorCodes);
            }
            return Optional.empty();
        }

        JsonNode a = seat.orElse(null);
        return Optional.of(new RegistryLegalEntity(
                oib,
                named.map(v -> text(v, "nazivTvrtke")).orElse(null),
                registered.map(v -> text(v, "mbs")).orElse(null),
                a == null ? null : text(a, "ulica"),
                a == null ? null : streetNumber(a),
                a == null ? null : text(a, "naselje"),
                a == null ? null : text(a, "brojPoste"),
                a == null ? null : text(a, "opcina"),
                null,
                SubjectDataSource.OIB_REGISTAR));
    }

    /** {@code kucniBroj} + {@code kucniBrojDodatak} („14" + „a" → „14a"), kako se piše u adresi. */
    private static String streetNumber(JsonNode address) {
        String number = text(address, "kucniBroj");
        String suffix = text(address, "kucniBrojDodatak");
        if (number == null) {
            return null;
        }
        return suffix == null ? number : number + suffix;
    }

    private static Optional<JsonNode> firstWith(JsonNode person, List<String> variants,
                                                Predicate<JsonNode> test) {
        for (String variant : variants) {
            JsonNode node = person.path(variant);
            if (node.isObject() && test.test(node)) {
                return Optional.of(node);
            }
        }
        return Optional.empty();
    }

    private static List<String> errorCodes(JsonNode body) {
        JsonNode errors = body.path("greske");
        if (!errors.path("imaGresaka").asBoolean(false)) {
            return List.of();
        }
        List<String> codes = new ArrayList<>();
        for (JsonNode e : errors.path("greska")) {
            String code = text(e, "sifra");
            codes.add(code != null ? code : "?");
        }
        return codes.isEmpty() ? List.of("?") : codes;
    }

    private static boolean hasText(JsonNode node, String field) {
        return text(node, field) != null;
    }

    /**
     * Prazno i čisti razmaci se broje kao „nema podatka". Broj se čita kao tekst — shema kaže
     * {@code string}, ali npr. MBS ili kućni broj kao JSON broj ne smiju tiho nestati.
     */
    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (!v.isTextual() && !v.isNumber()) {
            return null;
        }
        String t = v.asText().trim();
        return t.isEmpty() ? null : t;
    }
}
