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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KP API outbox na pravoj bazi (H2): bilježenje u transakciji radnje, slanje odmah nakon commita, retry posao,
 * ishodi i odgode. Klijent je zamijenjen, a pool izvodi zadatke na pozivajućoj niti. RB-ovi
 * {@code HR000000000000952…}.
 */
@SpringBootTest(properties = {
        "str.kp-api.enabled=true",
        "str.kp-api.base-url=https://kp.test/str-internal-api/kp-api",
        "str.kp-api.username=short-term-rental",
        "str.kp-api.password=test-lozinka",
        "str.kp-api.tip-poruke=1741",
        "str.kp-api.tip-poruke-po-aktu.SUSPENZIJA=1843",
        "str.kp-api.max-attempts=3",
        // Posao se zove ručno; raspored bi ga pokrenuo usred testa.
        "str.kp-api.retry.cron=-"
})
@ActiveProfiles("test")
class KpPorukaServiceIntegrationTest {

    private static final int SPLIT = 17;
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
        when(client.posalji(any())).thenReturn(new Odgovor(Ishod.POSLANO, 201, null));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM str_rn.kp_poruka WHERE rn LIKE 'HR000000000000952%'");
        jdbc.update("DELETE FROM str_rn.egop_pismeno WHERE rn LIKE 'HR000000000000952%'");
    }

    // ---------------------------------------------------------------- bilježenje i slanje

    /** Poruka nastaje u transakciji radnje i nakon commita odmah odlazi — i bez službenika (noćni posao). */
    @Test
    void nakonCommita_saljeSeOdmah() {
        String rn = "HR000000000000952001";
        List<UUID> ids = zabiljezi(akt(rn, pismeno(rn), "REAKTIVACIJA", List.of()));

        ArgumentCaptor<PorukaZahtjev> zahtjev = ArgumentCaptor.forClass(PorukaZahtjev.class);
        verify(client).posalji(zahtjev.capture());
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
        List<UUID> ids = zabiljezi(akt(rn, pismeno(rn), "SUSPENZIJA", List.of()));

        assertThat(repository.findById(ids.get(0)).orElseThrow().getTipPoruke()).isEqualTo(1843);
    }

    /** Prilozi idu svojim imenom, čitani uz RB; što ne stane ide u sljedeću poruku. */
    @Test
    void prilozi_imenomIzRbaIPoPorukama() {
        String rn = "HR000000000000952004";
        when(prilogService.zaPreuzimanje(rn, 1L)).thenReturn(new PrilogSadrzaj("Zapisnik.pdf", "application/pdf", PDF));
        when(prilogService.zaPreuzimanje(rn, 2L)).thenReturn(new PrilogSadrzaj("Fotografije.pdf", "application/pdf", PDF));

        List<UUID> ids = zabiljezi(akt(rn, pismeno(rn), "SUSPENZIJA", List.of(
                new KpAkt.Prilog(1, "Zapisnik.pdf", "application/pdf", 1_500_000),
                new KpAkt.Prilog(2, "Fotografije.pdf", "application/pdf", 1_500_000))));

        assertThat(ids).hasSize(2);
        ArgumentCaptor<PorukaZahtjev> zahtjevi = ArgumentCaptor.forClass(PorukaZahtjev.class);
        verify(client, times(2)).posalji(zahtjevi.capture());
        assertThat(zahtjevi.getAllValues()).extracting(z -> z.privici().stream().map(KpApiClient.Privitak::naziv).toList())
                .containsExactlyInAnyOrder(List.of("obavijest.pdf", "Zapisnik.pdf"), List.of("Fotografije.pdf"));
    }

    // ---------------------------------------------------------------- ishodi i retry posao

    @Test
    void greskaKp_odgodaRetryPaOdustajanje() {
        String rn = "HR000000000000952005";
        when(client.posalji(any())).thenReturn(new Odgovor(Ishod.GRESKA, 502, "Greška u komunikaciji"));
        UUID id = zabiljezi(akt(rn, pismeno(rn), "SUSPENZIJA", List.of())).get(0);

        KpPorukaEntity e = repository.findById(id).orElseThrow();
        assertThat(e.getStatus()).isEqualTo(Status.PENDING);
        assertThat(e.getAttempts()).isEqualTo(1);
        assertThat(e.getNextAttemptAt()).isAfter(Instant.now().plusSeconds(60));
        assertThat(e.getGreska()).isEqualTo("Greška u komunikaciji");

        // Odgoda: retry posao je još ne uzima.
        assertThat(service.posaljiDospjele()).isZero();

        dospjelo(id);
        assertThat(service.posaljiDospjele()).isEqualTo(1);
        dospjelo(id);
        service.posaljiDospjele();
        e = repository.findById(id).orElseThrow();
        assertThat(e.getStatus()).isEqualTo(Status.ABANDONED);
        assertThat(e.getAttempts()).isEqualTo(3);
        assertThat(e.getNextAttemptAt()).isNull();
    }

    /** Odbijena prijava je konfiguracija: pokušaj se ne broji, poruka čeka i odlazi kad prijava prođe. */
    @Test
    void odbijenaPrijava_neTrosiPokusaj_odgodaPaRetry() {
        String rn = "HR000000000000952006";
        when(client.posalji(any())).thenReturn(
                new Odgovor(Ishod.NEAUTORIZIRAN, 403, "prijava odbijena: HTTP 403 — Invalid CSRF token"));
        UUID id = zabiljezi(akt(rn, pismeno(rn), "SUSPENZIJA", List.of())).get(0);

        KpPorukaEntity e = repository.findById(id).orElseThrow();
        assertThat(e.getStatus()).isEqualTo(Status.PENDING);
        assertThat(e.getAttempts()).isZero();
        assertThat(e.getGreska()).contains("Invalid CSRF token");
        assertThat(e.getNextAttemptAt()).isAfter(Instant.now().plusSeconds(60));
        assertThat(service.posaljiDospjele()).isZero();

        when(client.posalji(any())).thenReturn(new Odgovor(Ishod.POSLANO, 201, null));
        dospjelo(id);
        assertThat(service.posaljiDospjele()).isEqualTo(1);
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(Status.POSLANO);
    }

    @Test
    void nemaPretinca_konacno() {
        String rn = "HR000000000000952007";
        when(client.posalji(any())).thenReturn(new Odgovor(Ishod.NEMA_PRETINCA, 422, "K006: …"));
        UUID id = zabiljezi(akt(rn, pismeno(rn), "SUSPENZIJA", List.of())).get(0);
        dospjelo(id);

        assertThat(service.posaljiDospjele()).isZero();

        KpPorukaEntity e = repository.findById(id).orElseThrow();
        assertThat(e.getStatus()).isEqualTo(Status.NEMA_PRETINCA);
        assertThat(e.getGreska()).isEqualTo("K006: …");
        verify(client, times(1)).posalji(any());
    }

    /** Zauzeta poruka (slanje u tijeku u drugoj niti) se ne šalje ponovno. */
    @Test
    void zauzeta_neSaljeSeDvaput() {
        String rn = "HR000000000000952008";
        when(client.posalji(any())).thenReturn(new Odgovor(Ishod.GRESKA, 502, "x"));
        UUID id = zabiljezi(akt(rn, pismeno(rn), "SUSPENZIJA", List.of())).get(0);
        jdbc.update("UPDATE str_rn.kp_poruka SET next_attempt_at = ? WHERE id_poruke = ?",
                Timestamp.from(Instant.now().plusSeconds(60)), id);

        service.posalji(id);

        verify(client, times(1)).posalji(any());
    }

    /** Pad pripreme (npr. PDF akta nedostaje) je obični neuspjeh, ne iznimka. */
    @Test
    void bezPdfa_neuspjehBezIznimke() {
        String rn = "HR000000000000952009";
        UUID id = zabiljezi(akt(rn, UUID.randomUUID(), "SUSPENZIJA", List.of())).get(0);

        KpPorukaEntity e = repository.findById(id).orElseThrow();
        assertThat(e.getAttempts()).isEqualTo(1);
        assertThat(e.getGreska()).contains("PDF akta nije pronađen");
        verify(client, never()).posalji(any());
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
        assertThatThrownBy(() -> service.zabiljezi(akt(rn, UUID.randomUUID(), "SUSPENZIJA", List.of())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ---------------------------------------------------------------- pomoćno

    private List<UUID> zabiljezi(KpAkt akt) {
        return new TransactionTemplate(txManager).execute(tx -> service.zabiljezi(akt));
    }

    private static KpAkt akt(String rn, UUID pismeno, String vrsta, List<KpAkt.Prilog> prilozi) {
        return new KpAkt(pismeno, rn, "12312312316", vrsta, SPLIT, "Obavijest o suspenziji " + rn,
                "Poštovani, …", "334-06/26-01/1", "2181-26-2", "obavijest.pdf", PDF.length, prilozi);
    }

    private UUID pismeno(String rn) {
        return pismenoRepository.save(EgopPismenoEntity.forAct(UUID.randomUUID(), rn, UUID.randomUUID().toString(),
                "Obavijest o suspenziji registracijskog broja", EgopPismenoEntity.Smjer.IZLAZNO, PDF)).getId();
    }

    private void dospjelo(UUID id) {
        jdbc.update("UPDATE str_rn.kp_poruka SET next_attempt_at = ? WHERE id_poruke = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), id);
    }
}
