package com.str.backend.document;

import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
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

    /**
     * Predmet 334-06/11 „Suspenzija, povlačenje i reaktiviranje…". Od T15 su sve obavijesti
     * izlazne, i opoziv; ulazan je samo zahtjev za opoziv, podnesak stranke.
     */
    @Test
    void actsPredmet_onlyRevocationRequestIsIncoming() {
        assertThat(StrDocumentType.PRIJEDLOG_SUSPENZIJE.smjer()).isEqualTo(IZLAZNO);
        assertThat(StrDocumentType.OBUSTAVA_SUSPENZIJE.smjer()).isEqualTo(IZLAZNO);
        assertThat(StrDocumentType.SUSPENZIJA.smjer()).isEqualTo(IZLAZNO);
        assertThat(StrDocumentType.REAKTIVACIJA.smjer()).isEqualTo(IZLAZNO);
        assertThat(StrDocumentType.POVLACENJE.smjer()).isEqualTo(IZLAZNO);
        assertThat(StrDocumentType.OPOZIV.smjer()).isEqualTo(IZLAZNO);
        assertThat(StrDocumentType.ZAHTJEV_OPOZIVA.smjer()).isEqualTo(ULAZNO);
    }

    /** Smjer i autorstvo se poklapaju: podnesci stranke su ulazni i nisu akti tijela. */
    @Test
    void partySubmissions_areIncomingAndNotActsOfTheAuthority() {
        assertThat(StrDocumentType.OPOZIV.aktTijela()).isTrue();
        assertThat(StrDocumentType.values())
                .filteredOn(t -> !t.aktTijela())
                .containsExactlyInAnyOrder(StrDocumentType.ZAHTJEV, StrDocumentType.PRIGOVOR,
                        StrDocumentType.ZAHTJEV_PROMJENE_PODATAKA, StrDocumentType.ZAHTJEV_OPOZIVA)
                .allMatch(t -> t.smjer() == ULAZNO);
    }

    /** Opoziv: zahtjev stranke pa obavijest tijela; ostali prijelazi daju samo akt. */
    @Test
    void revocation_yieldsRequestThenNotice() {
        assertThat(StrDocumentType.forTransition(RnStatus.REVOKED, RnTrigger.REVOCATION))
                .contains(StrDocumentType.OPOZIV);
        assertThat(StrDocumentType.pismenaForTransition(RnStatus.REVOKED, RnTrigger.REVOCATION))
                .containsExactly(StrDocumentType.ZAHTJEV_OPOZIVA, StrDocumentType.OPOZIV);
        assertThat(StrDocumentType.pismenaForTransition(RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL))
                .containsExactly(StrDocumentType.POVLACENJE);
        assertThat(StrDocumentType.pismenaForTransition(RnStatus.ACTIVE, RnTrigger.INSPECTION)).isEmpty();
        assertThat(StrDocumentType.values())
                .filteredOn(t -> t.podnesak().isPresent())
                .containsExactly(StrDocumentType.OPOZIV);
        assertThat(StrDocumentType.fromSlug("zahtjev-opoziva")).contains(StrDocumentType.ZAHTJEV_OPOZIVA);
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
