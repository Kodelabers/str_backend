package com.str.backend.rn;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfWriter;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.pdf.PdfFonts;
import com.str.backend.rn.dto.RnDetailDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * STR-2.1: generira akte „Dopis o namjeri" i „Nalog za suspenziju/povlačenje" (PDF) koje
 * voditelj postupka prilaže u predmet.
 *
 * <p><b>TODO (zasebni epic, vanjske integracije):</b> dostava akta u KP + obavijest Internetskim
 * platformama (mail/M2M) + urudžbiranje u isti neupravni predmet (eGOP). Ovaj servis samo
 * generira dokument; dostava/urudžba se dodaje kad integracije (BX1/BX2/BX3) budu dostupne.
 */
@Service
public class RnDocumentService {

    private static final Color BLUE  = new Color(46, 116, 181);
    private static final Color BLACK = Color.BLACK;
    private static final Color GREY  = new Color(100, 100, 100);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy.");

    private static final Font FNT_TITLE;
    private static final Font FNT_LABEL;
    private static final Font FNT_BODY;
    private static final Font FNT_META;

    static {
        BaseFont bf = PdfFonts.loadArial();
        FNT_TITLE = new Font(bf, 14, Font.BOLD,   BLUE);
        FNT_LABEL = new Font(bf, 10, Font.BOLD,   BLACK);
        FNT_BODY  = new Font(bf, 10, Font.NORMAL, BLACK);
        FNT_META  = new Font(bf,  9, Font.NORMAL, GREY);
    }

    private final RnRepository rnRepository;

    public RnDocumentService(RnRepository rnRepository) {
        this.rnRepository = rnRepository;
    }

    @Transactional(readOnly = true)
    public byte[] generate(String rn, RnDocumentType type, String reason) {
        RnDetailDto d = rnRepository.findDetail(rn)
                .orElseThrow(() -> new ResourceNotFoundException("rn not found: " + rn));

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 48, 48, 48, 48);
            PdfWriter.getInstance(doc, baos);
            doc.open();

            Paragraph title = new Paragraph(type.title(), FNT_TITLE);
            title.setSpacingAfter(4);
            doc.add(title);

            Paragraph date = new Paragraph("Datum: " + LocalDate.now().format(DATE_FMT), FNT_META);
            date.setSpacingAfter(16);
            doc.add(date);

            labelValue(doc, "Registracijski broj:", d.rn());
            labelValue(doc, "Smještajna jedinica:", blankToDash(d.accommodationName()));
            labelValue(doc, "Adresa objekta:", objectAddress(d));
            labelValue(doc, "Iznajmljivač:", lessorName(d));
            labelValue(doc, "Razlog:", blankToDash(reason));

            Paragraph body = new Paragraph(bodyText(type, d, reason), FNT_BODY);
            body.setSpacingBefore(16);
            body.setSpacingAfter(28);
            doc.add(body);

            Paragraph signoff = new Paragraph("Voditelj postupka\n\n_______________________________", FNT_BODY);
            doc.add(signoff);

            doc.close();
            return baos.toByteArray();
        } catch (DocumentException | IOException e) {
            throw new IllegalStateException("Failed to generate RN document " + type + " for " + rn, e);
        }
    }

    private static void labelValue(Document doc, String label, String value) throws DocumentException {
        Paragraph p = new Paragraph();
        p.add(new com.lowagie.text.Chunk(label + " ", FNT_LABEL));
        p.add(new com.lowagie.text.Chunk(value, FNT_BODY));
        p.setSpacingAfter(2);
        doc.add(p);
    }

    private static String bodyText(RnDocumentType type, RnDetailDto d, String reason) {
        String rn = d.rn();
        String razlog = blankToDash(reason);
        return switch (type) {
            case DOPIS_NAMJERE -> "Obavještavamo Vas o namjeri suspenzije registracijskog broja " + rn +
                    " radi sljedećeg razloga: " + razlog + ". Sukladno čl. 6. Uredbe (EU) 2024/1028, " +
                    "pozivate se da u razumnom roku dostavite ispravak odnosno dopunu dokumentacije. " +
                    "U protivnom će biti izdan Nalog za suspenziju registracijskog broja.";
            case NALOG_SUSPENZIJA -> "Nalaže se suspenzija registracijskog broja " + rn +
                    " s razlogom: " + razlog + ". Registracijski broj time postaje nevažeći za oglašavanje. " +
                    "Suspenzija se može ukinuti reaktivacijom nakon dostave ispravne dokumentacije. " +
                    "Sukladno čl. 6. Uredbe (EU) 2024/1028.";
            case NALOG_POVLACENJE -> "Nalaže se trajno povlačenje (brisanje) registracijskog broja " + rn +
                    " s razlogom: " + razlog + ". Povlačenje je trajno — status se ne može reaktivirati bez " +
                    "novog zahtjeva za registraciju. Sukladno čl. 6. Uredbe (EU) 2024/1028.";
        };
    }

    private static String objectAddress(RnDetailDto d) {
        String streetLine = (safe(d.street()) + " " + safe(d.streetNumber())).trim();
        String cityLine = safe(d.city());
        String joined = streetLine.isEmpty() ? cityLine
                : (cityLine.isEmpty() ? streetLine : streetLine + ", " + cityLine);
        return blankToDash(joined);
    }

    private static String lessorName(RnDetailDto d) {
        if (d.lessorLegalEntityName() != null && !d.lessorLegalEntityName().isBlank()) {
            return d.lessorLegalEntityName();
        }
        String name = (safe(d.lessorFirstName()) + " " + safe(d.lessorLastName())).trim();
        return blankToDash(name);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String blankToDash(String s) {
        return (s == null || s.isBlank()) ? "—" : s.trim();
    }
}
