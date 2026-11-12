package com.str.backend.pdf;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.str.backend.document.DocumentProperties;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.registries.eovlastenja.LegalForm;

import java.awt.Color;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Zajednički izgled tabličnih podnesaka stranke (zahtjev, očitovanje i prigovor): fontovi, boje, zaglavlje
 * tijela, sekcije i grupe, blok podnositelja i objekta te potpis po čl. 75. st. 2 ZUP-a. Podnesci po čl. 71.
 * ZUP-a nisu akti po čl. 98., pa ne idu kroz {@code ZupDocumentRenderer}, nego kroz ove pomoćne metode.
 */
public final class PdfObrazac {

    static final Color BLUE = new Color(46, 116, 181);
    static final Color BLUE_LIGHT = new Color(189, 215, 238);
    static final Color BLACK = Color.BLACK;

    // outer group cells — no vertical divider between group label and content
    private static final int B_LTB = PdfPCell.LEFT | PdfPCell.TOP | PdfPCell.BOTTOM;
    private static final int B_RTB = PdfPCell.RIGHT | PdfPCell.TOP | PdfPCell.BOTTOM;

    /** Datum i vrijeme zaprimanja podneska, po hrvatskom vremenu. */
    static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd.MM.yyyy. HH:mm:ss");

    static final Font FNT_HEADER;
    static final Font FNT_SECTION;
    static final Font FNT_LABEL;
    static final Font FNT_VALUE;
    static final Font FNT_SMALL;

    static {
        BaseFont bf = PdfFonts.loadArial();
        FNT_HEADER = new Font(bf, 13, Font.NORMAL, BLUE);
        FNT_SECTION = new Font(bf, 9, Font.NORMAL, BLUE);
        FNT_LABEL = new Font(bf, 9, Font.NORMAL, BLUE);
        FNT_VALUE = new Font(bf, 9, Font.NORMAL, BLACK);
        FNT_SMALL = new Font(bf, 8, Font.NORMAL, BLACK);
    }

    private PdfObrazac() {
    }

    /**
     * Adresat podneska Ministarstvu (čl. 71. st. 2): naziv tijela, ustrojstvena jedinica, adresa i mjesto, redak
     * po redak. Isti je na zahtjevu i na podnesku na akt u postupku MINTS-a. Bez naziva tijela adresata nema.
     */
    public static List<String> adresat(DocumentProperties.Tijelo tijelo) {
        if (tijelo.naziv() == null || tijelo.naziv().isBlank()) {
            return List.of();
        }
        return Arrays.asList(tijelo.naziv().strip(), tijelo.ustrojstvenaJedinica(), tijelo.adresa(), tijelo.mjesto());
    }

    // ── zaglavlje i potpis ───────────────────────────────────────────────────

    /**
     * Adresat podneska (čl. 71. st. 2) — tijelo kojem je podnesak upućen, redak po redak. Prazni retci se
     * izostavljaju; bez naziva tijela zaglavlja nema.
     */
    static void addAuthorityHeader(Document doc, List<String> retci) throws DocumentException {
        if (retci.isEmpty() || isBlank(retci.get(0))) {
            return;
        }
        String tekst = retci.stream()
                .filter(r -> !isBlank(r))
                .map(String::strip)
                .collect(Collectors.joining("\n"));
        Paragraph p = new Paragraph(tekst, FNT_VALUE);
        p.setAlignment(Element.ALIGN_LEFT);
        p.setSpacingAfter(10);
        doc.add(p);
    }

    /**
     * Podnesak se predaje elektroničkim putem, pa se po čl. 75. st. 2 ZUP-a smatra
     * vlastoručno potpisanim — potpisna crta bi ovdje bila neistinita. Ispisuje se tko je
     * podnositelj i da je podnesen elektronički. Bez podnositelja (podaci nedostaju) ime ostaje prazno.
     */
    static void addSubmitterSignature(Document doc, LessorEntity lessor) throws DocumentException {
        String potpisnik = lessor == null ? "" : isLegal(lessor)
                ? firstNonBlank(lessor.getLegalRepresentativeName(), fullName(lessor))
                : fullName(lessor);

        Paragraph p = new Paragraph("Podnositelj: " + potpisnik, FNT_VALUE);
        p.setAlignment(Element.ALIGN_RIGHT);
        p.setSpacingBefore(14);
        doc.add(p);

        Paragraph note = new Paragraph(
                "Podnesak je dostavljen elektroničkim putem te se sukladno članku 75. stavku 2."
                        + " Zakona o općem upravnom postupku smatra vlastoručno potpisanim.",
                FNT_SMALL);
        note.setAlignment(Element.ALIGN_RIGHT);
        note.setSpacingBefore(2);
        doc.add(note);
    }

    // ── sekcije ──────────────────────────────────────────────────────────────

    /** Glavna tablica: lijevo naziv grupe, desno njezini retci. */
    static PdfPTable mainTable() {
        PdfPTable main = new PdfPTable(new float[]{2.4f, 7.6f});
        main.setWidthPercentage(100);
        main.setSpacingBefore(0);
        return main;
    }

