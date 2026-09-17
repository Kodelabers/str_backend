package com.str.backend.egop;

import com.str.backend.request.SubmissionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dodjeljuje KLASU i URBROJ kad eGOP nije uključen, tako da izgledaju i broje se kao pravi.
 *
 * <p>Zašto postoji: naručitelj traži da podnesak u predmetu nosi <b>ur. br. 1</b>, a obavijest
 * o dodjeli <b>ur. br. 2</b> (sastanak 10.09.2026., stavke 17–18). Urudžbeni broj je u eGOP-u
 * redni broj pismena <i>unutar predmeta</i>, pa mora krenuti od 1 u svakom predmetu.
 *
 * <p>Brojači se izvode iz baze, ne iz memorije. Razlog nije uredno stanje nego ispravnost:
 * <ul>
 *   <li><b>Restart usred urudžbiranja.</b> {@code EgopFilingService.fileRegistration} prvo
 *       kreira i <i>commita</i> ulazno pismeno, pa tek onda izlazno. Memorijski brojač bi se
 *       nakon restarta resetirao, {@code ensurePismeno} bi ulazno preskočio (red već postoji),
 *       a izlazno bi opet dobilo 1 — točno dvostruki ur. br. 1 iz primjedbe.</li>
 *   <li><b>Akti životnog ciklusa</b> urudžbiraju se danima kasnije, iz drugog procesa; iz
 *       memorije bi svaka suspenzija bila „ur. br. 1".</li>
 *   <li><b>KLASA se ne smije ponoviti.</b> {@code uq_submission_filing_number} stoji nad
 *       {@code "KLASA: …, URBROJ: <ulazno>"}. Otkad svako ulazno pismeno završava na
 *       {@code -1}, jedinstvenost te vrijednosti počiva <b>isključivo na KLASI</b>. Raniji
 *       brojač sijan iz {@code System.currentTimeMillis()/60000} nakon restarta ponavlja
 *       redne brojeve predmeta — dosad je to povremeno kolidiralo, s urudžbenim brojem po
 *       predmetu kolidira deterministički i izlazi kao 500 na registraciji.</li>
 * </ul>
 *
 * <p>Sjeme se čita lijeno, pri prvoj upotrebi, a ne u {@code @PostConstruct} — nedostupna baza
 * pri dizanju ne smije blokirati start aplikacije.
 *
 * <p>Diže se pod istim uvjetom kao {@link EgopClientMock} — kad je eGOP uključen, brojeve
 * dodjeljuje eGOP i ovaj razred nema svrhe.
 *
 * <p>Prazan prefiks na {@code prod} profilu ruši start. Prefiks je jedina oznaka po kojoj se
 * izmišljena KLASA/URBROJ u {@code submission.egop_klasa} razlikuje od prave; prazan je dopušten
 * samo na demo okolinama (CDU, predprodukcija). Na produkciji mock ionako ne bi smio biti aktivan,
 * ali {@code egop.enabled} ima {@code matchIfMissing = true}, pa ga tipfeler u {@code EGOP_ENABLED}
 * tiho uključi — tada barem ne smije proizvoditi brojeve nerazlučive od pravih.
 *
 * <p>Jedna instanca = jedan proces. Predprodukcija i CDU voze jedan kontejner, pa je
 * {@link AtomicInteger} po godini dovoljan. Za više instanci bi trebala Postgres sekvenca u
 * <b>novom</b> changesetu (changeset koji je već primijenjen se ne smije mijenjati), uz put za
 * H2 u testovima — zato se sad ne uvodi.
 */
@Component
@ConditionalOnProperty(
        name = "hr.infodom.str.integration.egop.enabled",
        havingValue = "false",
        matchIfMissing = true
)
class LocalFilingNumberAllocator {

    /** Novootvoreni predmet: uredska godina, redni broj i iz njih složena klasifikacijska oznaka. */
    record Predmet(int uredskaGodina, int rbrPredmeta, String klasa) {}

    /** Urudžbeni broj i njegov redni broj unutar predmeta — redni treba i za {@code jop}. */
    record UrBroj(int redni, String vrijednost) {}

    private final SubmissionRepository submissionRepository;
    private final EgopPismenoRepository pismenoRepository;
    private final String prefix;
    private final String klasaOznaka;
    private final String urbrojOznaka;

    /** Brojač redni-broj-predmeta po uredskoj godini; vrijednost je zadnji dodijeljeni rbr. */
    private final Map<Integer, AtomicInteger> predmetSeqByYear = new ConcurrentHashMap<>();

    LocalFilingNumberAllocator(SubmissionRepository submissionRepository,
                               EgopPismenoRepository pismenoRepository,
                               Environment environment,
                               @Value("${str.egop.mock.filing-prefix:MOCK-}") String prefix,
                               @Value("${str.egop.mock.klasa-oznaka:334-01}") String klasaOznaka,
                               @Value("${str.egop.mock.urbroj-oznaka:529-06}") String urbrojOznaka) {
        this.submissionRepository = submissionRepository;
        this.pismenoRepository = pismenoRepository;
        this.prefix = prefix == null ? "" : prefix;
        if (this.prefix.isBlank() && environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("str.egop.mock.filing-prefix je prazan na prod profilu. "
                    + "Prazan prefiks je dopušten samo na demo okolinama — bez njega se izmišljena KLASA "
                    + "i URBROJ u bazi ne razlikuju od pravih. Na produkciji mock ne bi smio biti "
                    + "aktivan: provjeri EGOP_ENABLED.");
        }
        this.klasaOznaka = klasaOznaka;
        this.urbrojOznaka = urbrojOznaka;
    }

    /** Prefiks lažnih oznaka — prazan kad demo traži brojeve koji izgledaju kao pravi. */
    String prefix() {
        return prefix;
    }

    Predmet nextPredmet() {
        int godina = LocalDate.now().getYear();
        int rbr = predmetSeqByYear
                .computeIfAbsent(godina, g -> new AtomicInteger(submissionRepository.maxEgopRbrPredmeta(g)))
                .incrementAndGet();
        return new Predmet(godina, rbr, klasa(godina, rbr));
    }

    String klasa(int uredskaGodina, int rbrPredmeta) {
        return prefix + klasaOznaka + "/" + (uredskaGodina % 100) + "-01/" + rbrPredmeta;
    }

    /**
     * Sljedeći urudžbeni broj u predmetu: prvo pismeno (zahtjev) dobiva 1, drugo (obavijest o
     * dodjeli) 2, a akti životnog ciklusa 3, 4, …
     *
     * <p>{@code synchronized} jer inline listener i {@code EgopRetryJob} mogu urudžbirati dva
     * akta istog predmeta u istom trenutku. Nijedan constraint ne bi pukao — nad {@code ur_broj}
     * nema unique indeksa — ali dva akta bi ispisala isti urudžbeni broj, tj. upravo kvar koji
     * ovaj razred uklanja. Mock nije na vrućoj putanji, pa je zaključavanje besplatno.
     *
     * <p>Brojevi se dodjeljuju redoslijedom <i>dovršetka</i>, ne nastanka: akt koji je pao pa
     * bio ponovljen dobiva sljedeći slobodan broj. Numeracija zato smije biti nekronološka, ali
     * se ne ponavlja.
     */
    synchronized UrBroj nextUrBroj(int uredskaGodina, int rbrPredmeta) {
        int redni = (int) pismenoRepository.countFiledInPredmet(uredskaGodina, rbrPredmeta) + 1;
        return new UrBroj(redni, prefix + urbrojOznaka + "/" + (uredskaGodina % 100) + "-" + redni);
    }
}
