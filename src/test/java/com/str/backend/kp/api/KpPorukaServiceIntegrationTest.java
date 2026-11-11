package com.str.backend.kp.api;

import com.str.backend.attachment.PrilogSadrzaj;
import com.str.backend.attachment.PrilogService;
import com.str.backend.egop.EgopPismenoEntity;
import com.str.backend.egop.EgopPismenoRepository;
import com.str.backend.kp.api.KpApiClient.Ishod;
import com.str.backend.kp.api.KpApiClient.Odgovor;
import com.str.backend.kp.api.KpApiClient.PorukaZahtjev;
import com.str.backend.kp.api.KpPorukaEntity.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KP API outbox na pravoj bazi (H2): bilježenje u transakciji radnje, slanje sesijom nakon commita,
 * slanje zaostalih poruka po županiji, ishodi i ponavljanje. Klijent je zamijenjen, a pool izvodi
 * zadatke na pozivajućoj niti. RB-ovi {@code HR000000000000952…}.
 */
@SpringBootTest(properties = {
        "str.kp-api.enabled=true",
        "str.kp-api.base-url=https://kp.test/str-internal-api/kp-api",
        "str.kp-api.tip-poruke=1741",
        "str.kp-api.tip-poruke-po-aktu.SUSPENZIJA=1843",
        "str.kp-api.max-attempts=3"
})
@ActiveProfiles("test")
class KpPorukaServiceIntegrationTest {

    private static final int SPLIT = 17;
    private static final int ZADAR = 13;
    private static final byte[] PDF = "%PDF-1.7 akt".getBytes(StandardCharsets.UTF_8);

    @TestConfiguration
    static class DirektanPool {
        @Bean
        @Primary
        KpApiExecutor direktanKpApiExecutor() {
            return KpApiExecutor.direct();
        }
    }

    @Autowired private KpPorukaService service;
    @Autowired private KpPorukaRepository repository;
    @Autowired private EgopPismenoRepository pismenoRepository;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;

    @MockBean private KpApiClient client;
    @MockBean private PrilogService prilogService;

    @BeforeEach
    void setUp() {
        cleanUp();
        when(client.posalji(anyString(), any())).thenReturn(new Odgovor(Ishod.POSLANO, 201, null));
    }

    @AfterEach
    void cleanUp() {
        RequestContextHolder.resetRequestAttributes();
        jdbc.update("DELETE FROM str_rn.kp_poruka WHERE rn LIKE 'HR000000000000952%'");
        jdbc.update("DELETE FROM str_rn.egop_pismeno WHERE rn LIKE 'HR000000000000952%'");
    }

    // ---------------------------------------------------------------- bilježenje i slanje sesijom

    /** Radnja službenika: poruka nastaje u transakciji, a nakon commita ide njegovom sesijom. */
    @Test
    void radnjaSluzbenika_saljeSeNjegovomSesijomNakonCommita() {
        String rn = "HR000000000000952001";
        UUID pismeno = pismeno(rn);
        uZahtjevuSaSesijom("sesija-sluzbenika");

        List<UUID> ids = zabiljezi(akt(rn, pismeno, "REAKTIVACIJA", SPLIT, List.of()));

        ArgumentCaptor<PorukaZahtjev> zahtjev = ArgumentCaptor.forClass(PorukaZahtjev.class);
        verify(client).posalji(eq("sesija-sluzbenika"), zahtjev.capture());
        PorukaZahtjev p = zahtjev.getValue();
        assertThat(p.idPoruke()).isEqualTo(ids.get(0));
        assertThat(p.tipPoruke()).isEqualTo(1741);
        assertThat(p.oib()).isEqualTo("12312312316");
        assertThat(p.klasa()).isEqualTo("334-06/26-01/1");
        assertThat(p.urBroj()).isEqualTo("2181-26-2");
        assertThat(p.privici()).singleElement().satisfies(pr -> {
            assertThat(pr.naziv()).isEqualTo("obavijest.pdf");
            assertThat(pr.sadrzaj()).isEqualTo(Base64.getEncoder().encodeToString(PDF));
        });
        KpPorukaEntity e = repository.findById(ids.get(0)).orElseThrow();
        assertThat(e.getStatus()).isEqualTo(Status.POSLANO);
        assertThat(e.getPoslanoAt()).isNotNull();
        assertThat(e.getNextAttemptAt()).isNull();
    }

