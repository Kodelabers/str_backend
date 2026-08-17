package com.str.backend.egop;

import com.str.backend.document.StrDocumentType;
import com.str.backend.egop.exception.EgopBadRequestException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Preslikavanje naših vrsta na eGOP šifre. Dvije stvari koje moraju držati:
 * prioritet (zadana šifra prije naziva prije fallbacka) i oznaka posudbenosti — po njoj se
 * kasnije zna što treba stornirati.
 */
class EgopVrstaResolverTest {

    private static final String ZAHTJEV = StrDocumentType.ZAHTJEV.vrstaPismenaNaziv();
    private static final String SUSPENZIJA = StrDocumentType.SUSPENZIJA.vrstaPismenaNaziv();
    private static final String OBUSTAVA = StrDocumentType.OBUSTAVA_SUSPENZIJE.vrstaPismenaNaziv();

    private final EgopClient client = mock(EgopClient.class);

    private static EgopVrsteProperties props(EgopVrsteProperties.Sifre sifre,
                                             EgopVrsteProperties.Nazivi nazivi) {
        return new EgopVrsteProperties(sifre, nazivi);
    }

    private static EgopVrsteProperties.Sifre sifre(Map<String, String> pismena, String fallback,
                                                  boolean privremene) {
        return new EgopVrsteProperties.Sifre(null, null, null, null, pismena, fallback, privremene);
    }

    private static EgopVrsteProperties.Nazivi nazivi(Map<String, String> pismena, String fallback) {
        return new EgopVrsteProperties.Nazivi("Izdavanje Registracijskog broja",
                "MINISTARSTVO TURIZMA", pismena, fallback);
    }

    @Test
    void zadanaSifraPoSlugu_imaPrednostIPreskaceMdm() throws Exception {
        EgopVrstaResolver resolver = new EgopVrstaResolver(client,
                props(sifre(Map.of("zahtjev", "100"), null, true), nazivi(Map.of(), null)));

        EgopVrstaResolver.Vrsta vrsta = resolver.vrstaPismena(ZAHTJEV);

        assertThat(vrsta.sifra()).isEqualTo("100");
        assertThat(vrsta.privremena()).isTrue();
        // Zadana šifra znači da MDM ne smije biti na kritičnom putu — na InfoDom test okolini
        // dio šifrarnika vraća prazno, pa bi dohvat bio i beskorisan i skup.
        verify(client, never()).getVrstePismena();
    }

    @Test
    void slugSCrticom_vezanIzEnvVarijableSPodvlakom() throws Exception {
        // Spring iz STR_EGOP_VRSTE_SIFRE_PISMENA_OBUSTAVA_SUSPENZIJE veže ključ s podvlakom,
        // a slug je "obustava-suspenzije" — bez izjednačavanja override tiho promaši.
        EgopVrstaResolver resolver = new EgopVrstaResolver(client,
                props(sifre(Map.of("obustava_suspenzije", "57"), null, true), nazivi(Map.of(), null)));

        assertThat(resolver.vrstaPismena(OBUSTAVA).sifra()).isEqualTo("57");
    }

    @Test
    void vlastitiNazivIzSifrarnika_nijePosudben() throws Exception {
        when(client.getVrstePismena()).thenReturn(Map.of(SUSPENZIJA, "777"));
        EgopVrstaResolver resolver = new EgopVrstaResolver(client,
                props(sifre(Map.of(), "57", true), nazivi(Map.of(), null)));

        EgopVrstaResolver.Vrsta vrsta = resolver.vrstaPismena(SUSPENZIJA);

        // eGOP ima NAŠU vrstu — fallback se ne koristi i nema se što kasnije stornirati,
        // makar je privremene=true za ostale vrste.
        assertThat(vrsta.sifra()).isEqualTo("777");
        assertThat(vrsta.privremena()).isFalse();
    }

    @Test
    void fallbackPokrivaVrsteKojihSifrarnikNema() throws Exception {
        when(client.getVrstePismena()).thenReturn(Map.of("Dopis", "3", "Obavijest", "57"));
        EgopVrstaResolver resolver = new EgopVrstaResolver(client,
                props(sifre(Map.of(), "57", true), nazivi(Map.of(), null)));

        EgopVrstaResolver.Vrsta vrsta = resolver.vrstaPismena(SUSPENZIJA);

        assertThat(vrsta.sifra()).isEqualTo("57");
        assertThat(vrsta.privremena()).isTrue();
    }

