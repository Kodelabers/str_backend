package com.str.backend.registries.stub;

import com.str.backend.registries.DguClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class StubDguClient implements DguClient {

    private static final Logger log = LoggerFactory.getLogger(StubDguClient.class);

    @Override
    public boolean postojiValjanaSuglasnost(UUID uuidSso) {
        log.debug("dgu_stub suglasnost uuidSso={}", uuidSso);
        return false;
    }
}