    @Test
    void sifraPoAktu_imaPrednost() {
        String rn = "HR000000000000952002";
        List<UUID> ids = zabiljezi(akt(rn, pismeno(rn), "SUSPENZIJA", SPLIT, List.of()));

        assertThat(repository.findById(ids.get(0)).orElseThrow().getTipPoruke()).isEqualTo(1843);
    }

    /** Noćni posao nema sesiju: poruka čeka, a šalje je zahtjev službenika iste županije. */
    @Test
    void bezSesije_cekaISaljeJeSluzbenikIsteZupanije() {
        String rn = "HR000000000000952003";
        List<UUID> ids = zabiljezi(akt(rn, pismeno(rn), "SUSPENZIJA", SPLIT, List.of()));
        verify(client, never()).posalji(anyString(), any());
        assertThat(repository.findById(ids.get(0)).orElseThrow().getStatus()).isEqualTo(Status.PENDING);

        assertThat(service.posaljiZaostale(ZADAR, "sesija-zadar")).isZero();
        verify(client, never()).posalji(anyString(), any());

        assertThat(service.posaljiZaostale(SPLIT, "sesija-split")).isEqualTo(1);
        verify(client).posalji(eq("sesija-split"), any());
        assertThat(repository.findById(ids.get(0)).orElseThrow().getStatus()).isEqualTo(Status.POSLANO);
    }

    /** Prilozi idu svojim imenom, čitani uz RB; što ne stane ide u sljedeću poruku. */
    @Test
    void prilozi_imenomIzRbaIPoPorukama() {
        String rn = "HR000000000000952004";
        when(prilogService.zaPreuzimanje(rn, 1L)).thenReturn(new PrilogSadrzaj("Zapisnik.pdf", "application/pdf", PDF));
        when(prilogService.zaPreuzimanje(rn, 2L)).thenReturn(new PrilogSadrzaj("Fotografije.pdf", "application/pdf", PDF));
        List<UUID> ids = zabiljezi(akt(rn, pismeno(rn), "SUSPENZIJA", SPLIT, List.of(
                new KpAkt.Prilog(1, "Zapisnik.pdf", "application/pdf", 1_500_000),
                new KpAkt.Prilog(2, "Fotografije.pdf", "application/pdf", 1_500_000))));

        assertThat(ids).hasSize(2);
        service.posaljiZaostale(SPLIT, "s");

        ArgumentCaptor<PorukaZahtjev> zahtjevi = ArgumentCaptor.forClass(PorukaZahtjev.class);
        verify(client, times(2)).posalji(eq("s"), zahtjevi.capture());
        assertThat(zahtjevi.getAllValues()).extracting(z -> z.privici().stream().map(KpApiClient.Privitak::naziv).toList())
                .containsExactly(List.of("obavijest.pdf", "Zapisnik.pdf"), List.of("Fotografije.pdf"));
    }

    // ---------------------------------------------------------------- ishodi

    @Test
    void greskaKp_odgodaPaOdustajanje() {
        String rn = "HR000000000000952005";
        when(client.posalji(anyString(), any())).thenReturn(new Odgovor(Ishod.GRESKA, 502, "Greška u komunikaciji"));
        UUID id = zabiljezi(akt(rn, pismeno(rn), "SUSPENZIJA", SPLIT, List.of())).get(0);

        service.posaljiZaostale(SPLIT, "s");
        KpPorukaEntity e = repository.findById(id).orElseThrow();
        assertThat(e.getStatus()).isEqualTo(Status.PENDING);
        assertThat(e.getAttempts()).isEqualTo(1);
        assertThat(e.getNextAttemptAt()).isAfter(Instant.now().plusSeconds(60));
        assertThat(e.getGreska()).isEqualTo("Greška u komunikaciji");

        // Odgoda: odmah ponovno se ne šalje.
        assertThat(service.posaljiZaostale(SPLIT, "s")).isZero();

        dospjelo(id);
        service.posaljiZaostale(SPLIT, "s");
        dospjelo(id);
        service.posaljiZaostale(SPLIT, "s");
        e = repository.findById(id).orElseThrow();
        assertThat(e.getStatus()).isEqualTo(Status.ABANDONED);
        assertThat(e.getAttempts()).isEqualTo(3);
        assertThat(e.getNextAttemptAt()).isNull();
    }

