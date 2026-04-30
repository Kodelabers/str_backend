package com.str.backend.registries.stub;

import com.str.backend.core.rpj.CoreRpjAdresaRepository;
import com.str.backend.registries.RpjClient;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class StubRpjClient implements RpjClient {

    private final CoreRpjAdresaRepository repository;

    public StubRpjClient(CoreRpjAdresaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Adresa> normalizirajAdresu(String zupanija, String grad, String ulica, String kucniBroj) {
        if (zupanija == null || grad == null || ulica == null || kucniBroj == null) {
            return Optional.empty();
        }
        return repository.findFirstByZupanijaAndGradAndUlicaAndKucniBroj(zupanija, grad, ulica, kucniBroj)
                .map(a -> new Adresa(a.getZupanija(), a.getGrad(), a.getNaselje(), a.getUlica(),
                        a.getKucniBroj(), a.getPostanskiBroj()));
    }
}
