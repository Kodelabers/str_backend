package com.str.backend.registries.stub;

import com.str.backend.registries.GisClient;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class StubGisClient implements GisClient {

    @Override
    public Optional<Parcela> dohvatiParcelu(String katastarskaOpcina, String brojCestice) {
        return Optional.empty();
    }
}
