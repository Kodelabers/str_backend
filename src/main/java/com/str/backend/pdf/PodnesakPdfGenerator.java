package com.str.backend.pdf;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.PageSize;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.str.backend.common.Zagreb;
import com.str.backend.document.FilingReference;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static com.str.backend.pdf.PdfObrazac.DT;
import static com.str.backend.pdf.PdfObrazac.addGroupRow;
import static com.str.backend.pdf.PdfObrazac.addInnerRow;
import static com.str.backend.pdf.PdfObrazac.addSectionHeader;
import static com.str.backend.pdf.PdfObrazac.bigHeader;
import static com.str.backend.pdf.PdfObrazac.innerTable;
import static com.str.backend.pdf.PdfObrazac.isBlank;

/**
 * PDF podneska stranke na akt — očitovanja ili prigovora (T12). Glavni dokument ulaznog pismena, kao PDF
 * zahtjeva ({@link SubmissionPdfGenerator}): isti izgled, ali skraćen — bez kapaciteta, ostalih sadržaja,
 * izjave i napomene. Dokumenti koje je stranka učitala nisu u PDF-u, nego idu uz pismeno kao prilozi; PDF ih
 * samo nabraja.
 *
 * <p>Podnesak po čl. 71. ZUP-a: adresat (Ministarstvo ili županija koja vodi postupak), naznaka stvari
 * (vrsta podneska, akt na koji se odnosi), podnositelj i potpis po čl. 75. st. 2.
 */
@Component
public class PodnesakPdfGenerator {

    /** Naziv datoteke u popisu priloga — dulji se skraćuje, da ne razvuče tablicu. */
    static final int NAZIV_PRILOGA_MAX = 120;

    public byte[] generate(PodnesakPdfContext ctx) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 28, 28, 28, 28);
            PdfWriter.getInstance(doc, baos);
            doc.open();

            PdfObrazac.addAuthorityHeader(doc, ctx.tijelo());

            PdfPTable main = PdfObrazac.mainTable();

            // ── naslov i PODNESAK ─────────────────────────────────────────────
            addSectionHeader(main, ctx.naslov());

            PdfPTable podnesak = innerTable();
            addInnerRow(podnesak, "Vrsta podneska", ctx.vrstaPodneska());
            addInnerRow(podnesak, "Datum zaprimanja podneska",
                    ctx.zaprimljeno() == null ? "" : ctx.zaprimljeno().atZone(Zagreb.ZONE).format(DT));
            FilingReference filing = ctx.filing();
            if (filing != null && !isBlank(filing.klasa())) {
                addInnerRow(podnesak, "KLASA", filing.klasa());
            }
            if (filing != null && !isBlank(filing.urBroj())) {
                addInnerRow(podnesak, "URBROJ", filing.urBroj());
            }
            addInnerRow(podnesak, "Registracijski broj", PdfObrazac.safe(ctx.registrationNumber()));
            if (!isBlank(ctx.odnosiSeNa())) {
                addInnerRow(podnesak, "Odnosi se na", ctx.odnosiSeNa());
            }
            addGroupRow(main, "PODNESAK", podnesak);

            // ── PODNOSITELJ ───────────────────────────────────────────────────
            PdfObrazac.addPodnositelj(main, ctx.lessor());

            // ── OBJEKT ────────────────────────────────────────────────────────
            addSectionHeader(main, "OBJEKTI");
            PdfObrazac.addObjekt(main, ctx.groupName(), ctx.street(), ctx.streetNumber(), ctx.postalCode(),
                    ctx.cityName());

            doc.add(main);

            // ── PRILOZI: što je podnositelj učitao ────────────────────────────
            PdfPTable prilozi = new PdfPTable(1);
            prilozi.setWidthPercentage(100);
            prilozi.setSpacingBefore(6);
            prilozi.addCell(bigHeader("PRILOZI", 1));
            int rbr = 0;
            for (String naziv : ctx.prilozi()) {
                PdfObrazac.addListRow(prilozi, ++rbr + ". " + PdfObrazac.skrati(naziv, NAZIV_PRILOGA_MAX));
            }
            doc.add(prilozi);

            PdfObrazac.addSubmitterSignature(doc, ctx.lessor());

            doc.close();
            return baos.toByteArray();
        } catch (DocumentException | IOException e) {
            throw new IllegalStateException("Failed to generate podnesak PDF", e);
        }
    }
}
