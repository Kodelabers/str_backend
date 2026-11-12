package com.str.backend.pdf;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.str.backend.document.DocumentProperties;
import com.str.backend.document.FilingReference;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.str.FacilityRooms.Rooms;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static com.str.backend.pdf.PdfObrazac.BLUE;
import static com.str.backend.pdf.PdfObrazac.BLUE_LIGHT;
import static com.str.backend.pdf.PdfObrazac.DT;
import static com.str.backend.pdf.PdfObrazac.FNT_LABEL;
import static com.str.backend.pdf.PdfObrazac.FNT_SECTION;
import static com.str.backend.pdf.PdfObrazac.FNT_VALUE;
import static com.str.backend.pdf.PdfObrazac.addGroupRow;
import static com.str.backend.pdf.PdfObrazac.addInnerRow;
import static com.str.backend.pdf.PdfObrazac.addPrilogRow;
import static com.str.backend.pdf.PdfObrazac.addSectionHeader;
import static com.str.backend.pdf.PdfObrazac.bigHeader;
import static com.str.backend.pdf.PdfObrazac.innerTable;
import static com.str.backend.pdf.PdfObrazac.isBlank;
import static com.str.backend.pdf.PdfObrazac.pad;
import static com.str.backend.pdf.PdfObrazac.safe;

@Component
public class SubmissionPdfGenerator {

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
     * OIB stranke i osobe ovlaštene za zastupanje, te potpis podnositelja. Izgled dijeli s
     * podneskom na akt ({@link PodnesakPdfGenerator}) kroz {@link PdfObrazac}.
     */
    public byte[] generate(SubmissionPdfContext ctx) {
        LessorEntity lessor = ctx.lessor();
        FilingReference filing = ctx.filing();
        String registrationNumber = ctx.registrationNumber();

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 28, 28, 28, 28);
            PdfWriter.getInstance(doc, baos);
            doc.open();

            // Čl. 71. st. 2 — naziv tijela kojem se podnesak upućuje.
            PdfObrazac.addAuthorityHeader(doc, PdfObrazac.adresat(documentProperties.tijelo()));

            PdfPTable main = PdfObrazac.mainTable();

            // ── ZAHTJEV ──────────────────────────────────────────────────────
            addSectionHeader(main, "ZAHTJEV");

            PdfPTable podnesak = innerTable();
            addInnerRow(podnesak, "Vrsta podneska",
                    "Zahtjev za registraciju smještajne jedinice kratkoročnog najma");
            addInnerRow(podnesak, "Datum zaprimanja podneska", ZonedDateTime.now(ZoneId.of("Europe/Zagreb")).format(DT));
            // KLASA (predmet) i URBROJ (pismeno) u zasebnim retcima, kao u zaglavlju obavijesti.
            if (filing != null && !isBlank(filing.klasa())) {
                addInnerRow(podnesak, "KLASA", filing.klasa());
            }
            if (filing != null && !isBlank(filing.urBroj())) {
                addInnerRow(podnesak, "URBROJ", filing.urBroj());
            }
            if (registrationNumber != null) {
                addInnerRow(podnesak, "Registracijski broj", registrationNumber);
            }
            addGroupRow(main, "PODNESAK", podnesak);

            // ── PODNOSITELJ ───────────────────────────────────────────────────
            PdfObrazac.addPodnositelj(main, lessor);

            // ── OBJEKTI ───────────────────────────────────────────────────────
            addSectionHeader(main, "OBJEKTI");
            PdfObrazac.addObjekt(main, ctx.groupName(), ctx.street(), ctx.streetNumber(), ctx.postalCode(),
                    ctx.cityName());

