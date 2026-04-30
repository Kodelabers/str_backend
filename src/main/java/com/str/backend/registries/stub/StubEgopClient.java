package com.str.backend.registries.stub;

import com.str.backend.registries.EgopClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class StubEgopClient implements EgopClient {

    private static final Logger log = LoggerFactory.getLogger(StubEgopClient.class);
    private static final String STR_KLASA = "334-01";
    private static final AtomicInteger SEQ = new AtomicInteger(1000);

    @Override
    public UrudzbeniBroj rezervirajUrudzbeniBroj() {
        int year = LocalDate.now().getYear() % 100;
        String klasa = STR_KLASA + "/" + year + "-01/" + SEQ.incrementAndGet();
        String urbroj = "529-06/" + year + "-1";
        Instant now = Instant.now();
        log.info("egop_stub rezerviraj_urudzbeni_broj klasa={} urbroj={}", klasa, urbroj);
        return new UrudzbeniBroj(klasa, urbroj, now);
    }

    @Override
    public PotvrdaUrudzbiranja posaljiZahtjev(String urudzbeniBroj, byte[] pdf) {
        if (urudzbeniBroj == null || urudzbeniBroj.isBlank()) {
            throw new IllegalArgumentException("urudzbeniBroj is required");
        }
        if (pdf == null || pdf.length == 0) {
            throw new IllegalArgumentException("pdf payload is empty");
        }
        log.info("egop_stub posalji_zahtjev urudzbeni={} pdf_size_bytes={}", urudzbeniBroj, pdf.length);
        return new PotvrdaUrudzbiranja(urudzbeniBroj, Instant.now());
    }
}