    /** Sesija koju servis ne prihvati ne troši pokušaj; poruku odmah može poslati druga sesija. */
    @Test
    void neprihvacenaSesija_neTrosiPokusaj() {
        String rn = "HR000000000000952006";
        when(client.posalji(eq("istekla"), any())).thenReturn(new Odgovor(Ishod.NEAUTORIZIRAN, 302, "sesija"));
        UUID id = zabiljezi(akt(rn, pismeno(rn), "SUSPENZIJA", SPLIT, List.of())).get(0);

        service.posaljiZaostale(SPLIT, "istekla");
        KpPorukaEntity e = repository.findById(id).orElseThrow();
        assertThat(e.getStatus()).isEqualTo(Status.PENDING);
        assertThat(e.getAttempts()).isZero();

        service.posaljiZaostale(SPLIT, "valjana");
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(Status.POSLANO);
    }

    @Test
    void nemaPretinca_konacno() {
        String rn = "HR000000000000952007";
        when(client.posalji(anyString(), any())).thenReturn(new Odgovor(Ishod.NEMA_PRETINCA, 422, "K006: …"));
        UUID id = zabiljezi(akt(rn, pismeno(rn), "SUSPENZIJA", SPLIT, List.of())).get(0);

        service.posaljiZaostale(SPLIT, "s");
        service.posaljiZaostale(SPLIT, "s");

        KpPorukaEntity e = repository.findById(id).orElseThrow();
        assertThat(e.getStatus()).isEqualTo(Status.NEMA_PRETINCA);
        assertThat(e.getGreska()).isEqualTo("K006: …");
        verify(client, times(1)).posalji(anyString(), any());
    }

    /** Zauzeta poruka (slanje u tijeku u drugom zahtjevu) se ne šalje ponovno. */
    @Test
    void zauzeta_neSaljeSeDvaput() {
        String rn = "HR000000000000952008";
        UUID id = zabiljezi(akt(rn, pismeno(rn), "SUSPENZIJA", SPLIT, List.of())).get(0);
        jdbc.update("UPDATE str_rn.kp_poruka SET next_attempt_at = ? WHERE id_poruke = ?",
                Timestamp.from(Instant.now().plusSeconds(60)), id);

        service.posalji(id, "s");

        verify(client, never()).posalji(anyString(), any());
    }

    /** Pad pripreme (npr. PDF akta nedostaje) je obični neuspjeh, ne iznimka. */
    @Test
    void bezPdfa_neuspjehBezIznimke() {
        String rn = "HR000000000000952009";
        UUID id = zabiljezi(akt(rn, UUID.randomUUID(), "SUSPENZIJA", SPLIT, List.of())).get(0);

        service.posalji(id, "s");

        KpPorukaEntity e = repository.findById(id).orElseThrow();
        assertThat(e.getAttempts()).isEqualTo(1);
        assertThat(e.getGreska()).contains("PDF akta nije pronađen");
        verify(client, never()).posalji(anyString(), any());
    }

    // ---------------------------------------------------------------- što se ne bilježi

    @Test
    void neispravanOib_neBiljeziNista() {
        String rn = "HR000000000000952010";
        KpAkt akt = new KpAkt(pismeno(rn), rn, "1234", "SUSPENZIJA", SPLIT, "N", "S", null, null, "a.pdf", 10, List.of());

        assertThat(zabiljezi(akt)).isEmpty();
    }

    @Test
    void izvanTransakcije_pada() {
        String rn = "HR000000000000952011";
        assertThatThrownBy(() -> service.zabiljezi(akt(rn, UUID.randomUUID(), "SUSPENZIJA", SPLIT, List.of())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ---------------------------------------------------------------- pomoćno

    private List<UUID> zabiljezi(KpAkt akt) {
        return new TransactionTemplate(txManager).execute(tx -> service.zabiljezi(akt));
    }

    private static KpAkt akt(String rn, UUID pismeno, String vrsta, int zupanija, List<KpAkt.Prilog> prilozi) {
        return new KpAkt(pismeno, rn, "12312312316", vrsta, zupanija, "Obavijest o suspenziji " + rn,
                "Poštovani, …", "334-06/26-01/1", "2181-26-2", "obavijest.pdf", PDF.length, prilozi);
    }

    private UUID pismeno(String rn) {
        return pismenoRepository.save(EgopPismenoEntity.forAct(UUID.randomUUID(), rn, UUID.randomUUID().toString(),
                "Obavijest o suspenziji registracijskog broja", EgopPismenoEntity.Smjer.IZLAZNO, PDF)).getId();
    }

    private static void uZahtjevuSaSesijom(String id) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new org.springframework.mock.web.MockHttpSession(null, id));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private void dospjelo(UUID id) {
        jdbc.update("UPDATE str_rn.kp_poruka SET next_attempt_at = ? WHERE id_poruke = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), id);
    }
}