            addGroupRow(main, "VRSTA BROJ I\nKAPACITET OBJEKATA\nZA SMJEŠTAJ",
                    buildKapacitetTable(ctx));

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
                    PdfObrazac.FNT_SMALL);
            izjava.setSpacingBefore(4);
            doc.add(izjava);

            // ── NAPOMENA ─────────────────────────────────────────────────────
            PdfPTable napomenaTable = new PdfPTable(1);
            napomenaTable.setWidthPercentage(100);
            napomenaTable.setSpacingBefore(6);
            napomenaTable.addCell(bigHeader("NAPOMENA", 1));
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
            PdfObrazac.addSubmitterSignature(doc, lessor);

            doc.close();
            return baos.toByteArray();
        } catch (DocumentException | IOException e) {
            throw new IllegalStateException("Failed to generate submission PDF", e);
        }
    }

    // ── builders ─────────────────────────────────────────────────────────────

    /**
     * Tablica kapaciteta. Od stavke 2 kapacitet je jedan podatak — maksimalan broj gostiju,
     * zbroj kreveta i pomoćnih kreveta — pa obrazac više nema zasebne stupce za krevete i
     * pomoćne krevete. Iz zahtjeva se ta podjela ionako ne može rekonstruirati.
     *
     * <p>Kategorija i sobe po vrsti dolaze iz eTurizma i postoje samo za postojeći objekt; novi
     * objekt ih na obrascu ne prijavljuje, pa ti stupci ostaju prazni.
     */
    private PdfPTable buildKapacitetTable(SubmissionPdfContext ctx) {
        PdfPTable t = new PdfPTable(new float[]{3.2f, 2.2f, 1.5f, 1.7f, 1.2f});
        t.setWidthPercentage(100);
        // Nepoznat podatak (postojeći objekt kojem ga eTurizam ne zna) ostaje prazan, kao i
        // ostali neupisani podaci na podnesku — ne „null" ni 0.
        String kapacitet = text(ctx.maxGuests());
        Rooms rooms = ctx.rooms();
        String brojSoba = rooms == null ? "" : text(rooms.ukupno());

        // header row
        addKapacitetHeader(t, "Vrsta objekta");
        addKapacitetHeader(t, "Oznaka/naziv\nobjekta");
        addKapacitetHeader(t, "Tražena\nkategorija");
        addKapacitetHeader(t, "Maksimalan\nbroj gostiju");
        addKapacitetHeader(t, "Broj soba");

        // data row — no vertical separators between cells
        String[] dataValues = {safe(ctx.typeName()), safe(ctx.accommodationName()), safe(ctx.category()),
                kapacitet, brojSoba};
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
        addTipSobeRow(t, "Jednokrevetne sobe", rooms == null ? "" : text(rooms.jednokrevetne()));
        addTipSobeRow(t, "Dvokrevetne sobe", rooms == null ? "" : text(rooms.dvokrevetne()));
        addTipSobeRow(t, "Trokrevetne sobe", rooms == null ? "" : text(rooms.trokrevetne()));

        // UKUPNO row
        PdfPCell ukupnoLabel = new PdfPCell(new Phrase("UKUPNO", FNT_SECTION));
        ukupnoLabel.setBorder(PdfPCell.BOX);
        ukupnoLabel.setBorderColor(BLUE);
        ukupnoLabel.setBackgroundColor(BLUE_LIGHT);
        ukupnoLabel.setHorizontalAlignment(Element.ALIGN_CENTER);
        ukupnoLabel.setVerticalAlignment(Element.ALIGN_MIDDLE);
        pad(ukupnoLabel, 3);
        t.addCell(ukupnoLabel);
        String[] ukupnoValues = {"1", "", kapacitet, brojSoba};
        for (int i = 0; i < ukupnoValues.length - 1; i++) {
            PdfPCell c = new PdfPCell(new Phrase(ukupnoValues[i], FNT_VALUE));
            c.setBorder(PdfPCell.BOTTOM);
            c.setBorderColor(BLUE);
            c.setHorizontalAlignment(Element.ALIGN_CENTER);
            c.setVerticalAlignment(Element.ALIGN_MIDDLE);
            pad(c, 4);
            t.addCell(c);
        }
        PdfPCell ukupnoLast = new PdfPCell(new Phrase(ukupnoValues[ukupnoValues.length - 1], FNT_VALUE));
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

    /** Redak vrste sobe; broj soba te vrste stoji u stupcu „Broj soba". */
    private void addTipSobeRow(PdfPTable t, String label, String count) {
        PdfPCell lc = new PdfPCell(new Phrase(label, FNT_LABEL));
        lc.setBorder(PdfPCell.BOX);
        lc.setBorderColor(BLUE);
        lc.setHorizontalAlignment(Element.ALIGN_CENTER);
        lc.setVerticalAlignment(Element.ALIGN_MIDDLE);
        pad(lc, 4);
        t.addCell(lc);
        // 3 cells: no borders
        for (int i = 0; i < 3; i++) {
            PdfPCell c = new PdfPCell(new Phrase("", FNT_VALUE));
            c.setBorder(PdfPCell.NO_BORDER);
            pad(c, 4);
            t.addCell(c);
        }
        // last cell closes the right edge only
        PdfPCell last = new PdfPCell(new Phrase(count, FNT_VALUE));
        last.setBorder(PdfPCell.RIGHT);
        last.setHorizontalAlignment(Element.ALIGN_CENTER);
        last.setBorderColor(BLUE);
        pad(last, 3);
        t.addCell(last);
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

    /** Broj za ćeliju tablice; nepoznat ostaje prazan. */
    private static String text(Integer value) {
        return value == null ? "" : String.valueOf(value);
    }
}
