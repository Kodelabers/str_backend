package com.str.backend.registries.stub;

import com.str.backend.registries.EgopClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StubEgopClient implements EgopClient {

    private static final Logger log = LoggerFactory.getLogger(StubEgopClient.class);

    @Override
    public List<Zastupnik> dohvatiZastupnike(String oibPravneOsobe) {
        log.debug("egop_stub dohvatiZastupnike called");
        if (oibPravneOsobe == null || oibPravneOsobe.isBlank()) {
            return List.of();
        }
        return List.of(
                new Zastupnik("12345678901", "Ivan", "Horvat", "Ilica 1, Zagreb"),
                new Zastupnik("23456789012", "Ana", "Kovac", "Ilica 1, Zagreb")
        );
    }
}
