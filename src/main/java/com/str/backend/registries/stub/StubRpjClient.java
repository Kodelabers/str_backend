package com.str.backend.registries.stub;

import com.str.backend.registries.RpjClient;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class StubRpjClient implements RpjClient {

    @Override
    public Optional<Adresa> normalizirajAdresu(String zupanija, String grad, String ulica, String kucniBroj) {
        return Optional.empty();
    }
}
