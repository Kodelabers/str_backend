package com.str.backend.document;

import com.lowagie.text.Element;

/**
 * Sekcije akta, <b>redoslijedom kojim se ispisuju</b> — poredak konstanti je poredak na papiru.
 * {@link ZupDocumentRenderer} prolazi kroz {@code values()}, pa nova konstanta automatski dobiva
 * svoje mjesto; ne postoji način da se sekcija doda a zaboravi ispisati.
 *
 * <p>Šest sekcija je čl. 98. st. 1 ZUP-a (zaglavlje, uvod, izreka, obrazloženje, uputa o pravnom
 * lijeku, potpis). {@link #NASLOV}, {@link #PRILOZI} i {@link #DOSTAVNA_LISTA} nisu u ZUP-u nego
 * dolaze iz uredskog poslovanja, na koje ZUP upućuje u čl. 164.; naručitelj ih je izrijekom
 * tražio.
 *
 * <p>{@link #ZAGLAVLJE} i {@link #POTPISNIK} su jedine sekcije koje renderer zna složiti sam
 * (iz {@link DocumentProperties} i urudžbenih oznaka), pa ih predložak ne mora sadržavati —
 * ako ih ipak sadrži, predložak ima prednost.
 */
public enum ZupSection {

    /** Grb, naziv tijela, ustrojstvena jedinica, KLASA, URBROJ, mjesto i datum. */
    ZAGLAVLJE(null, Mode.BLOK, Element.ALIGN_LEFT, 0f),

    /** Adresat (stranka kojoj se akt dostavlja). */
    NASLOV(null, Mode.BLOK, Element.ALIGN_RIGHT, 18f),

    /** Čl. 98. st. 2 — tijelo, propis o nadležnosti, stranka, oznaka predmeta, način pokretanja. */
    UVOD(null, Mode.PROZA, Element.ALIGN_JUSTIFIED, 18f),

    /** Čl. 98. st. 3–4 — sama odluka, kratka i određena, u numeriranim točkama. */
    IZREKA("I Z R E K A", Mode.PROZA, Element.ALIGN_JUSTIFIED, 4f),

    /** Čl. 98. st. 5 — činjenično stanje, ocjena dokaza i propisi na temelju kojih je riješeno. */
    OBRAZLOZENJE("Obrazloženje", Mode.PROZA, Element.ALIGN_JUSTIFIED, 14f),

    /** Čl. 98. st. 6 — koje sredstvo, kojem tijelu, u kojem roku i na koji način. */
    UPUTA_O_PRAVNOM_LIJEKU("Uputa o pravnom lijeku", Mode.PROZA, Element.ALIGN_JUSTIFIED, 14f),

    PRILOZI("Prilozi", Mode.BLOK, Element.ALIGN_LEFT, 14f),

    /** Čl. 98. st. 7–8 — ovlaštena službena osoba i ovjera. */
    POTPISNIK(null, Mode.BLOK, Element.ALIGN_RIGHT, 26f),

    /** Uredsko poslovanje: dolazi ispod potpisa, uz lijevi rub. */
    DOSTAVNA_LISTA("Dostaviti", Mode.BLOK, Element.ALIGN_LEFT, 20f);

    /** Kako se prelomi redaka iz predloška prevode u ispis. */
    public enum Mode {
        /** Svaki redak ostaje svoj redak — adrese, popisi, dostavna lista. */
        BLOK,
        /** Prazan redak dijeli odlomke; unutar odlomka prelomi se spajaju u razmak. */
        PROZA
    }

    private final String heading;
    private final Mode mode;
    private final int alignment;
    private final float spacingBefore;

    ZupSection(String heading, Mode mode, int alignment, float spacingBefore) {
        this.heading = heading;
        this.mode = mode;
        this.alignment = alignment;
        this.spacingBefore = spacingBefore;
    }

    /** Naslov sekcije na ispisu; {@code null} za strukturne blokove koji se ispisuju bez naslova. */
    public String heading() {
        return heading;
    }

    public boolean hasHeading() {
        return heading != null;
    }

    public Mode mode() {
        return mode;
    }

    /** {@code com.lowagie.text.Element} konstanta poravnanja. */
    public int alignment() {
        return alignment;
    }

    public float spacingBefore() {
        return spacingBefore;
    }
}
