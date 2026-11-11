package com.str.backend.egop;

import com.str.backend.document.StrDocumentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Vrste pismena kojima eGOP šifrarnik (još) nema unos.
 *
 * <p>Takav se akt <b>zapisuje i renderira kao i svaki drugi</b> — vidi se u „Moji registracijski
 * brojevi" i može se preuzeti — ali se ne šalje eGOP-u, jer bi slanje palo na razrješavanju vrste
 * pismena i vrtjelo retry do iscrpljenja pokušaja. Zaustavlja se dakle samo urudžbiranje, ne i
 * dokument prema stranci.
 *
 * <p>Popis je zajednički za {@link RnLifecycleFilingListener} (preskače slanje) i
 * {@link EgopRetryJob} (izuzima te akte iz reda za ponovni pokušaj). Da živi na samo jednom od
 * njih, cron bi vrtio upravo ono što je listener namjerno preskočio.
 *
 * <p>Kad InfoDom potvrdi šifru i slug se makne s popisa, zaostali akti prestaju biti izuzeti i
 * prvi sljedeći prolazak {@code EgopRetryJob}-a ih urudžbira — bez ručne intervencije.
 */
@Component
public class EgopAktiBezSifre {

    private static final Logger log = LoggerFactory.getLogger(EgopAktiBezSifre.class);

    /**
     * JPQL {@code NOT IN ()} s praznom listom je sintaktička greška, a prazan popis je
     * legitimno stanje (sve se urudžbira). Stražar je vrijednost koju nijedna vrsta ne nosi.
     */
    private static final String NIJEDNA = "";

    private final Set<String> slugovi;

    public EgopAktiBezSifre(@Value("${str.egop.akti-bez-sifre:}") Set<String> slugovi) {
        Set<String> ocisceni = new LinkedHashSet<>();
        for (String slug : slugovi) {
            String s = slug == null ? "" : slug.strip();
            if (s.isEmpty()) {
                continue;
            }
            if (StrDocumentType.fromSlug(s).isEmpty()) {
                // Tipfeler u konfiguraciji tiho bi urudžbirao vrstu koju smo mislili zaustaviti.
                log.warn("egop_akti_bez_sifre_nepoznat_slug slug={} — nema takve vrste akta,"
                        + " unos se ignorira; provjeriti str.egop.akti-bez-sifre", s);
                continue;
            }
            ocisceni.add(s);
        }
        this.slugovi = Set.copyOf(ocisceni);
        log.info("egop_akti_bez_sifre slugovi={}", this.slugovi);
    }

    /** Smije li se akt te vrste poslati eGOP-u. */
    public boolean urudzbiv(StrDocumentType type) {
        return !slugovi.contains(type.slug());
    }

    /**
     * Nazivi vrsta pismena za {@code NOT IN} uvjet u {@link EgopPismenoRepository}. Filtrira se
     * po nazivu, a ne po slugu, jer je naziv ono što {@code egop_pismeno} čuva.
     *
     * @return nikad prazno — prazan popis vraća stražara koji se ne poklapa ni s jednom vrstom
     */
    public Set<String> vrstePismena() {
        if (slugovi.isEmpty()) {
            return Set.of(NIJEDNA);
        }
        Set<String> nazivi = new LinkedHashSet<>();
        for (String slug : slugovi) {
            StrDocumentType.fromSlug(slug).ifPresent(t -> nazivi.add(t.vrstaPismenaNaziv()));
        }
        return Set.copyOf(nazivi);
    }
}
