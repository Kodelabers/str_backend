package com.str.backend.document;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import com.str.backend.pdf.PdfFonts;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
 * <p>Okvir akta (zaglavlje s grbom, potpis nazivom tijela, blok e-pečata, bez podnožja) slijedi
 * predložak MINT-a od 11.09.2026. Razmaci su izmjereni na tom predlošku, pa ih ne treba
 * „zaokruživati" bez usporedbe s njim.
 *
 * <p>{@link ZupSection#ZAGLAVLJE} i {@link ZupSection#POTPISNIK} renderer zna složiti sam iz
 * konfiguracije; predložak ih smije nadjačati. Nadjačano zaglavlje mijenja samo urudžbeni blok
 * (KLASA, URBROJ, mjesto i datum) — grb i naziv tijela ostaju.
 */
@Component
public class ZupDocumentRenderer {

    private static final Color CRNA = Color.BLACK;
    private static final Color SIVA = new Color(110, 110, 110);

    private static final float MARGINA_LIJEVO = 64f;
    private static final float MARGINA_DESNO = 50f;
    private static final float MARGINA_GORE = 42f;
    private static final float MARGINA_DOLJE = 36f;

    /** Prored teksta. Stisnut toliko da tipičan akt stane na jednu stranicu. */
    private static final float PRORED = 13f;

    private static final String GRB = "documents/grb-rh.png";
    private static final float GRB_SIRINA = 46f;
    private static final float GRB_VISINA = 58f;
    /** Lijevi rub grba od lijeve margine. */
    private static final float GRB_UVLAKA = 57f;
    /** Grb je 5 pt ispod gornje margine (y≈47). */
    private static final float GRB_ODMAK_GORE = 5f;
    private static final float RAZMAK_ISPOD_GRBA = 2f;
    /**
     * „P/&lt;jop&gt;" je na y≈67, ispod retka rezerviranog za barkod, i završava ≈8 pt prije
     * desne margine.
     */
    private static final float JOP_ODMAK_GORE = 21f;
    private static final float JOP_ODMAK_DESNO = 8.5f;
    /** „REPUBLIKA HRVATSKA" i naziv tijela uvučeni su koliko Wordova ćelija ima unutarnji rub. */
    private static final float ZAGLAVLJE_UVLAKA = 5f;
    /** Potpis počinje na x≈321 pt, kao u predlošku. */
    private static final float POTPIS_UVLAKA = 257f;

    private static final BaseFont ARIAL = PdfFonts.loadArial();
    private static final BaseFont ARIAL_BOLD = PdfFonts.loadArialBold();

    private static final Font FNT_TEKST = new Font(ARIAL, 10.5f, Font.NORMAL, CRNA);
    private static final Font FNT_NASLOV_SEKCIJE = new Font(ARIAL_BOLD, 10.5f, Font.NORMAL, CRNA);
    private static final Font FNT_NASLOV_AKTA = new Font(ARIAL_BOLD, 13f, Font.NORMAL, CRNA);
    private static final Font FNT_ZAGLAVLJE = new Font(ARIAL_BOLD, 11f, Font.NORMAL, CRNA);
    private static final Font FNT_JOP = new Font(ARIAL, 11f, Font.NORMAL, CRNA);
    private static final Font FNT_SITNO = new Font(ARIAL, 8f, Font.NORMAL, SIVA);

    private static final float PODNOZJE_SIRINA = 400f;
    private static final float PODNOZJE_VISINA = 12f;
    private static final float PODNOZJE_ODMAK_DNO = 20f;

    /** Urudžbeni blok kad ga predložak ne definira. Prazni se redci izbacuju pri ispisu. */
    private static final String URUDZBENI_BLOK_UGRADEN = """
            ${akt.klasaRedak}
            ${akt.urbrojRedak}
            ${akt.mjestoDatum}""";

    /**
     * Potpis kad ga predložak ne definira: naziv tijela, a ispod njega ime službene osobe samo
     * ako je konfigurirano (čl. 98. st. 7–8; akt ovjerava e-pečat tijela).
     */
    private static final String POTPISNIK_UGRADEN = """
            ${tijelo.nazivVelikim}
            ${potpisnik.ime}""";

    /** Podnesak stranke (čl. 71. st. 2) potpisuje podnositelj, ne tijelo. */
    private static final String POTPISNIK_STRANKE = """
            ${stranka.naziv}""";

    private final DocumentProperties properties;
    private final EpecatBlok epecatBlok = new EpecatBlok(ARIAL, ARIAL_BOLD);

    /**
     * Bajtovi grba, ne {@link Image}: slika nosi promjenjivo stanje (skaliranje, poravnanje),
     * a renderi idu paralelno s više threadova — svaki render gradi svoju instancu.
     */
    private final byte[] grb;

    public ZupDocumentRenderer(DocumentProperties properties) {
        this.properties = properties;
        this.grb = ucitajGrb();
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
            // Potpis, dostavna lista i e-pečat idu u jedan blok koji se ne prelama — inače zna
            // ispasti da na drugoj stranici stoji samo „Dostaviti" s dva retka, što na aktu
            // izgleda kao da dokument nije dovršen.
            PdfPCell zavrsna = celijaBezOkvira();
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
            epecat(zavrsni, template, ctx);
            // Razmak ispred potpisa ide na tablicu: ćelija ignorira razmak ispred svog prvog
            // elementa, a potpis je uvijek prvi.
            PdfPTable zavrsniBlok = zavrsniBlokOd(zavrsna);
            zavrsniBlok.setSpacingBefore(ZupSection.POTPISNIK.spacingBefore());
            doc.add(zavrsniBlok);

            doc.close();
            return out.toByteArray();
        } catch (DocumentException | IOException e) {
            throw new DocumentTemplateException(
                    "Neuspješan render akta " + template.type() + " (" + template.origin() + ")", e);
        }
    }

    /**
     * Grb i tijelo lijevo, eGOP oznake pismena desno, a ispod urudžbeni blok. Bez crte ispod
     * zaglavlja — naručitelj ju je izbacio.
     *
     * <p>Podnesak stranke (npr. prigovor) nije akt tijela, pa ne nosi grb ni naziv tijela kao
     * izdavatelja — tijelo je u njemu adresat. Ostaje samo urudžbeni blok.
     */
    private void zaglavlje(Document doc, ZupTemplate template, Map<String, String> ctx)
            throws DocumentException, IOException {
        if (aktTijela(template)) {
            zaglavljeTijela(doc, ctx);
        }
        String izvor = template.section(ZupSection.ZAGLAVLJE).orElse(URUDZBENI_BLOK_UGRADEN);
        ispisi(doc::add, izvor, ctx, template, ZupSection.ZAGLAVLJE,
                ZupSection.ZAGLAVLJE.alignment(), ZupSection.ZAGLAVLJE.spacingBefore(), 0f);
    }

    private void zaglavljeTijela(Document doc, Map<String, String> ctx)
            throws DocumentException, IOException {
        PdfPTable table = new PdfPTable(new float[]{52.5f, 47.5f});
        table.setWidthPercentage(100);

        // Ćelija ne poštuje uvlaku slike ni razmak ispred prvog elementa, pa grb ide kao Chunk
        // u odlomak s uvlakom, a vertikalni pomak daje padding ćelije.
        PdfPCell lijevo = celijaBezOkvira();
        lijevo.setPaddingTop(GRB_ODMAK_GORE);
        Image slika = grb();
        slika.scaleAbsolute(GRB_SIRINA, GRB_VISINA);
        Paragraph grbOdlomak = new Paragraph();
        grbOdlomak.add(new Chunk(slika, 0f, 0f, true));
        grbOdlomak.setIndentationLeft(GRB_UVLAKA);
        lijevo.addElement(grbOdlomak);
        lijevo.addElement(zaglavljeRedak("REPUBLIKA HRVATSKA", RAZMAK_ISPOD_GRBA));
        lijevo.addElement(zaglavljeRedak(ctx.getOrDefault("tijelo.nazivVelikim", ""), 0f));
        table.addCell(lijevo);

        // Desno je u predlošku barkod jedinstvene oznake pismena, prazan redak pa „P/<jop>".
        // Barkod čeka eGOP-ov jedinstvenaOznakaPismena; JOP već stoji na svom mjestu ispod
        // njega, da ne skače kad barkod dođe.
        PdfPCell desno = celijaBezOkvira();
        desno.setPaddingTop(JOP_ODMAK_GORE);
        desno.setPaddingRight(JOP_ODMAK_DESNO);
        String jop = ctx.getOrDefault("akt.jopRedak", "");
        if (!jop.isBlank()) {
            Paragraph p = new Paragraph(jop, FNT_JOP);
            p.setAlignment(Element.ALIGN_RIGHT);
            p.setLeading(PRORED);
            desno.addElement(p);
        }
        table.addCell(desno);
        doc.add(table);
    }

    /**
     * Izlazni akt izdaje tijelo; ulazno pismeno (podnesak stranke) piše stranka. O tome ovisi
     * zaglavlje s grbom, tko potpisuje i smije li se na dokument staviti pečat tijela.
     */
    private static boolean aktTijela(ZupTemplate template) {
        return template.type().smjer() == StrDocumentType.Smjer.IZLAZNO;
    }

    private static Paragraph zaglavljeRedak(String tekst, float razmakPrije) {
        Paragraph p = new Paragraph(tekst, FNT_ZAGLAVLJE);
        p.setIndentationLeft(ZAGLAVLJE_UVLAKA);
        p.setLeading(12.7f);
        p.setSpacingBefore(razmakPrije);
        return p;
    }

    private void naslovAkta(Document doc, Map<String, String> ctx) throws DocumentException {
        Paragraph p = new Paragraph(ctx.getOrDefault("akt.naslov", ""), FNT_NASLOV_AKTA);
        p.setAlignment(Element.ALIGN_CENTER);
        p.setSpacingBefore(26f);
        p.setSpacingAfter(8f);
        doc.add(p);
    }

    private void potpisnik(Odrediste odrediste, ZupTemplate template, Map<String, String> ctx)
            throws DocumentException {
        String izvor = template.section(ZupSection.POTPISNIK)
                .orElse(aktTijela(template) ? POTPISNIK_UGRADEN : POTPISNIK_STRANKE);
        ispisi(odrediste, izvor, ctx, template, ZupSection.POTPISNIK,
                ZupSection.POTPISNIK.alignment(), ZupSection.POTPISNIK.spacingBefore(),
                POTPIS_UVLAKA);
    }

    /**
     * Čl. 98. st. 8: akt iz informacijskog sustava ovjerava se isključivo kvalificiranim
     * elektroničkim pečatom. Dok pečata nema, blok se ne ispisuje — tvrdnja o ovjeri na
     * nepečaćenom aktu bila bi neistinita. Podnesak stranke tijelo ne pečati.
     */
    private void epecat(Odrediste odrediste, ZupTemplate template, Map<String, String> ctx)
            throws DocumentException, IOException {
        if (!properties.epecat().enabled() || !aktTijela(template)) {
            return;
        }
        Paragraph razmak = new Paragraph(" ", FNT_TEKST);
        razmak.setLeading(PRORED);
        razmak.setSpacingBefore(29f);
        odrediste.dodaj(razmak);
        odrediste.dodaj(epecatBlok.izgradi(ctx, grb()));
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
            naslov.setAlignment(Element.ALIGN_LEFT);
            naslov.setSpacingBefore(razmakPrije);
            naslov.setSpacingAfter(8f);
            odrediste.dodaj(naslov);
            razmakPrije = 0f;
        }
        ispisi(odrediste, izvor, ctx, template, section, section.alignment(), razmakPrije, 0f);
    }

    private void ispisi(Odrediste odrediste, String izvor, Map<String, String> ctx,
                        ZupTemplate template, ZupSection section, int poravnanje,
                        float razmakPrije, float uvlaka) throws DocumentException {
        // Ugrađene sekcije nisu u .txt datoteci, pa upućivanje na nju u poruci o grešci vodi
        // na krivi trag.
        String izvorište = template.has(section)
                ? template.origin() + " [" + section + "]"
                : "ZupDocumentRenderer, ugrađena sekcija [" + section + "]";
        // Cijeli izvor se veže prvi put samo radi provjere, da poruka o grešci nabroji sve
        // nepoznate placeholdere odjednom, a ne samo one iz prvog neispravnog retka.
        ZupPlaceholders.bind(izvor, ctx, izvorište);
        String vezano = vezi(izvor, ctx, izvorište);
        boolean prvi = true;
        for (String odlomak : odlomci(vezano, section.mode())) {
            Paragraph p = new Paragraph(odlomak, FNT_TEKST);
            p.setAlignment(poravnanje);
            p.setLeading(PRORED);
            p.setIndentationLeft(uvlaka);
            p.setSpacingBefore(prvi ? razmakPrije : 8f);
            odrediste.dodaj(p);
            prvi = false;
        }
    }

    /**
     * Veže placeholdere redak po redak i izbacuje redak predloška koji je nakon vezanja ostao
     * prazan — tako neobavezni podaci (URBROJ prije urudžbiranja, zastupnik, poštanski broj) ne
     * ostavljaju rupu. Da taj redak ostane, postao bi prazan redak i {@link #odlomci} bi na njemu
     * prelomio odlomak: KLASA i datum, odnosno ime i adresa, razišli bi se za razmak odlomka.
     *
     * <p>Prazni redci samog predloška ostaju — oni su namjerne granice odlomaka.
     */
    static String vezi(String izvor, Map<String, String> ctx, String izvorište) {
        List<String> redci = new ArrayList<>();
        for (String redak : izvor.split("\\R", -1)) {
            String vezan = ZupPlaceholders.bind(redak, ctx, izvorište);
            if (redak.isBlank() || !vezan.isBlank()) {
                redci.add(vezan);
            }
        }
        return String.join("\n", redci);
    }

    /**
     * Prazan redak dijeli odlomke. Unutar odlomka se u {@link ZupSection.Mode#PROZA} prelomi
     * spajaju u razmak (izvorni prelom je samo omatanje u datoteci), a u
     * {@link ZupSection.Mode#BLOK} ostaju. Prazni redci unutar teksta se ne ispisuju.
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

    private Image grb() throws DocumentException, IOException {
        return Image.getInstance(grb);
    }

    /**
     * Grb je obvezan dio zaglavlja akta (Uredba o uredskom poslovanju), pa njegov izostanak
     * ruši podizanje — kao i predložak kojem nedostaje sekcija — umjesto da akti tiho izlaze
     * bez njega.
     */
    private static byte[] ucitajGrb() {
        try (InputStream in = new ClassPathResource(GRB).getInputStream()) {
            byte[] bytes = in.readAllBytes();
            Image.getInstance(bytes);
            return bytes;
        } catch (IOException | DocumentException e) {
            throw new DocumentTemplateException("Grb nije čitljiv: " + GRB, e);
        }
    }

    private static PdfPCell celijaBezOkvira() {
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

    /**
     * KLASA i broj stranice u podnožju, <b>samo na aktu s više stranica</b>. Predložak naručitelja
     * je jednostranični i podnožje ne nosi, ali akt s obrazloženjem i uputom ide na dvije
     * stranice — a list bez KLASE i broja stranice, razdvojen od prvog, nije moguće povezati s
     * predmetom.
     *
     * <p>Ukupan broj stranica zna se tek na kraju, pa se na svaku stranicu ostavi prazan
     * {@code PdfTemplate} koji se ispuni u {@code onCloseDocument} — ili ostane prazan kad akt
     * stane na jednu stranicu.
     */
    private static final class Podnozje extends PdfPageEventHelper {

        private final String klasa;
        private final List<PdfTemplate> mjesta = new ArrayList<>();

        private Podnozje(String klasa) {
            this.klasa = klasa;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            Rectangle stranica = document.getPageSize();
            PdfTemplate mjesto = writer.getDirectContent()
                    .createTemplate(PODNOZJE_SIRINA, PODNOZJE_VISINA);
            mjesta.add(mjesto);
            writer.getDirectContent().addTemplate(mjesto,
                    (stranica.getLeft() + stranica.getRight() - PODNOZJE_SIRINA) / 2,
                    stranica.getBottom() + PODNOZJE_ODMAK_DNO);
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            int ukupno = mjesta.size();
            if (ukupno < 2) {
                return;
            }
            for (int i = 0; i < ukupno; i++) {
                String tekst = (klasa.isBlank() ? "" : "KLASA: " + klasa + " · ")
                        + "stranica " + (i + 1) + " od " + ukupno;
                ColumnText.showTextAligned(mjesta.get(i), Element.ALIGN_CENTER,
                        new Phrase(tekst, FNT_SITNO), PODNOZJE_SIRINA / 2, 2f, 0);
            }
        }
    }
}
