package com.str.backend.document;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.draw.LineSeparator;
import com.str.backend.pdf.PdfFonts;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ispisuje akt iz predloška u PDF.
 *
 * <p>Sekcije idu <b>poretkom konstanti {@link ZupSection}</b>, bez obzira na redoslijed u
 * datoteci — predložak određuje sadržaj, ne izgled. Naslov akta ide između uvoda i izreke, jer
 * uvod po ZUP-u završava riječju „donosi"/„izdaje".
 *
 * <p>{@link ZupSection#ZAGLAVLJE} i {@link ZupSection#POTPISNIK} renderer zna složiti sam iz
 * konfiguracije; predložak ih smije nadjačati.
 */
@Component
public class ZupDocumentRenderer {

    private static final Color CRNA = Color.BLACK;
    private static final Color SIVA = new Color(110, 110, 110);

    private static final float MARGINA_LIJEVO = 64f;
    private static final float MARGINA_DESNO = 52f;
    private static final float MARGINA_GORE = 42f;
    private static final float MARGINA_DOLJE = 46f;

    /** Prored teksta. Stisnut toliko da tipičan akt stane na jednu stranicu. */
    private static final float PRORED = 13f;

    private static final Font FNT_TEKST;
    private static final Font FNT_NASLOV_SEKCIJE;
    private static final Font FNT_NASLOV_AKTA;
    private static final Font FNT_SITNO;

    static {
        BaseFont obicni = PdfFonts.loadArial();
        BaseFont podebljani = PdfFonts.loadArialBold();
        FNT_TEKST = new Font(obicni, 10.5f, Font.NORMAL, CRNA);
        FNT_NASLOV_SEKCIJE = new Font(podebljani, 10.5f, Font.NORMAL, CRNA);
        FNT_NASLOV_AKTA = new Font(podebljani, 13f, Font.NORMAL, CRNA);
        FNT_SITNO = new Font(obicni, 8f, Font.NORMAL, SIVA);
    }

    /** Zaglavlje kad ga predložak ne definira. Prazni se redci izbacuju pri ispisu. */
    private static final String ZAGLAVLJE_UGRADENO = """
            REPUBLIKA HRVATSKA
            ${tijelo.naziv}
            ${tijelo.ustrojstvenaJedinica}
            ${akt.klasaRedak}
            ${akt.urbrojRedak}
            ${akt.mjestoDatum}""";

    /** Potpisnik kad ga predložak ne definira (čl. 98. st. 7). */
    private static final String POTPISNIK_UGRADEN = """
            ${potpisnik.funkcija}
            ${potpisnik.ime}""";

    private final DocumentProperties properties;

    public ZupDocumentRenderer(DocumentProperties properties) {
        this.properties = properties;
    }

    public byte[] render(ZupTemplate template, Map<String, String> ctx) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4,
                    MARGINA_LIJEVO, MARGINA_DESNO, MARGINA_GORE, MARGINA_DOLJE);
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new Podnozje(ctx.getOrDefault("akt.klasa", "")));
            doc.open();

            // Prolaz kroz values() umjesto niza ručnih poziva: nova sekcija u ZupSection time
            // automatski dobiva svoje mjesto na papiru. S ručnim popisom bi se dodala u enum,
            // prošla validaciju predloška i tiho izostala iz PDF-a.
            // Potpis i dostavna lista idu u jedan blok koji se ne prelama — inače zna ispasti
            // da na drugoj stranici stoji samo „Dostaviti" s dva retka, što na aktu izgleda
            // kao da dokument nije dovršen.
            PdfPCell zavrsna = zavrsnaCelija();
            Odrediste stranica = doc::add;
            Odrediste zavrsni = zavrsna::addElement;

            for (ZupSection section : ZupSection.values()) {
                switch (section) {
                    case ZAGLAVLJE -> zaglavlje(doc, template, ctx);
                    case POTPISNIK -> potpisnik(zavrsni, template, ctx);
                    case DOSTAVNA_LISTA -> sekcija(zavrsni, template, ctx, section);
                    default -> sekcija(stranica, template, ctx, section);
                }
                // Naslov akta ide između uvoda i izreke: uvod po ZUP-u završava riječju
                // „donosi"/„izdaje", pa naslov mora doći odmah iza njega.
                if (section == ZupSection.UVOD) {
                    naslovAkta(doc, ctx);
                }
            }
            doc.add(zavrsniBlokOd(zavrsna));

            doc.close();
            return out.toByteArray();
        } catch (DocumentException | IOException e) {
            throw new DocumentTemplateException(
                    "Neuspješan render akta " + template.type() + " (" + template.origin() + ")", e);
        }
    }

    private void zaglavlje(Document doc, ZupTemplate template, Map<String, String> ctx)
            throws DocumentException {
        String izvor = template.section(ZupSection.ZAGLAVLJE).orElse(ZAGLAVLJE_UGRADENO);
        ispisi(doc::add, izvor, ctx, template, ZupSection.ZAGLAVLJE,
                ZupSection.ZAGLAVLJE.alignment(), ZupSection.ZAGLAVLJE.spacingBefore());
        LineSeparator crta = new LineSeparator(0.7f, 100f, CRNA, Element.ALIGN_CENTER, -4f);
        Paragraph p = new Paragraph();
        p.add(crta);
        p.setSpacingBefore(6f);
        doc.add(p);
    }

    private void naslovAkta(Document doc, Map<String, String> ctx) throws DocumentException {
        Paragraph p = new Paragraph(ctx.getOrDefault("akt.naslov", ""), FNT_NASLOV_AKTA);
        p.setAlignment(Element.ALIGN_CENTER);
        p.setSpacingBefore(26f);
        p.setSpacingAfter(16f);
        doc.add(p);
    }

    private void potpisnik(Odrediste odrediste, ZupTemplate template, Map<String, String> ctx)
            throws DocumentException {
        String izvor = template.section(ZupSection.POTPISNIK).orElse(POTPISNIK_UGRADEN);
        ispisi(odrediste, izvor, ctx, template, ZupSection.POTPISNIK,
                ZupSection.POTPISNIK.alignment(), ZupSection.POTPISNIK.spacingBefore());

        // Čl. 98. st. 8: akt iz informacijskog sustava ovjerava se isključivo kvalificiranim
        // elektroničkim pečatom. Dok pečata nema, klauzula se ne ispisuje — tvrdnja o ovjeri
        // na nepečaćenom aktu bila bi neistinita.
        DocumentProperties.Epecat epecat = properties.epecat();
        if (epecat.enabled() && epecat.klauzula() != null && !epecat.klauzula().isBlank()) {
            Paragraph p = new Paragraph(epecat.klauzula().strip(), FNT_SITNO);
            p.setAlignment(Element.ALIGN_LEFT);
            p.setSpacingBefore(24f);
            odrediste.dodaj(p);
        }
    }

    private void sekcija(Odrediste odrediste, ZupTemplate template, Map<String, String> ctx,
                         ZupSection section) throws DocumentException {
        String izvor = template.section(section).orElse(null);
        if (izvor == null) {
            return;
        }
        float razmakPrije = section.spacingBefore();
        if (section.hasHeading()) {
            Paragraph naslov = new Paragraph(section.heading(), FNT_NASLOV_SEKCIJE);
            naslov.setAlignment(section == ZupSection.IZREKA
                    ? Element.ALIGN_CENTER : Element.ALIGN_LEFT);
            naslov.setSpacingBefore(razmakPrije);
            naslov.setSpacingAfter(8f);
            odrediste.dodaj(naslov);
            razmakPrije = 0f;
        }
        ispisi(odrediste, izvor, ctx, template, section, section.alignment(), razmakPrije);
    }

    private void ispisi(Odrediste odrediste, String izvor, Map<String, String> ctx,
                        ZupTemplate template, ZupSection section, int poravnanje,
                        float razmakPrije) throws DocumentException {
        // Ugrađene sekcije nisu u .txt datoteci, pa upućivanje na nju u poruci o grešci vodi
        // na krivi trag.
        String izvorište = template.has(section)
                ? template.origin() + " [" + section + "]"
                : "ZupDocumentRenderer, ugrađena sekcija [" + section + "]";
        String vezano = ZupPlaceholders.bind(izvor, ctx, izvorište);
        boolean prvi = true;
        for (String odlomak : odlomci(vezano, section.mode())) {
            Paragraph p = new Paragraph(odlomak, FNT_TEKST);
            p.setAlignment(poravnanje);
            p.setLeading(PRORED);
            p.setSpacingBefore(prvi ? razmakPrije : 8f);
            odrediste.dodaj(p);
            prvi = false;
        }
    }

    /**
     * Prazan redak dijeli odlomke. Unutar odlomka se u {@link ZupSection.Mode#PROZA} prelomi
     * spajaju u razmak (izvorni prelom je samo omatanje u datoteci), a u
     * {@link ZupSection.Mode#BLOK} ostaju.
     *
     * <p>Redak koji nakon vezanja placeholdera ostane prazan izbacuje se — tako neobavezni
     * podaci (zastupnik, poštanski broj) ne ostavljaju rupu u adresnom bloku.
     */
    static List<String> odlomci(String tekst, ZupSection.Mode mode) {
        List<String> rezultat = new ArrayList<>();
        for (String blok : tekst.split("\\R\\s*\\R")) {
            List<String> redci = new ArrayList<>();
            for (String redak : blok.split("\\R")) {
                String ociscen = redak.strip().replaceAll("\\s{2,}", " ");
                if (!ociscen.isEmpty()) {
                    redci.add(ociscen);
                }
            }
            if (redci.isEmpty()) {
                continue;
            }
            rezultat.add(String.join(mode == ZupSection.Mode.BLOK ? "\n" : " ", redci));
        }
        return rezultat;
    }

    /**
     * Kamo ide složeni element — izravno na stranicu ili u ćeliju završnog bloka. Postoji jer
     * {@code Document} i {@code PdfPCell} primaju elemente različitim metodama.
     */
    @FunctionalInterface
    private interface Odrediste {
        void dodaj(Element element) throws DocumentException;
    }

    private static PdfPCell zavrsnaCelija() {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0f);
        return cell;
    }

    /** Tablica bez okvira koja služi samo da se završni blok ne prelomi preko stranica. */
    private static PdfPTable zavrsniBlokOd(PdfPCell cell) {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        table.setKeepTogether(true);
        table.addCell(cell);
        return table;
    }

    /** KLASA i broj stranice u podnožju — akt zna imati više stranica i listovi se razdvajaju. */
    private static final class Podnozje extends PdfPageEventHelper {

        private final String klasa;

        private Podnozje(String klasa) {
            this.klasa = klasa;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            Rectangle stranica = document.getPageSize();
            String tekst = klasa.isBlank()
                    ? "Stranica " + writer.getPageNumber()
                    : "KLASA: " + klasa + " · stranica " + writer.getPageNumber();
            PdfContentByte platno = writer.getDirectContent();
            ColumnText.showTextAligned(platno, Element.ALIGN_CENTER, new Phrase(tekst, FNT_SITNO),
                    (stranica.getLeft() + stranica.getRight()) / 2, stranica.getBottom() + 28f, 0);
        }
    }
}
