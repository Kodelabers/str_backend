package com.str.backend.document;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Sve što u aktu dolazi iz konfiguracije, a ne iz baze: identitet tijela, potpisnik, ovjera
 * i tekst upute o pravnom lijeku.
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
        boolean reload
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
    ) {}

    public record Potpisnik(String ime, String funkcija) {}

    /**
     * Ovjera po čl. 98. st. 8: akt izdan iz informacijskog sustava smije se ovjeriti
     * <b>samo</b> kvalificiranim elektroničkim pečatom. Dok pečata nema, klauzula se ne
     * ispisuje — tvrdnja o ovjeri na nepečaćenom PDF-u bila bi neistinita izjava na aktu.
     */
    public record Epecat(boolean enabled, String klauzula) {}

    public DocumentProperties {
        tijelo = tijelo == null ? new Tijelo(null, null, null, null, null, null) : tijelo;
        potpisnik = potpisnik == null ? new Potpisnik(null, null) : potpisnik;
        epecat = epecat == null ? new Epecat(false, null) : epecat;
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