    /**
     * Sekcija PODNOSITELJ: matični podaci (čl. 71. st. 2 — ime, adresa i OIB stranke te osobe ovlaštene za
     * zastupanje) i podaci za kontakt. Bez podnositelja (podaci nedostaju) retci ostaju prazni — podnesak se
     * i tada mora moći izdati.
     */
    static void addPodnositelj(PdfPTable main, LessorEntity lessor) {
        addSectionHeader(main, "PODNOSITELJ");
        if (lessor == null) {
            PdfPTable maticni = innerTable();
            for (String label : List.of("OIB", "Naziv / Ime i prezime", "Pravni oblik", "Adresa sjedišta / prebivališta")) {
                addInnerRow(maticni, label, "");
            }
            addGroupRow(main, "MATIČNI PODACI\nPODNOSITELJA", maticni);
            PdfPTable kontakt = innerTable();
            for (String label : List.of("Osobe za kontakt", "Broj telefona", "Broj mobitela", "E-mail", "Napomena")) {
                addInnerRow(kontakt, label, "");
            }
            addGroupRow(main, "PODACI ZA\nKONTAKT", kontakt);
            return;
        }

        boolean isLegal = isLegal(lessor);
        // OIB podnositelja je uvijek lessorOib — kod pravne osobe to je OIB tvrtke. Zastupnikov
        // OIB (representativeOib) ide u redak „Osoba ovlaštena za zastupanje" niže.
        String oib = safe(lessor.getLessorOib());
        String naziv = isLegal ? safe(lessor.getLegalEntityName()) : fullName(lessor);
        String adresa = safe(lessor.getStreet()) + " " + safe(lessor.getStreetNumber())
                + ", " + safe(lessor.getPlace());

        PdfPTable maticni = innerTable();
        // Čl. 71. st. 2 traži OIB „ako joj je dodijeljen" — non-EU iznajmljivač ga nema,
        // pa se umjesto praznog retka ispisuje strani porezni broj kad postoji.
        addInnerRow(maticni, "OIB", oib.isEmpty() ? poreznaOznaka(lessor) : oib);
        addInnerRow(maticni, "Naziv / Ime i prezime", naziv);
        addInnerRow(maticni, "Pravni oblik", pravniOblik(lessor, isLegal));
        addInnerRow(maticni, "Adresa sjedišta / prebivališta", adresa);
        String zastupnik = zastupnik(lessor);
        if (!zastupnik.isEmpty()) {
            addInnerRow(maticni, "Osoba ovlaštena za zastupanje", zastupnik);
        }
        addGroupRow(main, "MATIČNI PODACI\nPODNOSITELJA", maticni);

        PdfPTable kontakt = innerTable();
        addInnerRow(kontakt, "Osobe za kontakt", safe(lessor.getContactName()));
        addInnerRow(kontakt, "Broj telefona", safe(lessor.getPhoneNumber()));
        addInnerRow(kontakt, "Broj mobitela", safe(lessor.getMobileNumber()));
        addInnerRow(kontakt, "E-mail", safe(lessor.getEmail()));
        addInnerRow(kontakt, "Napomena", safe(lessor.getContactNote()));
        addGroupRow(main, "PODACI ZA\nKONTAKT", kontakt);
    }

    /**
     * Grupa OBJEKTI: skupina i adresa objekta. Postojeći eTurizam objekt najčešće nema ulicu ni kućni broj —
     * prazni dijelovi se izostavljaju, da u podnesku ne ostanu viseći zarezi („ , 21000 SPLIT").
     */
    static void addObjekt(PdfPTable main, String groupName, String street, String streetNumber,
                          String postalCode, String cityName) {
        String adresaObjekta = joinNonBlank(", ",
                joinNonBlank(" ", street, streetNumber),
                joinNonBlank(" ", postalCode, safe(cityName).toUpperCase()));

        PdfPTable objekt = innerTable();
        addInnerRow(objekt, "Skupina objekta", safe(groupName));
        addInnerRow(objekt, "Adresa objekta", adresaObjekta);
        addGroupRow(main, "OBJEKTI", objekt);
    }

    // left cell: left+top+bottom (outer left edge, no right divider toward content)
    // right cell: right+top+bottom (outer right edge, no left divider)
    static void addGroupRow(PdfPTable main, String groupLabel, PdfPTable inner) {
        PdfPCell left = new PdfPCell(new Phrase(groupLabel, FNT_SECTION));
        left.setHorizontalAlignment(Element.ALIGN_CENTER);
        left.setVerticalAlignment(Element.ALIGN_MIDDLE);
        left.setBorder(B_LTB);
        left.setBorderColor(BLUE);
        pad(left, 5);
        main.addCell(left);

        PdfPCell right = new PdfPCell(inner);
        pad(right, 0);
        right.setBorder(B_RTB);
        right.setBorderColor(BLUE);
        main.addCell(right);
    }

    static void addSectionHeader(PdfPTable main, String text) {
        main.addCell(bigHeader(text, 2));
    }