    @Test
    void zadaniNaziv_razrjesavaSeKrozMdmIPrekoNasegNaziva() throws Exception {
        when(client.getVrstePismena()).thenReturn(Map.of("Rješenje", "81", SUSPENZIJA, "777"));
        EgopVrstaResolver resolver = new EgopVrstaResolver(client,
                props(sifre(Map.of(), null, true), nazivi(Map.of("suspenzija", "Rješenje"), null)));

        EgopVrstaResolver.Vrsta vrsta = resolver.vrstaPismena(SUSPENZIJA);

        assertThat(vrsta.sifra()).isEqualTo("81");
        assertThat(vrsta.privremena()).isTrue();
    }

    @Test
    void bezSifreINazivaIFallbacka_ostajeGreska() {
        when(client.getVrstePismena()).thenReturn(Map.of("Dopis", "3"));
        EgopVrstaResolver resolver = new EgopVrstaResolver(client,
                props(sifre(Map.of(), null, false), nazivi(Map.of(), null)));

        assertThatThrownBy(() -> resolver.vrstaPismena(SUSPENZIJA))
                .isInstanceOf(EgopBadRequestException.class)
                .hasMessageContaining(SUSPENZIJA);
    }

    @Test
    void privremeneFalse_neOznacavaAkteZaStorniranje() throws Exception {
        EgopVrstaResolver resolver = new EgopVrstaResolver(client,
                props(sifre(Map.of("zahtjev", "100"), null, false), nazivi(Map.of(), null)));

        assertThat(resolver.vrstaPismena(ZAHTJEV).privremena()).isFalse();
    }

    @Test
    void svakaVrstaPismenaSeRazrjesava_uzZadanFallback() throws Exception {
        when(client.getVrstePismena()).thenReturn(Map.of("Obavijest", "57"));
        EgopVrstaResolver resolver = new EgopVrstaResolver(client,
                props(sifre(Map.of("zahtjev", "100", "prigovor", "62"), "57", true),
                        nazivi(Map.of(), null)));

        for (StrDocumentType type : StrDocumentType.values()) {
            assertThat(resolver.vrstaPismena(type.vrstaPismenaNaziv()).sifra())
                    .as("vrsta pismena %s", type.slug())
                    .isNotBlank();
        }
    }

    @Test
    void predmetUstrojISubjekt_zadaneSifrePreskacuMdm() throws Exception {
        EgopVrsteProperties.Sifre sifre = new EgopVrsteProperties.Sifre("7765", 559, "3", "2",
                Map.of(), null, true);
        EgopVrstaResolver resolver = new EgopVrstaResolver(client, props(sifre, nazivi(Map.of(), null)));

        assertThat(resolver.vrstaPredmeta()).isEqualTo("7765");
        assertThat(resolver.nadleznaOrgJedinica()).isEqualTo(559);
        assertThat(resolver.tipOsobe(false)).isEqualTo("3");
        assertThat(resolver.tipOsobe(true)).isEqualTo("2");
        // Naziv predmeta ostaje naš i čitljiv, neovisno od posudbene šifre.
        assertThat(resolver.predmetNaziv()).isEqualTo("Izdavanje Registracijskog broja");
        verify(client, never()).getVrstePredmeta();
        verify(client, never()).getUstroj();
        verify(client, never()).getVrstePoslovnihSubjekata();
    }

    @Test
    void predmetUstrojISubjekt_bezSifriIduPrekoNaziva() throws Exception {
        when(client.getVrstePredmeta()).thenReturn(Map.of("Izdavanje Registracijskog broja", "9282"));
        when(client.getUstroj()).thenReturn(Map.of("MINISTARSTVO TURIZMA", 559));
        when(client.getVrstePoslovnihSubjekata()).thenReturn(Map.of("Fizička osoba", "3"));
        EgopVrstaResolver resolver = new EgopVrstaResolver(client,
                props(sifre(Map.of(), null, false), nazivi(Map.of(), null)));

        assertThat(resolver.vrstaPredmeta()).isEqualTo("9282");
        assertThat(resolver.nadleznaOrgJedinica()).isEqualTo(559);
        assertThat(resolver.tipOsobe(false)).isEqualTo("3");
    }
}
