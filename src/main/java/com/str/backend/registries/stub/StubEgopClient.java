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
    private static final String STR_CLASS_PREFIX = "334-01";
    private static final AtomicInteger SEQ = new AtomicInteger(
            (int) (System.currentTimeMillis() / 1000) % 1_000_000
    );

    @Override
    public FilingNumber reserveFilingNumber() {
        int year = LocalDate.now().getYear() % 100;
        String classificationCode = STR_CLASS_PREFIX + "/" + year + "-01/" + SEQ.incrementAndGet();
        String referenceNumber = "529-06/" + year + "-1";
        Instant now = Instant.now();
        log.info("egop_stub reserve_filing_number classification_code={} reference_number={}",
                classificationCode, referenceNumber);
        return new FilingNumber(classificationCode, referenceNumber, now);
    }

    @Override
    public FilingConfirmation submitFiling(String filingNumber, byte[] pdf) {
        if (filingNumber == null || filingNumber.isBlank()) {
            throw new IllegalArgumentException("filingNumber is required");
        }
        if (pdf == null || pdf.length == 0) {
            throw new IllegalArgumentException("pdf payload is empty");
        }
        log.info("egop_stub submit_filing filing_number={} pdf_size_bytes={}", filingNumber, pdf.length);
        return new FilingConfirmation(filingNumber, Instant.now());
    }
}
