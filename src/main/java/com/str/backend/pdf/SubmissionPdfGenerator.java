package com.str.backend.pdf;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.registration.dto.LessorRequest;
import com.str.backend.registration.dto.RegistrationRequest;
import com.str.backend.registration.dto.AccommodationRequest;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;

@Component
public class SubmissionPdfGenerator {

    private static final Font HEADER = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
    private static final Font LABEL = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
    private static final Font VALUE = FontFactory.getFont(FontFactory.HELVETICA, 10);
    private static final Font SMALL = FontFactory.getFont(FontFactory.HELVETICA, 9);
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd.MM.yyyy. HH:mm");

    /**
     * Generates PDF for registration submission. {@code filingNumber} may be
     * {@code null} (first pass before eGOP reservation) or the final stamped number.
     */
    public byte[] generate(RegistrationRequest req, LessorEntity lessor, String filingNumber) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 40, 40, 50, 40);
            PdfWriter.getInstance(doc, baos);
            doc.open();

            doc.add(new Paragraph("Zahtjev za registraciju smještajne jedinice (STR)", HEADER));
            doc.add(new Paragraph(filingNumber == null ? "Urudžbeni broj: (nedodijeljen)" : filingNumber, SMALL));
            doc.add(new Paragraph("Scenario: " + req.getScenario(), SMALL));
            doc.add(blank());

            doc.add(new Paragraph("Iznajmljivač", LABEL));
            doc.add(lessorTable(req.getLessor(), lessor));
            doc.add(blank());

            for (int i = 0; i < req.getAccommodations().size(); i++) {
                doc.add(new Paragraph("Smještajna jedinica #" + (i + 1), LABEL));
                doc.add(accommodationTable(req.getAccommodations().get(i)));
                doc.add(blank());
            }

            doc.add(new Paragraph("Generirano: " + java.time.LocalDateTime.now().format(DT), SMALL));
            doc.close();
            return baos.toByteArray();
        } catch (DocumentException | java.io.IOException e) {
            throw new IllegalStateException("Failed to generate submission PDF", e);
        }
    }

    private PdfPTable lessorTable(LessorRequest r, LessorEntity persisted) {
        PdfPTable t = baseTable();
        addRow(t, "ID", persisted.getLessorId().toString());
        addRow(t, "Ime i prezime", nullSafe(r.getFirstName()) + " " + nullSafe(r.getLastName()));
        addRow(t, "Adresa", nullSafe(r.getStreet()) + " " + nullSafe(r.getStreetNumber()) + ", "
                + nullSafe(r.getPlace()) + ", " + nullSafe(r.getCounty()));
        addRow(t, "E-mail", nullSafe(r.getEmail()));
        if (r.getLegalEntityName() != null) {
            addRow(t, "Pravna osoba", r.getLegalEntityName() + " (OIB " + nullSafe(r.getRepresentativeOib()) + ")");
            addRow(t, "Zastupnik", nullSafe(r.getLegalRepresentativeName()));
        }
        addRow(t, "Kontakt", nullSafe(r.getContactName()) + " | tel " + nullSafe(r.getPhoneNumber())
                + " | mob " + nullSafe(r.getMobileNumber()));
        return t;
    }

    private PdfPTable accommodationTable(AccommodationRequest s) {
        PdfPTable t = baseTable();
        addRow(t, "Adresa", nullSafe(s.getStreet()) + " " + nullSafe(s.getStreetNumber()) + ", "
                + nullSafe(s.getSettlement()) + ", " + nullSafe(s.getCity()) + ", " + nullSafe(s.getCounty()));
        addRow(t, "Katastar", nullSafe(s.getCadastralMunicipality()) + " / " + nullSafe(s.getCadastralParcelNumber()));
        addRow(t, "Kapacitet", "kreveta " + s.getMaxBeds() + ", gostiju " + s.getMaxGuests());
        addRow(t, "Ponuda", String.valueOf(s.getOfferType()));
        addRow(t, "Zgrada / stanovi / legalizirano",
                s.getBuilding() + " / " + s.getApartments() + " / " + s.getLegalized());
        if (Boolean.TRUE.equals(s.getCoOwnerConsent())) {
            addRow(t, "Suglasnost suvlasnika", "DA, " + s.getConsentDate());
        }
        return t;
    }

    private PdfPTable baseTable() {
        PdfPTable t = new PdfPTable(new float[] {1f, 2.2f});
        t.setWidthPercentage(100);
        t.setSpacingBefore(4);
        return t;
    }

    private void addRow(PdfPTable t, String label, String value) {
        PdfPCell l = new PdfPCell(new Phrase(label, LABEL));
        PdfPCell v = new PdfPCell(new Phrase(value == null ? "" : value, VALUE));
        l.setHorizontalAlignment(Element.ALIGN_LEFT);
        v.setHorizontalAlignment(Element.ALIGN_LEFT);
        t.addCell(l);
        t.addCell(v);
    }

    private Paragraph blank() {
        return new Paragraph(" ", SMALL);
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
