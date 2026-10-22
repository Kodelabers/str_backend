package com.str.backend.document;

import org.junit.jupiter.api.Test;

import static com.str.backend.document.StrDocumentType.Smjer.IZLAZNO;
import static com.str.backend.document.StrDocumentType.Smjer.ULAZNO;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smjer pismena u eGOP-u po predmetu, kako ga je propisao MINT (22.10.2026.). O smjeru ovisi
 * metoda kreiranja ({@code KreirajPismeno2} / {@code KreirajIzlaznoPismeno}) i otprema u
 * korisnički pretinac, koja ide samo za izlazna — eGOP ulazno odbija („Pismeno mora biti
 * izlazno!").
 */
class StrDocumentTypeTest {

    /** Predmet 334-06/10 „Dodjela registracijskog STR broja". */
    @Test
    void registrationPredmet_requestIsIncoming_noticeIsOutgoing() {
        assertThat(StrDocumentType.ZAHTJEV.smjer()).isEqualTo(ULAZNO);
        assertThat(StrDocumentType.DODJELA.smjer()).isEqualTo(IZLAZNO);
    }

    /** Predmet 334-06/11 „Suspenzija, povlačenje i reaktiviranje…". */
    @Test
    void actsPredmet_onlyRevocationIsIncoming() {
        assertThat(StrDocumentType.PRIJEDLOG_SUSPENZIJE.smjer()).isEqualTo(IZLAZNO);
        assertThat(StrDocumentType.OBUSTAVA_SUSPENZIJE.smjer()).isEqualTo(IZLAZNO);
        assertThat(StrDocumentType.SUSPENZIJA.smjer()).isEqualTo(IZLAZNO);
        assertThat(StrDocumentType.REAKTIVACIJA.smjer()).isEqualTo(IZLAZNO);
        assertThat(StrDocumentType.POVLACENJE.smjer()).isEqualTo(IZLAZNO);
        assertThat(StrDocumentType.OPOZIV.smjer()).isEqualTo(ULAZNO);
    }

    /**
     * Autorstvo dokumenta ne slijedi smjer: opoziv je ulazno pismeno, a PDF je i dalje akt
     * tijela (zaglavlje, potpis, ePečat). Podnesci stranke nisu akti tijela.
     */
    @Test
    void revocationIsIncomingButStillAnActOfTheAuthority() {
        assertThat(StrDocumentType.OPOZIV.aktTijela()).isTrue();
        assertThat(StrDocumentType.values())
                .filteredOn(t -> !t.aktTijela())
                .containsExactlyInAnyOrder(StrDocumentType.ZAHTJEV, StrDocumentType.PRIGOVOR,
                        StrDocumentType.ZAHTJEV_PROMJENE_PODATAKA);
    }

    /** Svako izlazno pismeno je akt tijela — inače bi u KP otišao dokument bez pečata tijela. */
    @Test
    void everyOutgoingType_isAnActOfTheAuthority() {
        assertThat(StrDocumentType.values())
                .filteredOn(t -> t.smjer() == IZLAZNO)
                .allMatch(StrDocumentType::aktTijela);
    }

    /** Kanonski naziv ostaje ključ na egop_pismeno; šifra se traži po nazivu iz eGOP šifrarnika. */
    @Test
    void egopNames_differOnlyForRequestAndReactivation() {
        assertThat(StrDocumentType.ZAHTJEV.vrstaPismenaNaziv()).isEqualTo("Zahtjev za registracijski broj");
        assertThat(StrDocumentType.ZAHTJEV.egopVrstaNaziv()).isEqualTo("Zahtjev za dodjelu registracijskog broja");
        assertThat(StrDocumentType.REAKTIVACIJA.vrstaPismenaNaziv())
                .isEqualTo("Obavijest o reaktivaciji registracijskog broja");
        assertThat(StrDocumentType.REAKTIVACIJA.egopVrstaNaziv())
                .isEqualTo("Obavijest o reaktiviranju registracijskog broja");
        assertThat(StrDocumentType.values())
                .filteredOn(t -> t != StrDocumentType.ZAHTJEV && t != StrDocumentType.REAKTIVACIJA)
                .allMatch(t -> t.egopVrstaNaziv().equals(t.vrstaPismenaNaziv()));
    }
}
