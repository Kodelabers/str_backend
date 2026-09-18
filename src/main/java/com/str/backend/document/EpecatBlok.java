package com.str.backend.document;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.lowagie.text.BadElementException;
import com.lowagie.text.Chunk;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;

import java.awt.Color;
import java.util.Arrays;
import java.util.Map;

/**
 * Vizualni prikaz kvalificiranog elektroničkog pečata na dnu akta, po uzoru na isprave Porezne
 * uprave — tako ga je naručitelj zatražio u predlošku od 11.09.2026.
 *
 * <p>Raspored: lijevo grb i naziv tijela preko šest redaka podataka o pečatu, a ispod QR kod i
 * tekst o provjeri izvornika. Podaci dolaze iz {@code epecat.*} ključeva konteksta, koje
 * {@link ZupContextFactory} puni samo kad je pečat uključen.
 */
final class EpecatBlok {

    private static final Color OKVIR = new Color(128, 128, 128);
    private static final float DEBLJINA_OKVIRA = 0.5f;
    private static final float PADDING = 3f;
    private static final float VELICINA_QR = 80f;
    private static final float PADDING_QR = 12f;
    private static final float GRB_SIRINA = 34f;
    private static final float GRB_VISINA = 42.9f;
    private static final float PRORED = 9.5f;
    /** Blok u predlošku je širok ≈436 pt od 481 pt širine sadržaja; reci su visoki ≈14,5 pt. */
    private static final float SIRINA_POSTO = 90.6f;

    private static final String[][] RETCI = {
            {"Vrijeme izdavanja:", "epecat.vrijemeIzdavanja"},
            {"Izdavatelj certifikata:", "epecat.izdavateljCertifikata"},
            {"Naziv certifikata:", "epecat.nazivCertifikata"},
            {"Algoritam potpisa:", "epecat.algoritam"},
            {"Broj zapisa:", "epecat.brojZapisa"},
            {"Kontrolni broj:", "epecat.kontrolniBroj"},
    };

    private final Font tekst;
    private final Font tijelo;

    EpecatBlok(BaseFont obicni, BaseFont podebljani) {
        this.tekst = new Font(obicni, 8f, Font.NORMAL, Color.BLACK);
        this.tijelo = new Font(podebljani, 6.5f, Font.NORMAL, Color.BLACK);
    }

    PdfPTable izgradi(Map<String, String> ctx, Image grb) throws BadElementException {
        PdfPTable table = new PdfPTable(new float[]{26.8f, 19.4f, 53.8f});
        table.setWidthPercentage(SIRINA_POSTO);
        table.setHorizontalAlignment(Element.ALIGN_CENTER);

        PdfPCell znak = celija();
        znak.setRowspan(RETCI.length);
        znak.setHorizontalAlignment(Element.ALIGN_CENTER);
        znak.setVerticalAlignment(Element.ALIGN_MIDDLE);
        grb.scaleAbsolute(GRB_SIRINA, GRB_VISINA);
        grb.setAlignment(Image.ALIGN_CENTER);
        znak.addElement(grb);
        Chunk rh = new Chunk("REPUBLIKA HRVATSKA", tijelo);
        rh.setCharacterSpacing(0.8f);
        znak.addElement(centrirano(new Paragraph(rh), 4f));
        znak.addElement(centrirano(new Paragraph(uDvaRetka(
                ctx.getOrDefault("tijelo.nazivVelikim", "")), tijelo), 1f));
        table.addCell(znak);

        for (String[] redak : RETCI) {
            table.addCell(tekstCelija(redak[0]));
            table.addCell(tekstCelija(ctx.getOrDefault(redak[1], "")));
        }

        PdfPCell qr = celija();
        qr.setHorizontalAlignment(Element.ALIGN_CENTER);
        qr.setVerticalAlignment(Element.ALIGN_MIDDLE);
        qr.setPadding(PADDING_QR);
        // Bez URL-a portala nema ni QR-a (vidi ZupContextFactory.putEpecat) — ćelija ostaje
        // prazna, blok se i dalje ispisuje.
        String sadrzaj = ctx.getOrDefault("epecat.qr", "");
        if (!sadrzaj.isBlank()) {
            Image kod = qr(sadrzaj);
            kod.setAlignment(Image.ALIGN_CENTER);
            qr.addElement(kod);
        } else {
            qr.setFixedHeight(VELICINA_QR + 2 * PADDING_QR);
        }
        table.addCell(qr);

        PdfPCell provjera = celija();
        provjera.setColspan(2);
        provjera.setVerticalAlignment(Element.ALIGN_MIDDLE);
        provjera.setPadding(6f);
        Paragraph uputa = new Paragraph(ctx.getOrDefault("epecat.provjera", ""), tekst);
        uputa.setAlignment(Element.ALIGN_JUSTIFIED);
        uputa.setLeading(PRORED);
        provjera.addElement(uputa);
        Paragraph potvrda = new Paragraph(ctx.getOrDefault("epecat.potvrda", ""), tekst);
        potvrda.setAlignment(Element.ALIGN_JUSTIFIED);
        potvrda.setLeading(PRORED);
        potvrda.setSpacingBefore(PRORED);
        provjera.addElement(potvrda);
        table.addCell(provjera);

        return table;
    }

