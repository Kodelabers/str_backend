package com.str.backend.pdf;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.str.backend.document.DocumentProperties;
import com.str.backend.lessor.LessorEntity;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class SubmissionPdfGenerator {

    private static final Color BLUE       = new Color(46, 116, 181);
    private static final Color BLUE_LIGHT = new Color(189, 215, 238);
    private static final Color BLACK      = Color.BLACK;

    // outer group cells — no vertical divider between group label and content
    private static final int B_LTB = PdfPCell.LEFT  | PdfPCell.TOP | PdfPCell.BOTTOM;
    private static final int B_RTB = PdfPCell.RIGHT | PdfPCell.TOP | PdfPCell.BOTTOM;

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd.MM.yyyy. HH:mm:ss");

    private static final Font FNT_HEADER;
    private static final Font FNT_SECTION;
    private static final Font FNT_LABEL;
    private static final Font FNT_VALUE;
    private static final Font FNT_SMALL;
    private static final Font FNT_IZJAVA;

    static {
        BaseFont bf  = PdfFonts.loadArial();
        FNT_HEADER   = new Font(bf, 13, Font.NORMAL, BLUE);
        FNT_SECTION  = new Font(bf,  9, Font.NORMAL, BLUE);
        FNT_LABEL     = new Font(bf,       9, Font.NORMAL, BLUE);
        FNT_VALUE     = new Font(bf,       9, Font.NORMAL, BLACK);
        FNT_SMALL     = new Font(bf,       8, Font.NORMAL, BLACK);
        FNT_IZJAVA    = new Font(bf,       8, Font.NORMAL, BLACK);
    }

    private final DocumentProperties documentProperties;

    public SubmissionPdfGenerator(DocumentProperties documentProperties) {
        this.documentProperties = documentProperties;
    }

    /**
     * Renderira PDF zahtjeva. Zove se nakon dodjele registracijskog broja.
     *
     * <p>Zahtjev je <b>podnesak</b> po čl. 71. ZUP-a, ne akt po čl. 98., pa nema izreke ni
     * upute o pravnom lijeku i zato ne ide kroz {@code ZupDocumentRenderer}. Čl. 71. st. 2
     * traži naziv tijela kojem se podnesak upućuje, naznaku upravne stvari, ime i adresu te
     * OIB stranke i osobe ovlaštene za zastupanje, te potpis podnositelja.
     */
    public byte[] generate(SubmissionPdfContext ctx) {
        LessorEntity lessor = ctx.lessor();
        String filingNumber = ctx.filingNumber();
        String registrationNumber = ctx.registrationNumber();

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 28, 28, 28, 28);
            PdfWriter.getInstance(doc, baos);
            doc.open();

            // Čl. 71. st. 2 — naziv tijela kojem se podnesak upućuje.
            addAuthorityHeader(doc);

            PdfPTable main = new PdfPTable(new float[]{2.4f, 7.6f});
            main.setWidthPercentage(100);
            main.setSpacingBefore(0);

            // ── ZAHTJEV ──────────────────────────────────────────────────────
            addSectionHeader(main, "ZAHTJEV");

            PdfPTable podnesak = innerTable();
            addInnerRow(podnesak, "Vrsta podneska",
                    "Zahtjev za registraciju smještajne jedinice kratkoročnog najma");
            addInnerRow(podnesak, "Datum zaprimanja podneska", ZonedDateTime.now(ZoneId.of("Europe/Zagreb")).format(DT));
            if (filingNumber != null) {
                addInnerRow(podnesak, "Urudžbeni broj", filingNumber);
            }
            if (registrationNumber != null) {
                addInnerRow(podnesak, "Registracijski broj", registrationNumber);
            }
            addGroupRow(main, "PODNESAK", podnesak);

            // ── PODNOSITELJ ───────────────────────────────────────────────────
            addSectionHeader(main, "PODNOSITELJ");

            boolean isLegal = lessor.getLegalEntityName() != null;
            String oib     = isLegal ? safe(lessor.getRepresentativeOib()) : safe(lessor.getLessorOib());
            String naziv   = isLegal ? safe(lessor.getLegalEntityName())   : fullName(lessor);
            String pravniOblik = isLegal ? "Pravna osoba" : "Fizička osoba";
            String adresa  = safe(lessor.getStreet()) + " " + safe(lessor.getStreetNumber())
                    + ", " + safe(lessor.getPlace());

            PdfPTable maticni = innerTable();
            // Čl. 71. st. 2 traži OIB „ako joj je dodijeljen" — non-EU iznajmljivač ga nema,
            // pa se umjesto praznog retka ispisuje strani porezni broj kad postoji.
            addInnerRow(maticni, "OIB", oib.isEmpty() ? poreznaOznaka(lessor) : oib);
            addInnerRow(maticni, "Naziv / Ime i prezime", naziv);
            addInnerRow(maticni, "Pravni oblik", pravniOblik);
            addInnerRow(maticni, "Adresa sjedišta / prebivališta", adresa);
            // Osoba ovlaštena za zastupanje i njezin OIB — isti stavak ZUP-a; dosad se nije
            // ispisivala ni kod pravnih osoba, gdje je zastupnik jedina fizička osoba na aktu.
            String zastupnik = zastupnik(lessor);
            if (!zastupnik.isEmpty()) {
                addInnerRow(maticni, "Osoba ovlaštena za zastupanje", zastupnik);
            }
            addGroupRow(main, "MATIČNI PODACI\nPODNOSITELJA", maticni);

            PdfPTable kontakt = innerTable();
            addInnerRow(kontakt, "Osobe za kontakt", safe(lessor.getContactName()));
            addInnerRow(kontakt, "Broj telefona",    safe(lessor.getPhoneNumber()));
            addInnerRow(kontakt, "Broj mobitela",    safe(lessor.getMobileNumber()));
            addInnerRow(kontakt, "E-mail",           safe(lessor.getEmail()));
            addInnerRow(kontakt, "Napomena",         safe(lessor.getContactNote()));
            addGroupRow(main, "PODACI ZA\nKONTAKT", kontakt);

            // ── OBJEKTI ───────────────────────────────────────────────────────
            addSectionHeader(main, "OBJEKTI");

            String adresaObjekta = safe(ctx.street()) + " " + safe(ctx.streetNumber())
                    + ", " + safe(ctx.postalCode()) + " " + safe(ctx.cityName()).toUpperCase();

            PdfPTable objektiTop = innerTable();
            addInnerRow(objektiTop, "Skupina objekta",
                    "Objekti u kojima se pružaju ugostiteljske usluge u domaćinstvu");
            addInnerRow(objektiTop, "Adresa objekta", adresaObjekta);
            addGroupRow(main, "OBJEKTI", objektiTop);

            addGroupRow(main, "VRSTA BROJ I\nKAPACITET OBJEKATA\nZA SMJEŠTAJ",
                    buildKapacitetTable(ctx.accommodationName(), ctx.maxBeds(), ctx.typeName()));

            addGroupRow(main, "OSTALI SADRŽAJI", singleValueTable("označeno"));

            doc.add(main);

            // ── Izjava ────────────────────────────────────────────────────────
            Paragraph izjava = new Paragraph(
                    "Izjavljujem da ja, moj bračni ili izvanbračni drug, životni partner i/ili član obitelji s kojim živim u " +
                    "zajedničkom domaćinstvu ne pružamo ugostiteljske usluge u domaćinstvu u smještajnom kapacitetu koji " +
                    "prelazi ukupno:\n" +
                    "• 20 kreveta odnosno ukupno 10 soba, apartmana i kuća za odmor\n" +
                    "• 10 smještajnih jedinica, odnosno 30 gostiju istodobno u vrstama kamp i/ili kamp-odmorište i/ili " +
                    "objektu za robinzonski smještaj, u koje se ne ubrajaju djeca do 12 godina starosti.",
                    FNT_IZJAVA);
            izjava.setSpacingBefore(4);
            doc.add(izjava);

            // ── NAPOMENA ─────────────────────────────────────────────────────
            PdfPTable napomenaTable = new PdfPTable(1);
            napomenaTable.setWidthPercentage(100);
            napomenaTable.setSpacingBefore(6);
            napomenaTable.addCell(bigHeader("NAPOMENA"));
            PdfPCell napomenaBody = new PdfPCell(new Phrase("", FNT_VALUE));
            napomenaBody.setMinimumHeight(18);
            napomenaBody.setBorder(PdfPCell.BOX);
            napomenaBody.setBorderColor(BLUE);
            pad(napomenaBody, 4);
            napomenaTable.addCell(napomenaBody);
            doc.add(napomenaTable);

            // ── PRILOZI ───────────────────────────────────────────────────────
            PdfPTable priloziTable = new PdfPTable(new float[]{5f, 1f});
            priloziTable.setWidthPercentage(100);
            priloziTable.addCell(bigHeader("PRILOZI", 2));
            addPrilogRow(priloziTable, "Dokaz o državljanstvu države članice EU", "NE");
            addPrilogRow(priloziTable,
                    "Dokaz o uporabljivosti građevine (uporabna dozvola i druge isprave kojima se dokazuje\n" +
                    " uporabljivost prema Zakonu o gradnji)", "NE");
            addPrilogRow(priloziTable, "Dokaz o vlasništvu objekta ili vlasništvu zemljišta za kamp", "NE");
            addPrilogRow(priloziTable, "Dokaz prava na oslobađanje od pristojbe", "NE");
            addPrilogRow(priloziTable, "Pisana suglasnost vlasnika ili suvlasnika", "NE");
            addPrilogRow(priloziTable, "Ostali prilozi", "NE");
            doc.add(priloziTable);

            // Čl. 71. st. 5 — podnesak potpisuje stranka odnosno osoba ovlaštena za zastupanje.
            addSubmitterSignature(doc, lessor, isLegal);

            doc.close();
            return baos.toByteArray();
        } catch (DocumentException | IOException e) {
            throw new IllegalStateException("Failed to generate submission PDF", e);
        }
    }

    /** Adresat podneska — bez njega se ne vidi kojem je tijelu zahtjev upućen (čl. 71. st. 2). */
    private void addAuthorityHeader(Document doc) throws DocumentException {
        DocumentProperties.Tijelo tijelo = documentProperties.tijelo();
        if (tijelo.naziv() == null || tijelo.naziv().isBlank()) {
            return;
        }
        StringBuilder sb = new StringBuilder(tijelo.naziv().strip());
        appendLine(sb, tijelo.ustrojstvenaJedinica());
        appendLine(sb, tijelo.adresa());
        appendLine(sb, tijelo.mjesto());

        Paragraph p = new Paragraph(sb.toString(), FNT_VALUE);
        p.setAlignment(Element.ALIGN_LEFT);
        p.setSpacingAfter(10);
        doc.add(p);
    }

    /**
     * Podnesak se predaje elektroničkim putem, pa se po čl. 75. st. 2 ZUP-a smatra
     * vlastoručno potpisanim — potpisna crta bi ovdje bila neistinita. Ispisuje se tko je
     * podnositelj i da je podnesen elektronički.
     */
    private void addSubmitterSignature(Document doc, LessorEntity lessor, boolean isLegal)
            throws DocumentException {
        String potpisnik = isLegal
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

    private static void appendLine(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) {
            sb.append('\n').append(value.strip());
        }
    }

    private static String firstNonBlank(String a, String b) {
        return (a == null || a.isBlank()) ? b : a.strip();
    }

    // ── builders ─────────────────────────────────────────────────────────────

    private PdfPTable buildKapacitetTable(String reqName, int reqMaxBeds, String typeName) {
        PdfPTable t = new PdfPTable(new float[]{3.2f, 2.2f, 1.5f, 1.2f, 1.5f, 1.2f});
        t.setWidthPercentage(100);

        // header row
        addKapacitetHeader(t, "Vrsta objekta");
        addKapacitetHeader(t, "Oznaka/naziv\nobjekta");
        addKapacitetHeader(t, "Tražena\nkategorija");
        addKapacitetHeader(t, "Broj\nkreveta");
        addKapacitetHeader(t, "Broj\npomoćnih\nkreveta");
        addKapacitetHeader(t, "Broj soba");

        // data row — no vertical separators between cells
        String[] dataValues = {typeName != null ? typeName : "", safe(reqName), "",
                String.valueOf(reqMaxBeds), "", ""};
        for (int i = 0; i < dataValues.length; i++) {
            PdfPCell c = new PdfPCell(new Phrase(dataValues[i], FNT_VALUE));
            int border = PdfPCell.TOP;
            if (i == 0) border |= PdfPCell.LEFT;
            if (i == dataValues.length - 1) border |= PdfPCell.RIGHT;
            c.setBorder(border);
            c.setBorderColor(BLUE);
            c.setHorizontalAlignment(Element.ALIGN_CENTER);
            c.setVerticalAlignment(Element.ALIGN_MIDDLE);
            pad(c, 4);
            t.addCell(c);
        }

        // room-type sub-rows
        addTipSobeRow(t, "Jednokrevetne sobe");
        addTipSobeRow(t, "Dvokrevetne sobe");
        addTipSobeRow(t, "Trokrevetne sobe");

        // UKUPNO row
        PdfPCell ukupnoLabel = new PdfPCell(new Phrase("UKUPNO", FNT_SECTION));
        ukupnoLabel.setBorder(PdfPCell.BOX);
        ukupnoLabel.setBorderColor(BLUE);
        ukupnoLabel.setBackgroundColor(BLUE_LIGHT);
        ukupnoLabel.setHorizontalAlignment(Element.ALIGN_CENTER);
        ukupnoLabel.setVerticalAlignment(Element.ALIGN_MIDDLE);
        pad(ukupnoLabel, 3);
        t.addCell(ukupnoLabel);
        String[] ukupnoValues = {"1", "", String.valueOf(reqMaxBeds), "", ""};
        for (int i = 0; i < 4; i++) {
            PdfPCell c = new PdfPCell(new Phrase(ukupnoValues[i], FNT_VALUE));
            c.setBorder(PdfPCell.BOTTOM);
            c.setBorderColor(BLUE);
            c.setHorizontalAlignment(Element.ALIGN_CENTER);
            c.setVerticalAlignment(Element.ALIGN_MIDDLE);
            pad(c, 4);
            t.addCell(c);
        }
        PdfPCell ukupnoLast = new PdfPCell(new Phrase(ukupnoValues[4], FNT_VALUE));
        ukupnoLast.setBorder(PdfPCell.BOTTOM | PdfPCell.RIGHT);
        ukupnoLast.setBorderColor(BLUE);
        ukupnoLast.setHorizontalAlignment(Element.ALIGN_CENTER);
        ukupnoLast.setVerticalAlignment(Element.ALIGN_MIDDLE);
        pad(ukupnoLast, 3);
        t.addCell(ukupnoLast);

        return t;
    }

    private void addKapacitetHeader(PdfPTable t, String text) {
        PdfPCell c = new PdfPCell(new Phrase(text, FNT_SECTION));
        c.setBackgroundColor(BLUE_LIGHT);
        c.setBorder(PdfPCell.BOX);
        c.setBorderColor(BLUE);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setPadding(4);
        t.addCell(c);
    }

    private void addKapacitetCell(PdfPTable t, String text) {
        addKapacitetCell(t, text, null);
    }

    private void addKapacitetCell(PdfPTable t, String text, Color bg) {
        PdfPCell c = new PdfPCell(new Phrase(text, FNT_VALUE));
        if (bg != null) c.setBackgroundColor(bg);
        c.setBorder(PdfPCell.BOX);
        c.setBorderColor(BLUE);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setPadding(4);
        t.addCell(c);
    }

    private void addTipSobeRow(PdfPTable t, String label) {
        PdfPCell lc = new PdfPCell(new Phrase(label, FNT_LABEL));
        lc.setBorder(PdfPCell.BOX);
        lc.setBorderColor(BLUE);
        lc.setHorizontalAlignment(Element.ALIGN_CENTER);
        lc.setVerticalAlignment(Element.ALIGN_MIDDLE);
        pad(lc, 4);
        t.addCell(lc);
        // 4 cells: no borders
        for (int i = 0; i < 4; i++) {
            PdfPCell c = new PdfPCell(new Phrase("", FNT_VALUE));
            c.setBorder(PdfPCell.NO_BORDER);
            pad(c, 4);
            t.addCell(c);
        }
        // last cell closes the right edge only
        PdfPCell last = new PdfPCell(new Phrase("", FNT_VALUE));
        last.setBorder(PdfPCell.RIGHT);
        last.setBorderColor(BLUE);
        pad(last, 3);
        t.addCell(last);
    }

    // left cell: left+top+bottom (outer left edge, no right divider toward content)
    // right cell: right+top+bottom (outer right edge, no left divider)
    private void addGroupRow(PdfPTable main, String groupLabel, PdfPTable inner) {
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

    private void addSectionHeader(PdfPTable main, String text) {
        main.addCell(bigHeader(text, 2));
    }

    private PdfPCell bigHeader(String text) {
        return bigHeader(text, 1);
    }

    private PdfPCell bigHeader(String text, int colspan) {
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

    private PdfPTable innerTable() {
        PdfPTable t = new PdfPTable(new float[]{3.2f, 6.8f});
        t.setWidthPercentage(100);
        return t;
    }

    private void addInnerRow(PdfPTable t, String label, String value) {
        PdfPCell lc = new PdfPCell(new Phrase(label, FNT_LABEL));
        lc.setBorder(PdfPCell.NO_BORDER);
        pad(lc, 4);
        t.addCell(lc);

        PdfPCell vc = new PdfPCell(new Phrase(value, FNT_VALUE));
        vc.setBorder(PdfPCell.NO_BORDER);
        pad(vc, 4);
        t.addCell(vc);
    }

    private PdfPTable singleValueTable(String value) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        PdfPCell c = new PdfPCell(new Phrase(value, FNT_VALUE));
        c.setBorder(PdfPCell.NO_BORDER);
        c.setPadding(4);
        t.addCell(c);
        return t;
    }

    private void addPrilogRow(PdfPTable t, String label, String value) {
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

    private static void pad(PdfPCell c, float lr) {
        c.setPaddingLeft(lr);
        c.setPaddingRight(lr);
        c.setPaddingTop(lr + 1.0f);
        c.setPaddingBottom(lr + 1.0f);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String fullName(LessorEntity l) {
        return (safe(l.getFirstName()) + " " + safe(l.getLastName())).trim().toUpperCase();
    }
}
