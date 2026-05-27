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
import com.str.backend.lessor.LessorEntity;
import com.str.backend.registration.dto.RegistrationExternalRequest;
import com.str.backend.registration.dto.RegistrationRequest;
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

    public byte[] generate(RegistrationRequest req, String countyName, LessorEntity lessor, String filingNumber) {
        return generate(req, countyName, lessor, filingNumber, null);
    }

    public byte[] generate(RegistrationRequest req, String countyName, LessorEntity lessor,
                           String filingNumber, String typeName) {
        return generate0(req.name(), req.street(), req.streetNumber(), req.postalCode(), req.cityId(), req.maxBeds(),
                countyName, lessor, filingNumber, typeName);
    }

    public byte[] generate(RegistrationExternalRequest req, String countyName, LessorEntity lessor,
                           String filingNumber, String typeName) {
        return generate0(req.name(), req.street(), req.streetNumber(), req.postalCode(), req.cityId(), req.maxBeds(),
                countyName, lessor, filingNumber, typeName);
    }

    private byte[] generate0(String reqName, String reqStreet, String reqStreetNumber,
                              String reqPostalCode, String reqCityId, int reqMaxBeds,
                              String countyName, LessorEntity lessor,
                              String filingNumber, String typeName) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 28, 28, 28, 28);
            PdfWriter.getInstance(doc, baos);
            doc.open();

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
            addInnerRow(maticni, "OIB", oib);
            addInnerRow(maticni, "Naziv / Ime i prezime", naziv);
            addInnerRow(maticni, "Pravni oblik", pravniOblik);
            addInnerRow(maticni, "Adresa sjedišta / prebivališta", adresa);
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

            String adresaObjekta = safe(reqStreet) + " " + safe(reqStreetNumber)
                    + ", " + safe(reqPostalCode) + " " + safe(reqCityId).toUpperCase();

            PdfPTable objektiTop = innerTable();
            addInnerRow(objektiTop, "Skupina objekta",
                    "Objekti u kojima se pružaju ugostiteljske usluge u domaćinstvu");
            addInnerRow(objektiTop, "Adresa objekta", adresaObjekta);
            addGroupRow(main, "OBJEKTI", objektiTop);

            addGroupRow(main, "VRSTA BROJ I\nKAPACITET OBJEKATA\nZA SMJEŠTAJ",
                    buildKapacitetTable(reqName, reqMaxBeds, typeName));

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

            doc.close();
            return baos.toByteArray();
        } catch (DocumentException | IOException e) {
            throw new IllegalStateException("Failed to generate submission PDF", e);
        }
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