    /**
     * „MINISTARSTVO / TURIZMA I SPORTA" — kao „MINISTARSTVO FINANCIJA / POREZNA UPRAVA" kod
     * Porezne. Pušten slobodnom prelamanju, naziv u uskom stupcu puca kao „…TURIZMA I / SPORTA".
     */
    static String uDvaRetka(String naziv) {
        int razmak = naziv.indexOf(' ');
        return razmak < 0 ? naziv : naziv.substring(0, razmak) + "\n" + naziv.substring(razmak + 1);
    }

    private PdfPCell tekstCelija(String vrijednost) {
        PdfPCell cell = celija();
        cell.setPhrase(new Phrase(PRORED, vrijednost, tekst));
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return cell;
    }

    private static PdfPCell celija() {
        PdfPCell cell = new PdfPCell();
        cell.setBorderColor(OKVIR);
        cell.setBorderWidth(DEBLJINA_OKVIRA);
        cell.setPadding(PADDING);
        return cell;
    }

    private static Paragraph centrirano(Paragraph p, float razmakPrije) {
        p.setAlignment(Element.ALIGN_CENTER);
        p.setLeading(8f);
        p.setSpacingBefore(razmakPrije);
        return p;
    }

    /**
     * QR kao 1-bitna slika, jedan piksel po modulu. OpenPDF nema QR simbologiju, a slika bez
     * interpolacije ostaje oštra pri povećanju — jednako dobro kao vektor, uz manje koda.
     */
    static Image qr(String sadrzaj) throws BadElementException {
        Moduli moduli = qrModuli(sadrzaj);
        Image slika = Image.getInstance(moduli.stranica(), moduli.stranica(), 1, 1, moduli.bitovi());
        slika.scaleAbsolute(VELICINA_QR, VELICINA_QR);
        return slika;
    }

    /**
     * Moduli QR koda spakirani u 1-bitne redove, točno onako kako ulaze u PDF.
     *
     * <p>Izdvojeno iz {@link #qr} da se može dekodirati natrag u testu: {@code Image.getRawData()}
     * ne vraća predane bajtove (OpenPDF ih pakira), pa se ispravnost bitova ne može provjeriti
     * kroz gotovu sliku.
     */
    record Moduli(int stranica, byte[] bitovi) {}

    static Moduli qrModuli(String sadrzaj) {
        BitMatrix matrica;
        try {
            matrica = new QRCodeWriter().encode(sadrzaj, BarcodeFormat.QR_CODE, 0, 0, Map.of(
                    EncodeHintType.MARGIN, 0,
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.CHARACTER_SET, "UTF-8"));
            // Prazan sadržaj i predugačak URL zxing prijavljuje različitim tipovima
            // (IllegalArgumentException, odnosno WriterException); oba su kvar konfiguracije
            // pečata, a ne kvar renderera, pa idu kao DocumentTemplateException.
        } catch (WriterException | IllegalArgumentException e) {
            throw new DocumentTemplateException(
                    "QR kod e-pečata nije moguće složiti za sadržaj duljine " + sadrzaj.length(), e);
        }
        int n = matrica.getWidth();
        int bajtovaPoRedu = (n + 7) / 8;
        byte[] podaci = new byte[bajtovaPoRedu * n];
        // DeviceGray s 1 bitom: 1 je bijelo, 0 crno — kreće se od bijelog pa se gase moduli.
        Arrays.fill(podaci, (byte) 0xFF);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (matrica.get(x, y)) {
                    podaci[y * bajtovaPoRedu + x / 8] &= (byte) ~(0x80 >> (x % 8));
                }
            }
        }
        return new Moduli(n, podaci);
    }
}
