package com.str.backend.registries.stub;

import com.str.backend.registries.SrClient;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class StubSrClient implements SrClient {

    @Override
    public Optional<PravnaOsoba> dohvatiPravnuOsobu(String oib) {
        return Optional.empty();
    }
}
