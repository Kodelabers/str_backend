package com.str.backend.pdf;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.str.backend.document.DocumentProperties;
import com.str.backend.document.FilingReference;
import com.str.backend.document.StrDocumentType;
import com.str.backend.lessor.LessorEntity;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PDF podneska na akt (očitovanje, prigovor): skraćeni obrazac zahtjeva — adresat, naslov i vrsta po statusu,
 * urudžbene oznake, akt na koji se odnosi, podnositelj, objekt i popis učitanih datoteka; bez kapaciteta,
 * ostalih sadržaja, izjave i napomene.
 *
 * <p>Uz {@code -Dpodnesak.primjer=<putanja>} test {@link #zupanija_zaglavljeOznakeIPrilozi} sprema PDF na tu
 * putanju, za vizualnu provjeru.
 */
class PodnesakPdfGeneratorTest {

    private static final String RN = "HR180001787531203985";
    private static final Instant ZAPRIMLJENO = Instant.parse("2026-11-12T10:15:30Z");

    private final PodnesakPdfGenerator generator = new PodnesakPdfGenerator();

    @Test
    void zupanija_zaglavljeOznakeIPrilozi() throws Exception {
        byte[] pdf = generator.generate(context(StrDocumentType.PRIGOVOR_SUSPENZIJE,
                Arrays.asList("Splitsko-dalmatinska županija", "Upravni odjel za turizam"),
                new FilingReference("UP/I-334-06/26-01/7", "2181-26-2")));
        String primjer = System.getProperty("podnesak.primjer");
        if (primjer != null && !primjer.isBlank()) {
            Files.write(Path.of(primjer), pdf);
        }

        String text = textOf(pdf);
        String sazeto = sazeto(text);

        assertThat(text).startsWith("Splitsko-dalmatinska županija Upravni odjel za turizam");
        assertThat(text).doesNotContain("Ministarstvo");
        assertThat(text).contains("PRIGOVOR NA SUSPENZIJU REGISTRACIJSKOG BROJA");
        // Ekstraktor susjedne ćelije spaja bez razmaka, pa se uspoređuje tekst bez razmaka.
        assertThat(sazeto).contains(sazeto("Vrsta podneska Prigovor na suspenziju registracijskog broja"));
        assertThat(sazeto).contains(sazeto("Datum zaprimanja podneska 12.11.2026. 11:15:30"));
        assertThat(sazeto).contains("KLASAUP/I-334-06/26-01/7URBROJ2181-26-2");
        assertThat(sazeto).contains("Registracijskibroj" + RN);
        assertThat(sazeto).contains(sazeto("Odnosi se na Obavijest o suspenziji registracijskog broja"
                + " (KLASA: UP/I-334-06/26-01/7, URBROJ: 2181-26-1)"));
        assertThat(sazeto).contains("OIB12312312316").contains(sazeto("Naziv / Ime i prezime PERO PERIĆ"));
        assertThat(sazeto).contains("E-mailpero@example.hr");
        assertThat(sazeto).contains(sazeto("Skupina objekta Objekti u kojima se pružaju ugostiteljske usluge u domaćinstvu"));
        assertThat(sazeto).contains(sazeto("Adresa objekta Bilice II 7, 21000 SPLIT"));
        assertThat(text).contains("PRILOZI 1. prigovor.docx 2. dokaz.png 3. uplatnica.pdf");
        assertThat(text).contains("Podnositelj: PERO PERIĆ");
        assertThat(text).contains("članku 75. stavku 2.");
        // Skraćeno: bez kapaciteta, ostalih sadržaja, izjave, napomene i fiksne liste priloga zahtjeva.
        assertThat(text).doesNotContain("ZAHTJEV").doesNotContain("KAPACITET").doesNotContain("UKUPNO")
                .doesNotContain("OSTALI SADRŽAJI").doesNotContain("Izjavljujem").doesNotContain("NAPOMENA")
                .doesNotContain("Dokaz o državljanstvu").doesNotContain("null");
    }

    /** Postupak MINTS-a: adresat je Ministarstvo, kao na zahtjevu; vrsta prati status broja pri predaji. */
    @Test
    void ministarstvo_zaglavljeKaoZahtjev_vrstaPoStatusu() throws Exception {
        DocumentProperties.Tijelo tijelo = new DocumentProperties.Tijelo("ministarstvo turizma i sporta",
                "87892589782", "Prisavlje 14", "Zagreb", "Uprava za turizam", null);

        String text = textOf(generator.generate(context(StrDocumentType.OCITOVANJE_POVLACENJA,
                PdfObrazac.adresat(tijelo), new FilingReference("334-06/26-11/3", "529-06-03/01-26-4"))));

        assertThat(text).startsWith("Ministarstvo turizma i sporta Uprava za turizam Prisavlje 14 Zagreb");
        assertThat(text).contains("OČITOVANJE NA PRIJEDLOG POVLAČENJA REGISTRACIJSKOG BROJA");
        assertThat(sazeto(text)).contains(sazeto("Vrsta podneska Očitovanje na prijedlog povlačenja registracijskog broja"))
                .contains("KLASA334-06/26-11/3URBROJ529-06-03/01-26-4");
    }

    /** Bez oznaka (ne bi se smjelo dogoditi) retci KLASA i URBROJ izostaju — ne „null". */
    @Test
    void bezOznaka_bezRedakaKlaseIUrbroja() throws Exception {
        String text = textOf(generator.generate(context(StrDocumentType.OCITOVANJE_SUSPENZIJE,
                List.of("Grad Zagreb"), null)));

        // „KLASA:" i „URBROJ:" ostaju samo u retku akta na koji se podnesak odnosi.
        assertThat(sazeto(text)).doesNotContain("KLASAUP").doesNotContain("URBROJ2").doesNotContain("null")
                .containsPattern("Registracijskibroj" + RN + "Odnosise");
    }

    /** Bez vremena predaje, iznajmljivača i objekta: prazna polja, bez iznimke i bez „null". */
    @Test
    void nedostajuciPodaci_praznaPolja() throws Exception {
        PodnesakPdfContext ctx = new PodnesakPdfContext(List.of("Zadarska županija"),
                StrDocumentType.OCITOVANJE_SUSPENZIJE.naslov(), StrDocumentType.OCITOVANJE_SUSPENZIJE.vrstaPismenaNaziv(),
                null, new FilingReference("K", "U-1"), RN, null, null, null, null, null, null, null, List.of());

        String text = textOf(generator.generate(ctx));

        assertThat(sazeto(text)).contains("Datumzaprimanjapodneska" + "KLASA").contains("OIBNaziv")
                .contains("Skupinaobjekta" + "Adresaobjekta").endsWith("PRILOZI").contains("Podnositelj:Podnesak");
        assertThat(text).doesNotContain("null").doesNotContain("Odnosi se na");
    }

    /** Pravna osoba: naziv tvrtke i pravni oblik, zastupnik s OIB-om, potpisuje zastupnik. */
    @Test
    void pravnaOsoba_nazivZastupnikIPotpis() throws Exception {
        LessorEntity tvrtka = LessorEntity.create("Pero", "Perić", "Riva", "1", "Split",
                "Splitsko-dalmatinska županija", "info@sunce.hr");
        tvrtka.setLessorOib("12312312316");
        tvrtka.setLegalEntityName("Sunce d.o.o.");
        tvrtka.setLegalRepresentativeName("Ana Anić");
        tvrtka.setRepresentativeOib("98765432106");

        String text = textOf(generator.generate(withLessor(tvrtka, List.of("prigovor.pdf"))));

        assertThat(sazeto(text)).contains(sazeto("Naziv / Ime i prezime Sunce d.o.o."))
                .contains(sazeto("Pravni oblik Pravna osoba"))
                .contains(sazeto("Osoba ovlaštena za zastupanje Ana Anić, OIB: 98765432106"));
        assertThat(text).contains("Podnositelj: Ana Anić");
    }

    /** Stranac bez OIB-a: umjesto praznog OIB-a strani porezni broj. Bez učitanih datoteka popis je prazan. */
    @Test
    void stranacBezOib_iBezPriloga() throws Exception {
        LessorEntity stranac = LessorEntity.createNonEuRegistration("John", "Smith", "Main St 5", "john@example.com",
                "john", "hash", null, null, "AT-123456", "+43 1 234");

        String text = textOf(generator.generate(withLessor(stranac, List.of())));

        assertThat(sazeto(text)).contains(sazeto("OIB strani porezni broj: AT-123456"));
        assertThat(text).endsWith("PRILOZI").contains("Podnositelj: JOHN SMITH").doesNotContain("1. ");
    }

    /** Predug naziv datoteke skraćuje se na 120 znakova s „…" — tablica se ne razvlači. */
    @Test
    void dugNazivPriloga_skracen() throws Exception {
        String dug = "a".repeat(200) + ".pdf";

        String text = textOf(generator.generate(withLessor(context(StrDocumentType.PRIGOVOR_SUSPENZIJE,
                List.of("Grad Zagreb"), null).lessor(), List.of(dug))));

        assertThat(sazeto(text)).contains("1." + "a".repeat(119) + "…").doesNotContain("a".repeat(120));
    }

    private static PodnesakPdfContext withLessor(LessorEntity lessor, List<String> prilozi) {
        PodnesakPdfContext c = context(StrDocumentType.PRIGOVOR_SUSPENZIJE, List.of("Grad Zagreb"),
                new FilingReference("K", "U-1"));
        return new PodnesakPdfContext(c.tijelo(), c.naslov(), c.vrstaPodneska(), c.zaprimljeno(), c.filing(),
                c.registrationNumber(), c.odnosiSeNa(), lessor, c.groupName(), c.street(), c.streetNumber(),
                c.postalCode(), c.cityName(), prilozi);
    }

    private static PodnesakPdfContext context(StrDocumentType vrsta, List<String> tijelo, FilingReference filing) {
        LessorEntity lessor = LessorEntity.create("Pero", "Perić", "SJENJAK", "19", "OSIJEK",
                "Osječko-baranjska županija", "pero@example.hr");
        lessor.setLessorOib("12312312316");
        return new PodnesakPdfContext(tijelo, vrsta.naslov(), vrsta.vrstaPismenaNaziv(), ZAPRIMLJENO, filing, RN,
                vrsta.pobijaniAkt().orElseThrow().vrstaPismenaNaziv()
                        + " (KLASA: UP/I-334-06/26-01/7, URBROJ: 2181-26-1)",
                lessor, "Objekti u kojima se pružaju ugostiteljske usluge u domaćinstvu", "Bilice II", "7", "21000",
                "Split", List.of("prigovor.docx", "dokaz.png", "uplatnica.pdf"));
    }

    private static String sazeto(String s) {
        return s.replaceAll("\\s+", "");
    }

    private static String textOf(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder sb = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                sb.append(extractor.getTextFromPage(page)).append('\n');
            }
            return sb.toString().replaceAll("\\s+", " ").strip();
        } finally {
            reader.close();
        }
    }
}