    static PdfPCell bigHeader(String text, int colspan) {
        PdfPCell c = new PdfPCell(new Phrase(text, FNT_HEADER));
        c.setColspan(colspan);
        c.setBackgroundColor(BLUE_LIGHT);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setBorder(PdfPCell.BOX);
        c.setBorderColor(BLUE);
        c.setPadding(4);
        return c;
    }

    static PdfPTable innerTable() {
        PdfPTable t = new PdfPTable(new float[]{3.2f, 6.8f});
        t.setWidthPercentage(100);
        return t;
    }

    static void addInnerRow(PdfPTable t, String label, String value) {
        PdfPCell lc = new PdfPCell(new Phrase(label, FNT_LABEL));
        lc.setBorder(PdfPCell.NO_BORDER);
        pad(lc, 4);
        t.addCell(lc);

        PdfPCell vc = new PdfPCell(new Phrase(value, FNT_VALUE));
        vc.setBorder(PdfPCell.NO_BORDER);
        pad(vc, 4);
        t.addCell(vc);
    }

    static void addPrilogRow(PdfPTable t, String label, String value) {
        PdfPCell lc = new PdfPCell(new Phrase(label, FNT_LABEL));
        lc.setBorder(PdfPCell.BOX);
        lc.setBorderColor(BLUE);
        pad(lc, 4);
        t.addCell(lc);

        PdfPCell vc = new PdfPCell(new Phrase(value, FNT_VALUE));
        vc.setBorder(PdfPCell.BOX);
        vc.setBorderColor(BLUE);
        vc.setHorizontalAlignment(Element.ALIGN_CENTER);
        pad(vc, 4);
        t.addCell(vc);
    }

    /** Redak popisa u tablici s jednim stupcem (npr. nazivi priloženih datoteka). */
    static void addListRow(PdfPTable t, String text) {
        PdfPCell c = new PdfPCell(new Phrase(text, FNT_VALUE));
        c.setBorder(PdfPCell.BOX);
        c.setBorderColor(BLUE);
        pad(c, 4);
        t.addCell(c);
    }

    static void pad(PdfPCell c, float lr) {
        c.setPaddingLeft(lr);
        c.setPaddingRight(lr);
        c.setPaddingTop(lr + 1.0f);
        c.setPaddingBottom(lr + 1.0f);
    }

    // ── podnositelj ──────────────────────────────────────────────────────────

    /**
     * Zastava je mjerodavna (e-Zastupanja je postavljaju uvijek). Naziv bez zastave i dalje znači pravnu
     * osobu: tako ga upisuju setLegalEntity i registar subjekata.
     */
    static boolean isLegal(LessorEntity lessor) {
        return lessor.isLegalEntityOwner() || lessor.getLegalEntityName() != null;
    }

    /**
     * Čl. 71. st. 2 traži OIB „ako joj je dodijeljen". Non-EU podnositelj ga nema, pa se
     * umjesto praznog retka — koji izgleda kao propust u ispunjavanju — ispisuje strani
     * porezni broj ili izričita napomena, isto kao na aktima.
     */
    private static String poreznaOznaka(LessorEntity lessor) {
        String tax = safe(lessor.getTaxNumber());
        return tax.isEmpty() ? "nije dodijeljen" : "strani porezni broj: " + tax;
    }

    private static String zastupnik(LessorEntity lessor) {
        String ime = safe(lessor.getLegalRepresentativeName());
        if (ime.isEmpty()) {
            return "";
        }
        String oib = safe(lessor.getRepresentativeOib());
        return oib.isEmpty() ? ime : ime + ", OIB: " + oib;
    }

    static String fullName(LessorEntity l) {
        return (safe(l.getFirstName()) + " " + safe(l.getLastName())).trim().toUpperCase();
    }

    /** Obrt i OPG iz e-Ovlaštenja (T13) imaju svoj naziv; ostala pravna osoba ostaje „Pravna osoba”. */
    private static String pravniOblik(LessorEntity lessor, boolean isLegal) {
        if (lessor.getLegalForm() == LegalForm.OPG) {
            return "Obiteljsko poljoprivredno gospodarstvo";
        }
        if (lessor.getLegalForm() == LegalForm.OBRT) {
            return "Obrt";
        }
        return isLegal ? "Pravna osoba" : "Fizička osoba";
    }

    // ── tekst ────────────────────────────────────────────────────────────────

    /** Dugi tekst (npr. naziv datoteke) skraćen na {@code max} znakova, s „…" na kraju. */
    static String skrati(String s, int max) {
        String t = safe(s);
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    static String safe(String s) {
        return s == null ? "" : s;
    }

    static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String firstNonBlank(String a, String b) {
        return (a == null || a.isBlank()) ? b : a.strip();
    }

    static String joinNonBlank(String separator, String... parts) {
        return Arrays.stream(parts)
                .filter(p -> p != null && !p.isBlank())
                .map(String::strip)
                .collect(Collectors.joining(separator));
    }
}
