package com.str.backend.registries.stub;

import com.str.backend.registries.MpgiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class StubMpgiClient implements MpgiClient {

    private static final Logger log = LoggerFactory.getLogger(StubMpgiClient.class);

    @Override
    public int brojStambenihJedinica(String adresa) {
        log.debug("mpgi_stub brojJedinica adresa={}", adresa);
        return adresa != null && adresa.toLowerCase().contains("zgrada") ? 12 : 1;
    }

}
