package com.str.backend.address;

import java.util.List;

/**
 * Izbor poštanskih brojeva naselja iz {@code rpj_dgu.postanski_brojevi}.
 *
 * <p>Tablica je vezana na naselje <b>samo po imenu</b>, preko cijele države, pa isto ime naselja
 * pokupi brojeve svih istoimenih naselja (zagrebačka Brezovica dobije i osječki 31542 i
 * virovitički 33411). Zato se redci sužavaju na županiju naselja.
 *
 * <p>Sužavanje je namjerno oprezno, jer format {@code postanski_brojevi.zupanija} na stvarnoj
 * bazi nije provjeren (lokalni seed piše isto kao {@code zupanije.zu_ime}):
 * <ul>
 *   <li>županije se uspoređuju po {@link #countyKey} — bez obzira na velika slova, sufiks
 *       „ županija" i prefiks „Grad " („Grad Zagreb" = „Zagreb");</li>
 *   <li>popis naselja ({@link #preferSameCounty}): ako nijedan redak ne odgovara županiji,
 *       vraćaju se svi redci (dosadašnje ponašanje) — naselje nikad ne ostane bez broja koji je
 *       dosad imalo, a korisnik broj ionako vidi i bira;</li>
 *   <li>adresa podnositelja ({@link #single}): broj iz druge županije se ne uzima — podnositelj
 *       ga ne bira, pa je prazno bolje od tuđeg broja;</li>
 *   <li>redak bez županije se ne izbacuje: nepoznato nije „druga županija".</li>
 * </ul>
 */
public final class PostalCodes {

    private PostalCodes() {
    }

    /** Jedan redak spoja naselja i {@code postanski_brojevi}. */
    public interface Candidate {
        String getPostalCode();

        /** {@code postanski_brojevi.zupanija}. */
        String getPostalCounty();

        /** Županija naselja, {@code zupanije.zu_ime}. */
        String getCounty();
    }

    /**
     * Redci <b>jednog</b> naselja suženi na njegovu županiju; svi redci ako nijedan ne odgovara.
     */
    public static <T extends Candidate> List<T> preferSameCounty(List<T> rows) {
        boolean anyMatch = rows.stream().anyMatch(PostalCodes::sameCounty);
        if (!anyMatch) {
            return rows;
        }
        return rows.stream()
                .filter(r -> sameCounty(r) || countyKey(r.getPostalCounty()) == null)
                .toList();
    }

    /**
     * Jedinstveni poštanski broj naselja za adresu podnositelja; {@code null} kad ga nema ili kad
     * ih ostane više (adresni registar ne veže broj na kućni broj, pa se ne bira).
     *
     * <p>Strože od {@link #preferSameCounty}: broj iz <b>druge</b> županije se ne uzima ni kad
     * drugog nema. Podnositelj broj ne bira, a ide u podnesak, pa je prazno bolje od tuđeg broja.
     * Redak bez županije vrijedi, jer nepoznato nije „druga županija".
     */
    public static String single(List<? extends Candidate> rows) {
        List<String> codes = rows.stream()
                .filter(r -> sameCounty(r) || countyKey(r.getPostalCounty()) == null)
                .map(Candidate::getPostalCode)
                .filter(c -> c != null && !c.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        return codes.size() == 1 ? codes.get(0) : null;
    }

    /** {@link CountyNames#key} bez prefiksa „grad "; {@code null} za prazno. */
    static String countyKey(String name) {
        String key = CountyNames.key(name);
        return key == null ? null : key.replaceFirst("^grad ", "");
    }

    private static boolean sameCounty(Candidate row) {
        String postal = countyKey(row.getPostalCounty());
        return postal != null && postal.equals(countyKey(row.getCounty()));
    }
}
