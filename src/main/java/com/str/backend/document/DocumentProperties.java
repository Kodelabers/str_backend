package com.str.backend.document;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Sve što u aktu dolazi iz konfiguracije, a ne iz baze: identitet tijela, potpisnik, podaci
 * e-pečata i tekst upute o pravnom lijeku.
 *
 * <p>Uputa je namjerno property, a ne dio predloška: pravna narav akata (upravni postupak sa
 * žalbom / upravnim sporom, ili neupravni s prigovorom čelniku) nije potvrđena s MINT-om, a
 * po čl. 111. ZUP-a pogrešna uputa ide na štetu tijela. Ovako pravna služba mijenja jedan
 * redak konfiguracije, bez rebuilda i bez diranja predloška.
 */
@ConfigurationProperties("str.documents")
public record DocumentProperties(
        Tijelo tijelo,
        Potpisnik potpisnik,
        Epecat epecat,
        /** slug vrste akta → tekst upute; vidi {@link #uputaZa(StrDocumentType)}. */
        Map<String, String> uputa,
        /** Razvojna pogodnost: čita predloške pri svakom renderu umjesto iz cachea. */
        boolean reload,
        /**
         * Akti tijela izlaze kao PDF/A-1b (ISO 19005): sRGB output intent i XMP oznaka razine.
         * eSeal servis može odbiti PDF koji nije PDF/A ({@code PDF_NOT_PDFA}), a pečat na PDF/A
         * ulazu ostavlja dokument prikladnim za arhivu. Prekidač dok se ne potvrdi što servis traži.
         */
        boolean pdfa
) {

    private static final String DEFAULT_UPUTA =
            "nije dopuštena žalba, ali se može pokrenuti upravni spor tužbom nadležnom upravnom"
                    + " sudu u roku od 30 dana od dana dostave ove Obavijesti.";

    public record Tijelo(
            String naziv,
            String oib,
            String adresa,
            String mjesto,
            String ustrojstvenaJedinica,
            /** Čl. 98. st. 2 — propis koji tijelu daje nadležnost, citira se u uvodu. */
            String propisNadleznosti
    ) {

        /**
         * Naziv otvara rečenicu uvoda svakog akta („Ministarstvo …, OIB: …") i stoji kao adresat
         * zahtjeva, pa uvijek počinje velikim slovom — i kad je env upisan malim (CDU,
         * 18.09.2026.). Ostatak naziva ostaje kako je upisan.
         */
        public Tijelo {
            naziv = velikoPocetno(naziv);
        }

        private static String velikoPocetno(String naziv) {
            if (naziv == null || naziv.isBlank()) {
                return naziv;
            }
            String s = naziv.strip();
            int prvi = s.codePointAt(0);
            return new StringBuilder(s.length())
                    .appendCodePoint(Character.toUpperCase(prvi))
                    .append(s, Character.charCount(prvi), s.length())
                    .toString();
        }
    }

    public record Potpisnik(String ime, String funkcija) {}

    /**
     * Ovjera po čl. 98. st. 8: akt izdan iz informacijskog sustava smije se ovjeriti
     * <b>samo</b> kvalificiranim elektroničkim pečatom. Dok pečata nema, blok e-pečata se ne
     * ispisuje — tvrdnja o ovjeri na nepečaćenom PDF-u bila bi neistinita izjava na aktu.
     *
     * <p>Ovo je samo vizualni blok; pečat stavlja eSeal servis ({@code str.eseal.*}). Podaci o
     * certifikatu su zasad konfiguracija, a broj zapisa i kontrolni broj u bloku nisu oni koje
     * servis vrati ({@code Document-Guid}, {@code Control-Number}) — zato blok ostaje ugašen dok se
     * ne odluči tko ih ispisuje (docs/ESEAL-SPREMNOST.md).
     */
    public record Epecat(
            boolean enabled,
            String izdavateljCertifikata,
            String nazivCertifikata,
            String algoritam,
            String urlProvjere
    ) {}

    public DocumentProperties {
        tijelo = tijelo == null ? new Tijelo(null, null, null, null, null, null) : tijelo;
        potpisnik = potpisnik == null ? new Potpisnik(null, null) : potpisnik;
        epecat = epecat == null ? new Epecat(false, null, null, null, null) : epecat;
        uputa = uputa == null ? Map.of() : Map.copyOf(uputa);
    }

    /**
     * Tekst upute za zadani akt; ako nije konfiguriran, vraća se default (upravni spor).
     * Nikad ne vraća prazno — prazna uputa je po čl. 111. isto što i nepostojeća.
     */
    public String uputaZa(StrDocumentType type) {
        String value = uputa.get(type.slug());
        return (value == null || value.isBlank()) ? DEFAULT_UPUTA : value;
    }
}
